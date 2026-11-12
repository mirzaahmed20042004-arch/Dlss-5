package com.dlss5.optimizer.util

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Single source of truth for persisted settings. All keys are `const val` so call sites cannot drift,
 * and [deviceId] is generated once and used as the ownership key for cloud telemetry rows.
 */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    companion object {
        private const val NAME = "dlss5_prefs_v1"

        const val KEY_DEVICE_ID = "device_id"
        const val KEY_SESSION_ID = "active_session_id"
        const val KEY_MODE = "engine_mode"
        const val KEY_SAFE_MODE = "safe_mode"
        const val KEY_BOOT_RESTORE = "boot_restore"
        const val KEY_TARGET_FPS = "target_fps"
        const val KEY_LAST_SCALE = "last_render_scale"
        const val KEY_TOTAL_SESSIONS = "total_sessions"
        const val KEY_LAST_SYNC = "last_sync_epoch"
        const val KEY_ENGINE_RUNNING = "engine_running"
        const val KEY_ANALYTICS_OPT_IN = "analytics_opt_in"
        const val KEY_GL_FALLBACK_NOTIFIED = "gl_fallback_notified"
        const val KEY_CONFIG_JSON = "remote_config_json"
        const val KEY_HDR_PRESET = "hdr_preset_id"
        const val KEY_HDR_ENABLED = "hdr_enabled"
        const val KEY_HDR_BLEND = "hdr_blend"
        const val KEY_HDR_LAST_MODE = "hdr_last_mode"

        /** Engine modes. Values are persisted, so never renumber — append only. */
        const val MODE_GOVERNOR = 0
        const val MODE_UPSCALE = 1
        const val MODE_TRUE_DLSS = 2
        const val MODE_HDR = 3

        /** Every mode that needs screen capture consent. */
        fun requiresCapture(mode: Int): Boolean =
            mode == MODE_UPSCALE || mode == MODE_TRUE_DLSS || mode == MODE_HDR

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context).also { instance = it }
            }
    }

    var deviceId: String
        get() {
            val existing = sp.getString(KEY_DEVICE_ID, null)
            if (existing != null && existing.isNotBlank()) return existing
            val fresh = UUID.randomUUID().toString()
            sp.edit().putString(KEY_DEVICE_ID, fresh).apply()
            return fresh
        }
        set(value) = sp.edit().putString(KEY_DEVICE_ID, value).apply()

    var mode: Int
        get() = sp.getInt(KEY_MODE, MODE_GOVERNOR)
        set(value) = sp.edit().putInt(KEY_MODE, value.coerceIn(0, MODE_HDR)).apply()

    var safeMode: Boolean
        get() = sp.getBoolean(KEY_SAFE_MODE, false)
        set(value) = sp.edit().putBoolean(KEY_SAFE_MODE, value).apply()

    var bootRestore: Boolean
        get() = sp.getBoolean(KEY_BOOT_RESTORE, false)
        set(value) = sp.edit().putBoolean(KEY_BOOT_RESTORE, value).apply()

    var targetFps: Int
        get() = sp.getInt(KEY_TARGET_FPS, 60).coerceIn(30, 120)
        set(value) = sp.edit().putInt(KEY_TARGET_FPS, value.coerceIn(30, 120)).apply()

    var lastRenderScale: Float
        get() = sp.getFloat(KEY_LAST_SCALE, 0.70f)
        set(value) = sp.edit().putFloat(KEY_LAST_SCALE, value).apply()

    var lastSyncEpoch: Long
        get() = sp.getLong(KEY_LAST_SYNC, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_SYNC, value).apply()

    var engineRunning: Boolean
        get() = sp.getBoolean(KEY_ENGINE_RUNNING, false)
        set(value) = sp.edit().putBoolean(KEY_ENGINE_RUNNING, value).apply()

    var analyticsOptIn: Boolean
        get() = sp.getBoolean(KEY_ANALYTICS_OPT_IN, true)
        set(value) = sp.edit().putBoolean(KEY_ANALYTICS_OPT_IN, value).apply()

    var remoteConfigJson: String?
        get() = sp.getString(KEY_CONFIG_JSON, null)
        set(value) = sp.edit().putString(KEY_CONFIG_JSON, value).apply()

    var sessionCount: Int
        get() = sp.getInt(KEY_TOTAL_SESSIONS, 0)
        set(value) = sp.edit().putInt(KEY_TOTAL_SESSIONS, value).apply()

    // ---------------------------------------------------------------- HDR compositor

    /** Id of the active colour preset, e.g. `extreme_hdr`. */
    var hdrPresetId: String
        get() = sp.getString(KEY_HDR_PRESET, "extreme_hdr") ?: "extreme_hdr"
        set(value) = sp.edit().putString(KEY_HDR_PRESET, value).apply()

    /** When false the compositor is bypassed even in HDR mode (pure upscale path). */
    var hdrEnabled: Boolean
        get() = sp.getBoolean(KEY_HDR_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_HDR_ENABLED, value).apply()

    /** 0 = raw game pixels, 1 = full graded image. Default 1.0 (user asked for the real effect). */
    var hdrBlend: Float
        get() = sp.getFloat(KEY_HDR_BLEND, 1.0f).coerceIn(0f, 1f)
        set(value) = sp.edit().putFloat(KEY_HDR_BLEND, value.coerceIn(0f, 1f)).apply()

    /** Rotates the anonymous identity and drops locally queued telemetry ownership. */
    fun rotateDeviceId(): String {
        val fresh = UUID.randomUUID().toString()
        sp.edit()
            .putString(KEY_DEVICE_ID, fresh)
            .remove(KEY_SESSION_ID)
            .apply()
        return fresh
    }
}
