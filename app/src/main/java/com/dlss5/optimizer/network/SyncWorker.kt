package com.dlss5.optimizer.network

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dlss5.optimizer.BuildConfig
import com.dlss5.optimizer.core.DeviceProfiler
import com.dlss5.optimizer.util.Logger
import com.dlss5.optimizer.util.Prefs
import com.dlss5.optimizer.util.Time
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Drains the local telemetry queue and pushes unsynced sessions to Supabase.
 *
 * Guarantees:
 *  * Only runs with a network (`NetworkType.CONNECTED`) and never on the main thread.
 *  * Exponential backoff 30 s → up to 8 h; the queue is durable so nothing is lost across reboots.
 *  * Total failure is silent — the app's local history is the source of truth for the UI.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs.get(applicationContext)
        if (!prefs.analyticsOptIn) {
            Logger.i(TAG, "analytics opt-out, skipping sync")
            return Result.success()
        }
        val client = SupabaseClient(prefs)
        val store = LocalStore.get(applicationContext)
        if (!SupabaseConfig.isConfigured()) {
            Logger.w(TAG, "backend not configured")
            return Result.success()
        }

        return try {
            val profile = DeviceProfiler.profile(applicationContext)
            client.upsertDevice(profile, prefs.deviceId, BuildConfig.VERSION_NAME)

            var pushedSessions = 0
            store.unsyncedSessions(10).forEach { session ->
                if (client.upsertSession(session)) {
                    store.markSessionSynced(session.sessionId)
                    pushedSessions++
                }
            }

            var pushedSamples = 0
            val batch = store.drainTelemetry(200)
            if (batch.isNotEmpty()) {
                if (client.pushTelemetry(batch)) {
                    store.ackTelemetry(batch.size)
                    pushedSamples = batch.size
                } else {
                    Logger.w(TAG, "telemetry push rejected, will retry")
                    return Result.retry()
                }
            }

            // Refresh remote tuning config for this SoC tier (best-effort).
            val config = client.fetchConfig(profile.tier.key)
                ?: client.fetchEdgeConfig(profile.tier.key, prefs.deviceId)
            if (config != null) {
                prefs.remoteConfigJson = config.toString()
            }

            prefs.lastSyncEpoch = Time.epochSeconds()
            Logger.i(TAG, "sync ok: sessions=$pushedSessions samples=$pushedSamples")
            Result.success()
        } catch (t: Throwable) {
            Logger.e(TAG, "sync failed", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val UNIQUE_NAME = "dlss5-telemetry-sync"

        /** Fire-and-forget sync with backoff; safe to call from anywhere, any number of times. */
        fun enqueue(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
            } catch (t: Throwable) {
                Logger.w(TAG, "enqueue failed: ${t.message}")
            }
        }

        /** Builds a telemetry row; returns null when the payload would be malformed. */
        fun sampleRow(
            sessionId: String,
            deviceId: String,
            fps: Double,
            frameMs: Double,
            renderScale: Float,
            thermal: Int,
            gpuCostMs: Double
        ): JSONObject? = try {
            JSONObject().apply {
                put("session_id", sessionId)
                put("device_id", deviceId)
                put("sampled_at", Time.isoNowUtc())
                put("fps", round2(fps))
                put("frame_ms", round2(frameMs))
                put("render_scale", round2(renderScale.toDouble()))
                put("thermal", thermal)
                put("gpu_cost_ms", round2(gpuCostMs))
            }
        } catch (t: Throwable) {
            null
        }

        private fun round2(v: Double): Double = if (v.isFinite()) Math.round(v * 100.0) / 100.0 else 0.0
    }
}
