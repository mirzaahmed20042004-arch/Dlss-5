package com.dlss5.optimizer.network

import android.content.Context
import com.dlss5.optimizer.core.HdrPreset
import com.dlss5.optimizer.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Pulls the tuned HDR preset for this device class from the `dlss-config` edge function and applies it on
 * top of the locally selected preset. Fully offline-tolerant: any failure returns null and the app keeps
 * using the local preset, so this can never block or break the UI.
 */
object ConfigRepository {

    private const val TAG = "ConfigRepo"

    data class Tuned(val preset: HdrPreset, val source: String, val version: Int)

    suspend fun fetchTunedHdr(context: Context, socTier: String, base: HdrPreset): Tuned? =
        withContext(Dispatchers.IO) {
            try {
                if (!SupabaseConfig.isConfigured()) return@withContext null
                val client = SupabaseClient(com.dlss5.optimizer.util.Prefs.get(context))
                val payload: JSONObject? = client.fetchConfig(socTier)
                    ?: client.fetchEdgeConfig(socTier, com.dlss5.optimizer.util.Prefs.get(context).deviceId)
                if (payload == null) return@withContext null
                val hdr = payload.optJSONObject("hdr") ?: payload
                val tuned = HdrPreset.withRemote(base, hdr)
                Tuned(tuned, if (payload.has("hdr")) "cloud" else "cloud", payload.optInt("version", 1))
            } catch (t: Throwable) {
                Logger.w(TAG, "fetchTunedHdr failed: ${t.message}")
                null
            }
        }
}
