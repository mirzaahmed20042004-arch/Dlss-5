package com.dlss5.optimizer.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Filter math, kept free of Android types so it is verifiable with plain JVM unit tests.
 *
 * Weights are normalised on construction. `offsets` are the fractional sample positions you must fetch
 * for a given sub-pixel phase — exactly the contract a GLES `texture2D` tap needs.
 */
object FilterKernels {

    /** Catmull-Rom bicubic: cheap, sharp, no ringing beyond ±0.05. Used for the overlay path. */
    fun bicubicWeights(t: Float): FloatArray {
        val x = t.coerceIn(0f, 1f)
        val x2 = x * x
        val x3 = x2 * x
        val w0 = -0.5f * x3 + x2 - 0.5f * x
        val w1 = 1.5f * x3 - 2.5f * x2 + 1f
        val w2 = -1.5f * x3 + 2.0f * x2 + 0.5f * x
        val w3 = 0.5f * x3 - 0.5f * x2
        return normalize(floatArrayOf(w0, w1, w2, w3))
    }

    /**
     * Lanczos-3 kernel evaluated at integer taps -3..3 with fractional offset [t] ∈ [0,1).
     * Only used by the capture path where quality outweighs the extra taps.
     */
    fun lanczos3Weights(t: Float): FloatArray {
        val a = 3
        val frac = t.coerceIn(0f, 1f)
        val out = FloatArray(2 * a)
        for (i in 0 until 2 * a) {
            // tap distances relative to the sample point
            val distance = (i - a + 1) - frac
            out[i] = lanczos(distance.toFloat(), a)
        }
        return normalize(out)
    }

    fun lanczos(x: Float, a: Int = 3): Float {
        if (x == 0f) return 1f
        val ax = abs(x)
        if (ax >= a) return 0f
        val px = Math.PI.toFloat() * x
        return (a * sin(px) * sin(px / a)) / (px * px)
    }

    /** 5-tap unsharp mask weights for [-2..2]: centre gain vs. ring blur. */
    fun unsharpWeights(strength: Float): FloatArray {
        val s = strength.coerceIn(0f, 1f)
        val center = 1f + 4f * s
        val neighbor = -s
        return floatArrayOf(neighbor, neighbor, center, neighbor, neighbor)
    }

    /** Separable horizontal/vertical Gaussian (or box approximation) for the temporal denoise pass. */
    fun gaussianWeights(radius: Int, sigma: Float = 1.0f): FloatArray {
        val r = radius.coerceIn(1, 8)
        val size = 2 * r + 1
        val out = FloatArray(size)
        var sum = 0f
        for (i in 0 until size) {
            val x = (i - r).toFloat()
            val v = kotlin.math.exp(-(x * x) / (2f * sigma * sigma))
            out[i] = v
            sum += v
        }
        if (sum <= 0f) return FloatArray(size) { 1f / size }
        for (i in out.indices) out[i] /= sum
        return out
    }

    /**
     * Temporal blend factor. When motion (frame-interval jitter) is high, trust the previous frame
     * less so we don't smear; when the scene is calm, blend more aggressively to smooth micro-stutter.
     */
    fun temporalAlpha(jitterMs: Double, targetMs: Double): Float {
        if (!jitterMs.isFinite() || targetMs <= 0.0) return 0.15f
        val normalized = (jitterMs / targetMs).coerceIn(0.0, 1.0)
        // calm (0) → 0.35 blend, jittery (1) → 0.10 blend
        return (0.35 - 0.25 * normalized).toFloat()
    }

    /**
     * Budget gate: on entry-tier GPUs we skip Lanczos entirely. Returns true when the kernel is affordable.
     */
    fun canAffordLanczos(tier: SocTier, outputWidth: Int): Boolean {
        if (tier == SocTier.ENTRY) return false
        if (tier == SocTier.MID && outputWidth > 1600) return false
        return true
    }

    private fun normalize(weights: FloatArray): FloatArray {
        var sum = 0f
        for (w in weights) sum += w
        if (sum == 0f || !sum.isFinite()) {
            val flat = FloatArray(weights.size) { 1f / weights.size }
            return flat
        }
        for (i in weights.indices) weights[i] /= sum
        return weights
    }

    /**
     * Reference CPU implementation of the separable upscale. Used by unit tests and as the software
     * fallback when a device refuses to compile the GL shader (older Adreno drivers).
     */
    fun upscaleBicubic(
        src: FloatArray,
        srcWidth: Int,
        srcHeight: Int,
        dst: FloatArray,
        dstWidth: Int,
        dstHeight: Int
    ) {
        if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) return
        val scaleX = srcWidth.toFloat() / dstWidth
        val scaleY = srcHeight.toFloat() / dstHeight
        for (y in 0 until dstHeight) {
            val sy = (y + 0.5f) * scaleY - 0.5f
            val y0 = kotlin.math.floor(sy).toInt()
            val ty = (sy - y0).coerceIn(0f, 1f)
            val wy = bicubicWeights(ty)
            for (x in 0 until dstWidth) {
                val sx = (x + 0.5f) * scaleX - 0.5f
                val x0 = kotlin.math.floor(sx).toInt()
                val tx = (sx - x0).coerceIn(0f, 1f)
                val wx = bicubicWeights(tx)
                var acc = 0f
                for (j in 0 until 4) {
                    val yy = (y0 - 1 + j).coerceIn(0, srcHeight - 1)
                    var rowAcc = 0f
                    for (i in 0 until 4) {
                        val xx = (x0 - 1 + i).coerceIn(0, srcWidth - 1)
                        rowAcc += src[yy * srcWidth + xx] * wx[i]
                    }
                    acc += rowAcc * wy[j]
                }
                dst[y * dstWidth + x] = acc.coerceIn(0f, 1f)
            }
        }
    }

    /** Reference CPU unsharp mask, separable-friendly single pass over a 5-tap cross. */
    fun applyUnsharp(
        src: FloatArray,
        width: Int,
        height: Int,
        dst: FloatArray,
        strength: Float
    ) {
        if (width <= 0 || height <= 0) return
        val s = strength.coerceIn(0f, 1f)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = src[y * width + x]
                val l = src[y * width + (x - 1).coerceAtLeast(0)]
                val r = src[y * width + (x + 1).coerceAtMost(width - 1)]
                val u = src[(y - 1).coerceAtLeast(0) * width + x]
                val d = src[(y + 1).coerceAtMost(height - 1) * width + x]
                val sharpened = c + s * (4f * c - l - r - u - d)
                dst[y * width + x] = sharpened.coerceIn(0f, 1f)
            }
        }
    }
}
