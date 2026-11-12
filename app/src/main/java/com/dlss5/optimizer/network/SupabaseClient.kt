package com.dlss5.optimizer.network

import com.dlss5.optimizer.core.DeviceProfile
import com.dlss5.optimizer.core.SessionRecord
import com.dlss5.optimizer.util.Logger
import com.dlss5.optimizer.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Minimal PostgREST client.
 *
 * Deliberately dependency-free: four endpoints, ~200 lines, no Retrofit/OkHttp/Supabase-SDK. Keeps the
 * APK small and the build reproducible in CI. Every call is blocking and must run off the main thread
 * (SyncWorker / Dispatchers.IO). Nothing here throws to the caller — failures return null/false and are
 * retried by WorkManager.
 */
class SupabaseClient(private val prefs: Prefs) {

    companion object {
        private const val TAG = "Supabase"
    }

    data class Result(val ok: Boolean, val code: Int, val body: String)

    // ---------------------------------------------------------------- low level

    private fun request(
        url: String,
        method: String,
        body: String?,
        extraHeaders: Map<String, String> = emptyMap()
    ): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = SupabaseConfig.CONNECT_TIMEOUT_MS
                readTimeout = SupabaseConfig.READ_TIMEOUT_MS
                setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
                setRequestProperty("Authorization", "Bearer ${SupabaseConfig.ANON_KEY}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Device-Id", prefs.deviceId)
                setRequestProperty("X-Client-Info", "dlss5-android/1.0.0")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(body.toByteArray(StandardCharsets.UTF_8).size)
                }
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { readAll(it) } ?: ""
            if (code !in 200..299) {
                Logger.w(TAG, "$method $url -> $code $text")
            }
            Result(code in 200..299, code, text)
        } catch (t: Throwable) {
            Logger.w(TAG, "$method $url failed: ${t.message}")
            Result(false, -1, t.message ?: "network error")
        } finally {
            try {
                conn?.disconnect()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    private fun readAll(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            out.write(buf, 0, read)
        }
        return out.toString("UTF-8")
    }

    // ---------------------------------------------------------------- devices

    fun upsertDevice(profile: DeviceProfile, deviceId: String, appVersion: String): Boolean {
        if (!SupabaseConfig.isConfigured()) return false
        val payload = JSONArray().put(JSONObject().apply {
            put("device_id", deviceId)
            put("manufacturer", profile.manufacturer)
            put("model", profile.model)
            put("android_release", profile.androidRelease)
            put("sdk_int", profile.sdkInt)
            put("soc_model", profile.socModel)
            put("soc_tier", profile.tier.key)
            put("total_ram_mb", profile.totalRamMb)
            put("cpu_cores", profile.cpuCores)
            put("max_refresh_hz", profile.maxRefreshHz.toDouble())
            put("supports_es3", profile.supportsEs3)
            put("app_version", appVersion)
            put("last_seen", com.dlss5.optimizer.util.Time.isoNowUtc())
        })
        val res = request(
            "${SupabaseConfig.restUrl(SupabaseConfig.TABLE_DEVICES)}?on_conflict=device_id",
            "POST",
            payload.toString(),
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
        )
        return res.ok
    }

    // ---------------------------------------------------------------- sessions

    fun upsertSession(session: SessionRecord): Boolean {
        if (!SupabaseConfig.isConfigured()) return false
        val row = session.toJson().apply {
            put("hdr_enabled", session.hdrPreset != null)
            if (session.hdrPreset != null) put("hdr_preset", session.hdrPreset)
        }
        val payload = JSONArray().put(row)
        val res = request(
            "${SupabaseConfig.restUrl(SupabaseConfig.TABLE_SESSIONS)}?on_conflict=session_id",
            "POST",
            payload.toString(),
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
        )
        return res.ok
    }

    fun fetchRecentSessions(limit: Int = 20): List<JSONObject> {
        if (!SupabaseConfig.isConfigured()) return emptyList()
        val url = "${SupabaseConfig.restUrl(SupabaseConfig.TABLE_SESSIONS)}" +
            "?select=session_id,started_at,ended_at,mode,avg_fps,p95_frame_ms,final_render_scale" +
            "&order=started_at.desc&limit=$limit"
        val res = request(url, "GET", null)
        if (!res.ok || res.body.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(res.body)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (t: Throwable) {
            Logger.w(TAG, "parse sessions failed: ${t.message}")
            emptyList()
        }
    }

    // ---------------------------------------------------------------- telemetry

    /** Bulk insert of numeric samples; returns true only when the server acknowledged the whole batch. */
    fun pushTelemetry(samples: List<String>): Boolean {
        if (!SupabaseConfig.isConfigured() || samples.isEmpty()) return false
        val payload = JSONArray()
        samples.forEach { raw ->
            try {
                payload.put(JSONObject(raw))
            } catch (t: Throwable) {
                Logger.w(TAG, "skip malformed sample")
            }
        }
        if (payload.length() == 0) return true
        val res = request(
            SupabaseConfig.restUrl(SupabaseConfig.TABLE_TELEMETRY),
            "POST",
            payload.toString(),
            mapOf("Prefer" to "return=minimal")
        )
        return res.ok
    }

    // ---------------------------------------------------------------- config

    /** Reads the remote quality preset for this device class. Returns null when offline/unavailable. */
    fun fetchConfig(socTier: String): JSONObject? {
        if (!SupabaseConfig.isConfigured()) return null
        val url = "${SupabaseConfig.restUrl(SupabaseConfig.TABLE_CONFIG)}" +
            "?soc_tier=eq.$socTier&active=is.true&select=version,payload&limit=1"
        val res = request(url, "GET", null)
        if (!res.ok || res.body.isBlank()) return null
        return try {
            val arr = JSONArray(res.body)
            if (arr.length() == 0) null else arr.getJSONObject(0).optJSONObject("payload")
        } catch (t: Throwable) {
            Logger.w(TAG, "parse config failed: ${t.message}")
            null
        }
    }

    /** Calls the `dlss-config` edge function (server-side tuning, keeps the table private in future). */
    fun fetchEdgeConfig(socTier: String, deviceId: String): JSONObject? {
        if (!SupabaseConfig.isConfigured()) return null
        val body = JSONObject().put("soc_tier", socTier).put("device_id", deviceId).toString()
        val res = request(SupabaseConfig.functionUrl(SupabaseConfig.EDGE_FUNCTION_CONFIG), "POST", body)
        if (!res.ok || res.body.isBlank()) return null
        return try {
            JSONObject(res.body).optJSONObject("config")
        } catch (t: Throwable) {
            null
        }
    }

    fun ping(): Boolean {
        if (!SupabaseConfig.isConfigured()) return false
        val res = request("${SupabaseConfig.restUrl(SupabaseConfig.TABLE_CONFIG)}?select=soc_tier&limit=1", "GET", null)
        return res.ok
    }
}

private fun OutputStream.writeBytesQuietly(bytes: ByteArray) {
    try {
        write(bytes)
    } catch (t: Throwable) {
        Logger.w("Supabase", "write failed: ${t.message}")
    }
}
