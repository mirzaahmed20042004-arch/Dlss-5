package com.dlss5.optimizer.core

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.Display
import android.view.WindowManager
import com.dlss5.optimizer.util.DeviceGuards
import com.dlss5.optimizer.util.Logger

/**
 * Everything we are *allowed* to do about sustained performance, using only public APIs.
 *
 * What is real here:
 *  * `WindowManager.LayoutParams.preferredRefreshRate` / `preferredDisplayModeId` — pins the display to a
 *    rate the GPU can actually hold, which removes the largest source of frame-time variance on 90/120 Hz
 *    panels (the system otherwise switches modes mid-fight).
 *  * `PowerManager` thermal status → we shrink the render scale *before* the OS throttles us.
 *  * `Window.setSustainedPerformanceMode` on the activity (used for our own surface) reduces DVFS
 *    fluctuation.
 *
 * What is *not* possible without root (and is therefore not pretended): writing to
 * `/sys/class/devfreq` governors, cpuset tweaks, GPU clock overrides.
 */
class PerfTuner(private val context: Context) {

    companion object {
        private const val TAG = "PerfTuner"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    data class Capabilities(
        val supportedRefreshRates: List<Float>,
        val currentRefreshRate: Float,
        val thermalStatus: Int,
        val powerSaver: Boolean,
        val sustainedModeAvailable: Boolean
    )

    fun capabilities(): Capabilities {
        val rates = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.supportedModes?.map { it.refreshRate }?.distinct()?.sorted() ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.supportedModes.map { it.refreshRate }.distinct().sorted()
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "refresh rate probe failed: ${t.message}")
            emptyList()
        }
        return Capabilities(
            supportedRefreshRates = rates,
            currentRefreshRate = DeviceGuards.refreshRateHz(context),
            thermalStatus = thermalStatus(),
            powerSaver = DeviceGuards.isBatterySaverOn(context),
            sustainedModeAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        )
    }

    fun thermalStatus(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus ?: 0
        } else {
            0
        }
    } catch (t: Throwable) {
        0
    }

    /**
     * Picks the highest refresh rate that is *safely* sustainable for this device class.
     * Entry-tier phones do better with a locked 60 Hz than with an oscillating 90/60.
     */
    fun recommendedRefreshRate(tier: SocTier, rates: List<Float>): Float {
        if (rates.isEmpty()) return 60f
        val ceiling = when (tier) {
            SocTier.FLAGSHIP -> 120f
            SocTier.HIGH -> 90f
            SocTier.MID -> 60f
            SocTier.ENTRY -> 60f
        }
        return rates.filter { it <= ceiling + 0.5f }.maxOrNull() ?: rates.minOrNull() ?: 60f
    }

    /**
     * Applies the preferred refresh rate / display mode to a window's layout params.
     * Returns the params so the caller can hand them to `updateViewLayout`.
     */
    fun applyPreferredRate(
        params: WindowManager.LayoutParams,
        targetHz: Float,
        @Suppress("UNUSED_PARAMETER") tier: SocTier
    ): Boolean = try {
        params.preferredRefreshRate = targetHz
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val modes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.supportedModes?.toList()
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.supportedModes.toList()
            }
            val best = modes?.filter { it.refreshRate <= targetHz + 0.5f }?.maxByOrNull { it.refreshRate }
            if (best != null) params.preferredDisplayModeId = best.modeId
        }
        true
    } catch (t: Throwable) {
        Logger.w(TAG, "applyPreferredRate failed: ${t.message}")
        false
    }

    /**
     * Thermal-aware scale ceiling. Called from the engine each time the thermal listener fires, and
     * on a slow timer so a device that never reports thermal events still gets protection.
     */
    fun scaleCeilingForThermal(status: Int): Float = when {
        status >= 6 -> 0.66f
        status >= 5 -> 0.70f
        status >= 4 -> 0.76f
        status >= 3 -> 0.82f
        status >= 2 -> 0.90f
        else -> 1.00f
    }

    /** Diagnostics line surfaced in the UI so the tuning is visible, not mysterious. */
    fun describe(tier: SocTier): String {
        val caps = capabilities()
        val target = recommendedRefreshRate(tier, caps.supportedRefreshRates)
        val thermal = when {
            caps.thermalStatus >= 5 -> "CRITICAL"
            caps.thermalStatus >= 4 -> "SEVERE"
            caps.thermalStatus >= 3 -> "MODERATE"
            caps.thermalStatus >= 2 -> "LIGHT"
            else -> "NONE"
        }
        return "display ${"%.0f".format(target)}Hz (of ${caps.supportedRefreshRates.size} modes) · " +
            "thermal $thermal · battery saver ${if (caps.powerSaver) "ON" else "OFF"}"
    }

    /** Only used by the activity's own window; kept here so all perf policy lives in one place. */
    @Suppress("unused")
    fun displaySummary(): String {
        val d: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display else null
        return d?.name ?: "display"
    }
}
