package com.dlss5.optimizer.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Adaptive dynamic-resolution controller.
 *
 * Model: PI controller on frame-interval error, deliberately conservative.
 *
 *   err   = observed - target                      (ms; > 0 means we are late → shrink scale)
 *   scale += -Kp * err - Ki * integral(err)
 *
 * Guard rails that keep it stable on real phones:
 *  * slew limit (max 0.02 scale units per update) prevents visible resolution pumping,
 *  * dead-band of ±1.5 ms ignores noise,
 *  * hold window (500 ms) after each change stops oscillation,
 *  * thermal clamp lowers `maxScale` as the device heats up,
 *  * hard floor `minScale` protects against unplayable mush.
 *
 * This class is pure Kotlin (no Android imports) so it is unit-testable on the JVM.
 */
class FrameGovernor(
    targetFps: Int = 60,
    minScale: Float = 0.60f,
    maxScale: Float = 1.00f,
    initialScale: Float = 0.75f,
    private val kp: Double = 0.020,
    private val ki: Double = 0.004,
    private val deadBandMs: Double = 1.5,
    private val slewPerUpdate: Float = 0.02f,
    private val holdMs: Long = 500L
) {

    private var targetMs: Double = 1000.0 / targetFps.coerceIn(30, 240)

    var minScale: Float = minScale.coerceIn(0.40f, 1.0f)
        private set
    var maxScale: Float = maxScale.coerceIn(this.minScale, 1.0f)
        private set

    private var thermalClamp: Float = 1.0f
    private var scale: Float = initialScale.coerceIn(this.minScale, this.maxScale)
    private var integral: Double = 0.0
    private var lastChangeAtMs: Long = Long.MIN_VALUE / 2
    private var lastOutputScale: Float = scale
    private var updates: Long = 0
    private var shrinks: Long = 0
    private var grows: Long = 0

    fun currentScale(): Float = scale
    fun targetFrameMs(): Double = targetMs
    fun totalUpdates(): Long = updates
    fun shrinkCount(): Long = shrinks
    fun growCount(): Long = grows

    fun setTargetFps(fps: Int) {
        targetMs = 1000.0 / fps.coerceIn(30, 240)
        integral = 0.0
    }

    fun setBounds(min: Float, max: Float) {
        minScale = min.coerceIn(0.40f, 1.0f)
        maxScale = max.coerceIn(minScale, 1.0f)
        scale = scale.coerceIn(minScale, maxScale)
    }

    /**
     * Thermal status uses the values from `PowerManager.THERMAL_STATUS_*` (0..6).
     * We shave the ceiling (not the current value) so cooling restores quality smoothly.
     */
    fun applyThermalStatus(status: Int) {
        thermalClamp = when {
            status >= 6 -> 0.80f  // SHUTDOWN
            status >= 5 -> 0.82f  // CRITICAL
            status >= 4 -> 0.86f  // SEVERE
            status >= 3 -> 0.90f  // MODERATE
            status >= 2 -> 0.96f  // LIGHT
            else -> 1.00f
        }
    }

    fun effectiveMaxScale(): Float = max(minScale, minScale + (maxScale - minScale) * thermalClamp)
        .coerceIn(minScale, maxScale)
        .let { if (it > maxScale) maxScale else it }

    /**
     * Feeds one measurement. Returns the scale the renderer should use for the next frame.
     * Safe to call at any rate; it is pure arithmetic and never throws.
     */
    fun update(observedFrameMs: Double, nowMs: Long): Float {
        updates++
        if (!observedFrameMs.isFinite() || observedFrameMs <= 0.0) return scale

        val ceiling = maxScale * thermalClamp
        val err = observedFrameMs - targetMs

        if (abs(err) < deadBandMs) {
            integral *= 0.85  // bleed the integrator so we don't drift while idle
            return scale
        }
        if (nowMs - lastChangeAtMs < holdMs) {
            return scale
        }

        integral = (integral + err).coerceIn(-400.0, 400.0)
        val delta = -(kp * err + ki * integral)
        var next = (scale + delta.toFloat()).coerceIn(minScale, ceiling)
        // slew limit
        next = when {
            next > lastOutputScale + slewPerUpdate -> lastOutputScale + slewPerUpdate
            next < lastOutputScale - slewPerUpdate -> lastOutputScale - slewPerUpdate
            else -> next
        }.coerceIn(minScale, ceiling)

        if (abs(next - scale) > 0.0005f) {
            if (next < scale) shrinks++ else grows++
            val stabilized = (next * 100f).toInt() / 100f
            scale = stabilized
            lastOutputScale = stabilized
            lastChangeAtMs = nowMs
            // Integral anti-windup: clamp it once we sit on a rail.
            if (scale <= minScale + 0.001f || scale >= ceiling - 0.001f) {
                integral *= 0.5
            }
        }
        return scale
    }

    /** Called when the engine restarts or the user forces a preset. */
    fun reset(to: Float = 0.75f) {
        scale = to.coerceIn(minScale, maxScale)
        lastOutputScale = scale
        integral = 0.0
        lastChangeAtMs = Long.MIN_VALUE / 2
    }

    fun snapshot(): Snapshot = Snapshot(
        scale = scale,
        minScale = minScale,
        maxScale = maxScale,
        effectiveMax = maxScale * thermalClamp,
        targetFps = (1000.0 / targetMs).toInt(),
        updates = updates,
        shrinks = shrinks,
        grows = grows
    )

    data class Snapshot(
        val scale: Float,
        val minScale: Float,
        val maxScale: Float,
        val effectiveMax: Float,
        val targetFps: Int,
        val updates: Long,
        val shrinks: Long,
        val grows: Long
    )
}
