package com.dlss5.optimizer.core

import android.graphics.Bitmap
import com.dlss5.optimizer.util.Logger

/**
 * CPU fallback for devices where **no** GLSL tier compiles (very old drivers, emulator software GL,
 * some vendor ROMs that block EGL pbuffers).
 *
 * Rather than telling the user "your GPU blocked the HDR shader" and giving up, we grade on the CPU —
 * a real, visible colour change — and simply say it is running in compatibility mode. It is slower than
 * the GPU path, so it runs at a reduced cadence and a downscaled working size, but the feature works.
 *
 * Memory-safe by construction: one destination bitmap, reused; optional subsampling for weak CPUs.
 */
class SoftwareGrader {

    companion object {
        private const val TAG = "SoftwareGrade"
    }

    @Volatile
    private var enabled = false
    private var preset: HdrPreset = HdrPreset.EXTREME_HDR
    private var correctionAlpha = 0.34f
    private var blend = 1f

    private var reusable: Bitmap? = null
    private var srcPixels = IntArray(0)
    private var dstPixels = IntArray(0)
    private var frames = 0L
    private var lastCostMs = 0L

    /** Step size while iterating: 1 = every pixel, 2 = every 2nd pixel in x and y (4× faster). */
    private var step = 1

    fun configure(preset: HdrPreset, blend: Float, alpha: Float, step: Int): SoftwareGrader {
        this.preset = preset.sanitized()
        this.blend = blend.coerceIn(0f, 1f)
        this.correctionAlpha = alpha.coerceIn(0.12f, 0.6f)
        this.step = step.coerceIn(1, 3)
        enabled = true
        return this
    }

    fun isEnabled(): Boolean = enabled
    fun frameCount(): Long = frames
    fun lastCostMs(): Long = lastCostMs
    fun currentPreset(): HdrPreset = preset

    fun setPreset(newPreset: HdrPreset) {
        preset = newPreset.sanitized()
    }

    fun setBlend(value: Float) {
        blend = value.coerceIn(0f, 1f)
    }

    /** The CPU grader applies the preset's contrast/saturation; sharpness is GPU-only, so store it. */
    fun setSharpness(value: Float) {
        sharpness = value.coerceIn(0f, 1f)
    }

    private var sharpness = 0.38f

    /**
     * Produces a correction layer for [input].
     *
     * @return an ARGB bitmap with per-pixel alpha (the overlay draws it src-over the game), or null on OOM.
     */
    fun process(input: Bitmap): Bitmap? {
        if (!enabled) return null
        if (input.width <= 0 || input.height <= 0) return null
        val started = System.nanoTime()
        return try {
            val w = input.width
            val h = input.height
            val count = w * h
            if (srcPixels.size != count) srcPixels = IntArray(count)
            if (dstPixels.size != count) dstPixels = IntArray(count)

            val target = reusable?.takeIf { !it.isRecycled && it.width == w && it.height == h }
                ?: try {
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        reusable?.let { old -> if (!old.isRecycled) old.recycle() }
                        reusable = it
                    }
                } catch (t: Throwable) {
                    Logger.e(TAG, "cannot allocate ${w}x$h: ${t.message}")
                    return null
                }

            input.getPixels(srcPixels, 0, w, 0, 0, w, h)

            val alpha = correctionAlpha
            val strongBlend = blend
            var y = 0
            while (y < h) {
                var x = 0
                val rowOffset = y * w
                while (x < w) {
                    val pixel = srcPixels[rowOffset + x]
                    val r = ((pixel shr 16) and 0xFF) / 255f
                    val g = ((pixel shr 8) and 0xFF) / 255f
                    val b = (pixel and 0xFF) / 255f

                    val graded = ColorMath.gradeChannel(r, g, b, preset)
                    val tr = r + (graded[0] - r) * strongBlend
                    val tg = g + (graded[1] - g) * strongBlend
                    val tb = b + (graded[2] - b) * strongBlend

                    val corr = ColorMath.packCorrection(
                        ColorMath.correctionChannel(tr, r, alpha),
                        ColorMath.correctionChannel(tg, g, alpha),
                        ColorMath.correctionChannel(tb, b, alpha),
                        alpha
                    )

                    if (step == 1) {
                        dstPixels[rowOffset + x] = corr
                    } else {
                        // Fill the square block so the layer has no gaps.
                        var dy = 0
                        while (dy < step && y + dy < h) {
                            var dx = 0
                            val off = (y + dy) * w
                            while (dx < step && x + dx < w) {
                                dstPixels[off + x + dx] = corr
                                dx++
                            }
                            dy++
                        }
                    }
                    x += step
                }
                y += step
            }

            target.setPixels(dstPixels, 0, w, 0, 0, w, h)
            frames++
            lastCostMs = (System.nanoTime() - started) / 1_000_000L
            target
        } catch (t: Throwable) {
            Logger.e(TAG, "process failed: ${t.message}", t)
            enabled = false
            null
        }
    }

    fun release() {
        enabled = false
        try {
            reusable?.let { if (!it.isRecycled) it.recycle() }
        } catch (t: Throwable) {
            Logger.w(TAG, "release: ${t.message}")
        } finally {
            reusable = null
            srcPixels = IntArray(0)
            dstPixels = IntArray(0)
        }
    }
}
