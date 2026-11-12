package com.dlss5.optimizer.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Best-effort haptics: silently no-ops on devices with no vibrator or with haptics disabled. */
object Haptics {

    fun confirm(context: Context) = buzz(context, 18L, 90)

    fun reject(context: Context) = doubleBuzz(context)

    private fun buzz(context: Context, ms: Long, amplitude: Int) {
        try {
            val vib = vibrator(context) ?: return
            if (!vib.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(ms, amplitude.coerceIn(1, 255)))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(ms)
            }
        } catch (t: Throwable) {
            Logger.w("Haptics", "vibrate failed: ${t.message}")
        }
    }

    private fun doubleBuzz(context: Context) {
        try {
            val vib = vibrator(context) ?: return
            if (!vib.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 24, 70, 24), -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(longArrayOf(0, 24, 70, 24), -1)
            }
        } catch (t: Throwable) {
            Logger.w("Haptics", "double buzz failed: ${t.message}")
        }
    }

    private fun vibrator(context: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (t: Throwable) {
        null
    }
}
