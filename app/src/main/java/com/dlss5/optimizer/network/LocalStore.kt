package com.dlss5.optimizer.network

import android.content.Context
import com.dlss5.optimizer.core.SessionRecord
import com.dlss5.optimizer.util.Logger
import com.dlss5.optimizer.util.Time
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Crash-safe, allocation-light persistence built on plain files instead of Room.
 *
 *  * `sessions.jsonl`        — append-only session log, capped at 50 records.
 *  * `telemetry_queue.jsonl` — append-only upload queue, capped at 5 000 samples.
 *  * `sessions_ack.txt`      — ids the server has accepted.
 *
 * Why not SQLite: zero schema-migration risk, zero extra dependencies, ~0.5 MB smaller APK, and if the
 * process dies mid-write we lose at most one line (the readers skip unparsable lines).
 */
class LocalStore private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val dir: File = File(appContext.filesDir, "dlss5").apply { mkdirs() }
    private val sessionsFile = File(dir, "sessions.jsonl")
    private val queueFile = File(dir, "telemetry_queue.jsonl")
    private val lock = Any()

    companion object {
        private const val TAG = "LocalStore"
        private const val MAX_SESSIONS = 50
        private const val MAX_QUEUE = 5000

        @Volatile
        private var instance: LocalStore? = null

        fun get(context: Context): LocalStore =
            instance ?: synchronized(this) {
                instance ?: LocalStore(context).also { instance = it }
            }
    }

    // ---------------------------------------------------------------- sessions

    fun appendSession(record: SessionRecord) {
        synchronized(lock) {
            try {
                val json = JSONObject(record.toLocalJson()).apply { put("mode_int", record.mode) }
                sessionsFile.appendText(json.toString() + "\n")
                trimSessions()
                Logger.d(TAG, "session stored ${record.sessionId}")
            } catch (t: Throwable) {
                Logger.w(TAG, "appendSession failed: ${t.message}")
            }
        }
    }

    fun sessions(limit: Int = 20): List<SessionRecord> {
        synchronized(lock) {
            return try {
                if (!sessionsFile.exists()) return emptyList()
                sessionsFile.readLines()
                    .asSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull { SessionRecord.fromLocalJson(it) }
                    .sortedByDescending { it.startedAtMs }
                    .take(limit)
                    .toList()
            } catch (t: Throwable) {
                Logger.w(TAG, "sessions read failed: ${t.message}")
                emptyList()
            }
        }
    }

    fun unsyncedSessions(limit: Int = 20): List<SessionRecord> {
        synchronized(lock) {
            return sessions(limit = MAX_SESSIONS).filter { !it.synced }.take(limit)
        }
    }

    fun markSessionSynced(sessionId: String) {
        synchronized(lock) {
            try {
                if (!sessionsFile.exists()) return
                val lines = sessionsFile.readLines()
                val rebuilt = StringBuilder()
                for (line in lines) {
                    if (line.isBlank()) continue
                    val obj = try {
                        JSONObject(line)
                    } catch (t: Throwable) {
                        continue
                    }
                    if (obj.optString("session_id") == sessionId) {
                        obj.put("synced", true)
                    }
                    rebuilt.append(obj.toString()).append('\n')
                }
                sessionsFile.writeText(rebuilt.toString())
            } catch (t: Throwable) {
                Logger.w(TAG, "markSessionSynced failed: ${t.message}")
            }
        }
    }

    private fun trimSessions() {
        try {
            if (!sessionsFile.exists()) return
            val lines = sessionsFile.readLines().filter { it.isNotBlank() }
            if (lines.size <= MAX_SESSIONS) return
            val keep = lines.takeLast(MAX_SESSIONS)
            sessionsFile.writeText(keep.joinToString("\n", postfix = "\n"))
        } catch (t: Throwable) {
            Logger.w(TAG, "trimSessions failed: ${t.message}")
        }
    }

    fun sessionStats(): Pair<Int, Double> {
        val all = sessions(MAX_SESSIONS)
        if (all.isEmpty()) return 0 to 0.0
        return all.size to all.map { it.avgFps }.average()
    }

    // ---------------------------------------------------------------- telemetry queue

    fun enqueueTelemetry(sample: JSONObject) {
        synchronized(lock) {
            try {
                queueFile.appendText(sample.toString() + "\n")
                if (queueFile.length() > 1_500_000L) {
                    compactQueue()
                }
            } catch (t: Throwable) {
                Logger.w(TAG, "enqueueTelemetry failed: ${t.message}")
            }
        }
    }

    fun queueSize(): Int = try {
        synchronized(lock) { if (queueFile.exists()) queueFile.readLines().count { it.isNotBlank() } else 0 }
    } catch (t: Throwable) {
        0
    }

    /** Pops up to [max] queued samples, newest-last preserved. */
    fun drainTelemetry(max: Int): List<String> {
        synchronized(lock) {
            return try {
                if (!queueFile.exists()) return emptyList()
                val lines = queueFile.readLines().filter { it.isNotBlank() }
                if (lines.isEmpty()) return emptyList()
                lines.take(max)
            } catch (t: Throwable) {
                Logger.w(TAG, "drainTelemetry failed: ${t.message}")
                emptyList()
            }
        }
    }

    /** Removes exactly the lines that were acknowledged, keeping anything newer. */
    fun ackTelemetry(count: Int) {
        synchronized(lock) {
            try {
                if (!queueFile.exists() || count <= 0) return
                val lines = queueFile.readLines().filter { it.isNotBlank() }
                val remaining = lines.drop(count)
                val tmp = File(dir, "telemetry_queue.jsonl.tmp")
                FileOutputStream(tmp).use { out ->
                    remaining.forEach { out.write((it + "\n").toByteArray(Charsets.UTF_8)) }
                    out.flush()
                    out.fd.sync()
                }
                if (tmp.renameTo(queueFile)) {
                    Logger.d(TAG, "acked $count samples, ${remaining.size} left")
                } else {
                    Logger.w(TAG, "queue rename failed")
                }
            } catch (t: Throwable) {
                Logger.w(TAG, "ackTelemetry failed: ${t.message}")
            }
        }
    }

    private fun compactQueue() {
        try {
            val lines = queueFile.readLines().filter { it.isNotBlank() }
            val keep = lines.takeLast(MAX_QUEUE)
            queueFile.writeText(keep.joinToString("\n", postfix = "\n"))
            Logger.i(TAG, "compacted queue to ${keep.size} samples")
        } catch (t: Throwable) {
            Logger.w(TAG, "compactQueue failed: ${t.message}")
        }
    }

    fun clearAll() {
        synchronized(lock) {
            try {
                sessionsFile.delete()
                queueFile.delete()
            } catch (t: Throwable) {
                Logger.w(TAG, "clearAll failed: ${t.message}")
            }
        }
    }

    fun storageBytes(): Long = try {
        (sessionsFile.length() + queueFile.length())
    } catch (t: Throwable) {
        0L
    }

    /** Diagnostics blob shown in the app's "about" area. */
    fun diagnostics(): String = buildString {
        appendLine("sessions=" + sessions(MAX_SESSIONS).size)
        appendLine("queue=" + queueSize())
        appendLine("storage=" + storageBytes() + "B")
        val lastSync = com.dlss5.optimizer.util.Prefs.get(appContext).lastSyncEpoch
        append("lastSync=" + if (lastSync == 0L) "never" else Time.isoFromEpochMs(lastSync * 1000L))
    }

    fun exportSessionsJson(): String {
        val arr = JSONArray()
        sessions(MAX_SESSIONS).forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    @Suppress("unused")
    private fun ensureWritable(): Boolean = try {
        val probe = File(dir, ".probe")
        probe.writeText("ok")
        val ok = probe.exists()
        probe.delete()
        ok
    } catch (t: Throwable) {
        Logger.w(TAG, "storage not writable: ${t.message}")
        false
    }
}
