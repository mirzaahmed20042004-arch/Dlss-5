package com.dlss5.optimizer.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure-Kotlin colour math shared by the GPU shaders (as reference) and the software fallback.
 *
 * No Android imports → fully unit-testable, which matters because this is the code that runs when a
 * device rejects every shader. It must produce the same *look* as the GLSL, just slower.
 */
object ColorMath {

    private const val GAMMA = 2.2f

    /** sRGB-ish → linear. */
    fun toLinear(c: Float): Float = max(c, 0f).pow(GAMMA)

    /** Linear → sRGB-ish. */
    fun toDisplay(c: Float): Float = min(max(c, 0f), 1f).pow(1f / GAMMA)

    fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Grades one linear RGB triple with the same operations as the `hdr_full` shader, minus bloom/unsharp
     * (those are neighbourhood operations and in the software path they are applied separately).
     */
    fun gradeChannel(
        r: Float,
        g: Float,
        b: Float,
        preset: HdrPreset
    ): FloatArray {
        var lr = toLinear(r) * preset.exposure
        var lg = toLinear(g) * preset.exposure
        var lb = toLinear(b) * preset.exposure

        // white balance
        lr *= (1f + preset.temperature)
        lb *= (1f - preset.temperature)

        // Reinhard-style highlight shoulder above the knee (still linear light)
        val knee = 0.62f
        val k = max(preset.highlightRolloff, 0.001f) * 2f
        if (lr > knee) lr = knee + (lr - knee) / (1f + (lr - knee) * k)
        if (lg > knee) lg = knee + (lg - knee) / (1f + (lg - knee) * k)
        if (lb > knee) lb = knee + (lb - knee) / (1f + (lb - knee) * k)

        // ---- tone map to display space, THEN grade (contrast in linear light destroys shadows)
        var dr = toDisplay(lr)
        var dg = toDisplay(lg)
        var db = toDisplay(lb)

        dr = (dr - 0.5f) * preset.contrast + 0.5f
        dg = (dg - 0.5f) * preset.contrast + 0.5f
        db = (db - 0.5f) * preset.contrast + 0.5f

        val lum = luma(dr, dg, db)
        val lift = preset.shadowLift * (1f - smoothstep(0f, 0.45f, lum))
        dr += lift
        dg += lift
        db += lift

        val gray = luma(dr, dg, db)
        dr = gray + (dr - gray) * preset.saturation
        dg = gray + (dg - gray) * preset.saturation
        db = gray + (db - gray) * preset.saturation

        return floatArrayOf(
            dr.coerceIn(0f, 1f), dg.coerceIn(0f, 1f), db.coerceIn(0f, 1f)
        )
    }

    /**
     * Solves the src-over correction for one channel:
     * `target = src·α + original·(1−α)` → `src = (target − original·(1−α)) / α`.
     */
    fun correctionChannel(target: Float, original: Float, alpha: Float): Float {
        val a = alpha.coerceIn(0.05f, 0.95f)
        return ((target - original * (1f - a)) / a).coerceIn(0f, 1f)
    }

    /** Packs a correction pixel (premultiplied-free ARGB). */
    fun packCorrection(sr: Float, sg: Float, sb: Float, alpha: Float): Int {
        val a = (alpha * 255f).roundToInt().coerceIn(0, 255)
        val r = (sr * 255f).roundToInt().coerceIn(0, 255)
        val g = (sg * 255f).roundToInt().coerceIn(0, 255)
        val b = (sb * 255f).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun rgb(pixel: Int): Triple<Float, Float, Float> = Triple(
        ((pixel shr 16) and 0xFF) / 255f,
        ((pixel shr 8) and 0xFF) / 255f,
        (pixel and 0xFF) / 255f
    )
}
