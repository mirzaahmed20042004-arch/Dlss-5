package com.dlss5.optimizer.util

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager

/**
 * Reads public hazards that can make an overlay/foreground service misbehave, so we can pre-empt
 * rather than crash. Every probe is defensive: unknown values degrade to the safest assumption.
 */
object DeviceGuards {

    /** True when the OS will refuse to attach overlay windows from a background process. */
    @Suppress("UNUSED_PARAMETER")
    fun isOverlayBlockedByStrictMode(context: Context): Boolean = false // reserved; OEM hooks land here

    fun canDrawOverlaySafely(context: Context): Boolean = Permissions.canDrawOverlays(context)

    fun isBatterySaverOn(context: Context): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isPowerSaveMode == true
    } catch (t: Throwable) {
        false
    }

    /** Battery-optimisation exempt apps survive OEM kill-lists far more reliably. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    } catch (t: Throwable) {
        true
    }

    fun refreshRateHz(context: Context): Float = try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.refreshRate ?: 60f
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.refreshRate
        }
        if (rate.isFinite() && rate > 1f) rate else 60f
    } catch (t: Throwable) {
        60f
    }

    fun maxRefreshRateHz(context: Context): Float = try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.supportedModes?.map { it.refreshRate }
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.supportedModes.map { it.refreshRate }
        }
        rates?.maxOrNull()?.takeIf { it.isFinite() && it > 1f } ?: refreshRateHz(context)
    } catch (t: Throwable) {
        refreshRateHz(context)
    }

    /** System animation scale 0 means the user asked for reduced motion → we disable sweeps. */
    fun isReducedMotion(context: Context): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (t: Throwable) {
        false
    }

    fun applyImmersiveFlags(activity: Activity) {
        try {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = 0
        } catch (t: Throwable) {
            Logger.w("Guards", "immersive flags failed: ${t.message}")
        }
    }
}
