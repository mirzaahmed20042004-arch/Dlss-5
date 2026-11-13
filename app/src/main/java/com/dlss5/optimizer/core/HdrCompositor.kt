package com.dlss5.optimizer.core

import android.graphics.Bitmap
import android.opengl.GLES20
import com.dlss5.optimizer.util.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-time HDR / colour-grade compositor.
 *
 * Emits a **per-pixel alpha correction layer** rather than a replacement frame: for each pixel it solves
 * `src` such that `src·α + game·(1−α) = graded`. MediaProjection also captures our own overlay, so an
 * opaque graded copy would compound into a feedback tunnel; with alpha α the loop gain is `1−α` and
 * successive frames converge on the requested grade. The game keeps rendering, keeps its latency and
 * keeps every touch.
 *
 * Pass chain (all GPU, off-screen, own thread):
 *   bright-pass → separable blur ×2 (bloom) → HDR composite (tone-map, WB, contrast, shoulder, shadow
 *   lift, saturation, bloom, unsharp) → correction solve.
 *
 * Robustness (this is what fixes "This GPU blocked the HDR shader"):
 *  * shader ladder `hdr_full → hdr_lite → hdr_minimal`; bloom passes are optional,
 *  * the render surface is probed and shrunk to what the driver actually supports,
 *  * a failed tier is remembered and never retried, so a session cannot stall on compile attempts,
 *  * every entry point returns null/false instead of throwing.
 */
class HdrCompositor {

    companion object {
        private const val TAG = "HdrGL"
        private const val BLOOM_DIVISOR = 4
        private const val MIN_TARGET = 256
    }

    private val egl = EglCore()
    private val programs = ProgramCache()
    private val staging = GpuTextures.StagingPool()

    private var compositeProgram = 0
    private var brightProgram = 0
    private var blurProgram = 0
    private var compositeTierId = "none"
    private var bloomEnabled = false

    private var srcTex = 0
    private var bloomTexA = 0
    private var bloomTexB = 0
    private var outTex = 0
    private var fbo = 0

    private var width = 0
    private var height = 0
    private var bloomWidth = 0
    private var bloomHeight = 0

    private val healthy = AtomicBoolean(false)
    private var initialized = false
    private var frames = 0L
    private var lastCostMs = 0L

    private var preset: HdrPreset = HdrPreset.EXTREME_HDR
    private var blend = 1f
    private var enabled = true
    private var correctionAlpha = 0.34f

    private var readback: java.nio.ByteBuffer? = null

    fun isHealthy(): Boolean = healthy.get() && initialized
    fun frameCount(): Long = frames
    fun lastFrameCostMs(): Long = lastCostMs
    fun currentPreset(): HdrPreset = preset
    fun tier(): String = compositeTierId
    fun hasBloom(): Boolean = bloomEnabled && brightProgram != 0 && blurProgram != 0
    fun workingSize(): String = "${width}x$height"

    fun setPreset(newPreset: HdrPreset) {
        preset = newPreset.sanitized()
    }

    fun setBlend(value: Float) {
        blend = value.coerceIn(0f, 1f)
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun setCorrectionAlpha(alpha: Float) {
        correctionAlpha = alpha.coerceIn(0.12f, 0.6f)
    }

    fun correctionAlpha(): Float = correctionAlpha

    fun init(width: Int, height: Int, initialPreset: HdrPreset, initialBlend: Float): Boolean {
        if (initialized) release()
        return try {
            preset = initialPreset.sanitized()
            blend = initialBlend.coerceIn(0f, 1f)
            if (!egl.create()) {
                Logger.e(TAG, "EGL unavailable for HDR")
                return false
            }
            val maxTex = GpuTextures.maxTextureSize()
            var w = width.coerceAtMost(maxTex)
            var h = height.coerceAtMost(maxTex)
            var guard = 0
            while (guard < 6 && !GpuTextures.supportsRenderTarget(w, h, maxTex)) {
                w = (w * 3) / 4
                h = (h * 3) / 4
                guard++
            }
            if (w < MIN_TARGET || h < MIN_TARGET) {
                Logger.e(TAG, "no renderable HDR surface (${w}x$h)")
                egl.release()
                return false
            }
            this.width = w and 0xFFFFFFFE.toInt()
            this.height = h and 0xFFFFFFFE.toInt()
            bloomWidth = (this.width / BLOOM_DIVISOR).coerceAtLeast(2)
            bloomHeight = (this.height / BLOOM_DIVISOR).coerceAtLeast(2)

            compileTiers()
            createTargets()
            initialized = true
            healthy.set(true)
            Logger.i(
                TAG,
                "HDR ready ${this.width}x${this.height} tier=$compositeTierId bloom=$bloomEnabled preset=${preset.id}"
            )
            true
        } catch (t: Throwable) {
            Logger.e(TAG, "init failed: ${t.message}", t)
            release()
            false
        }
    }

    private fun compileTiers() {
        val compiled = programs.compileFirstUsable(ShaderLibrary.hdrTiers())
            ?: throw IllegalStateException("no HDR shader tier compiled")
        compositeProgram = compiled.program
        compositeTierId = compiled.tier.id

        if (compiled.tier.id == ShaderLibrary.HDR_FULL.id) {
            val bright = programs.compileFirstUsable(listOf(ShaderLibrary.BRIGHT))
            val blur = programs.compileFirstUsable(listOf(ShaderLibrary.BLUR))
            brightProgram = bright?.program ?: 0
            blurProgram = blur?.program ?: 0
            bloomEnabled = brightProgram != 0 && blurProgram != 0
            if (!bloomEnabled) Logger.w(TAG, "bloom passes unavailable, HDR continues without bloom")
        } else {
            brightProgram = 0
            blurProgram = 0
            bloomEnabled = false
        }
    }

    private fun createTargets() {
        srcTex = GpuTextures.createTexture()
        outTex = GpuTextures.createTexture()
        GpuTextures.allocate(srcTex, width, height)
        GpuTextures.allocate(outTex, width, height)
        if (bloomEnabled) {
            bloomTexA = GpuTextures.createTexture()
            bloomTexB = GpuTextures.createTexture()
            GpuTextures.allocate(bloomTexA, bloomWidth, bloomHeight)
            GpuTextures.allocate(bloomTexB, bloomWidth, bloomHeight)
        }
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fbo = fbos[0]
    }

    fun process(input: Bitmap): Bitmap? {
        if (!healthy.get() || !initialized || !enabled) return null
        if (input.width <= 0 || input.height <= 0) return null
        val started = System.nanoTime()
        return try {
            if (!egl.makeCurrent()) {
                healthy.set(false)
                Logger.e(TAG, "HDR context lost")
                return null
            }
            val srcW = input.width
            val srcH = input.height
            GpuTextures.upload(srcTex, input)

            if (hasBloom()) {
                runBloom(srcW, srcH)
            }

            drawTo(outTex, width, height) {
                GLES20.glUseProgram(compositeProgram)
                GpuTextures.bind(0, srcTex)
                programs.int(compositeProgram, "uTex", 0)
                if (hasBloom()) {
                    GpuTextures.bind(1, bloomTexA)
                    programs.int(compositeProgram, "uBloom", 1)
                }
                programs.vec2(compositeProgram, "uTexel", 1f / srcW, 1f / srcH)
                programs.float(compositeProgram, "uExposure", preset.exposure)
                programs.float(compositeProgram, "uContrast", preset.contrast)
                programs.float(compositeProgram, "uSaturation", preset.saturation)
                programs.float(compositeProgram, "uTemperature", preset.temperature)
                programs.float(compositeProgram, "uShadowLift", preset.shadowLift)
                programs.float(compositeProgram, "uRolloff", preset.highlightRolloff)
                programs.float(compositeProgram, "uBloomStrength", if (hasBloom()) preset.bloom else 0f)
                programs.float(compositeProgram, "uSharpen", preset.sharpen)
                programs.float(compositeProgram, "uBlend", blend)
                programs.float(compositeProgram, "uAlpha", correctionAlpha)
                programs.draw(compositeProgram)
            }

            val result = readBack()
            frames++
            lastCostMs = (System.nanoTime() - started) / 1_000_000L
            programs.checkGl("composite")
            result
        } catch (t: Throwable) {
            Logger.e(TAG, "process failed: ${t.message}", t)
            healthy.set(false)
            null
        }
    }

    private fun runBloom(srcW: Int, srcH: Int) {
        // bright pass → quarter res
        drawTo(bloomTexA, bloomWidth, bloomHeight) {
            GLES20.glUseProgram(brightProgram)
            GpuTextures.bind(0, srcTex)
            programs.int(brightProgram, "uTex", 0)
            programs.vec2(brightProgram, "uTexel", 1f / srcW, 1f / srcH)
            programs.float(brightProgram, "uThreshold", preset.bloomThreshold)
            programs.draw(brightProgram)
        }
        // horizontal
        drawTo(bloomTexB, bloomWidth, bloomHeight) {
            GLES20.glUseProgram(blurProgram)
            GpuTextures.bind(0, bloomTexA)
            programs.int(blurProgram, "uTex", 0)
            programs.vec2(blurProgram, "uTexel", 1f / bloomWidth, 1f / bloomHeight)
            programs.vec2(blurProgram, "uDir", 1f, 0f)
            programs.draw(blurProgram)
        }
        // vertical
        drawTo(bloomTexA, bloomWidth, bloomHeight) {
            GLES20.glUseProgram(blurProgram)
            GpuTextures.bind(0, bloomTexB)
            programs.int(blurProgram, "uTex", 0)
            programs.vec2(blurProgram, "uTexel", 1f / bloomWidth, 1f / bloomHeight)
            programs.vec2(blurProgram, "uDir", 0f, 1f)
            programs.draw(blurProgram)
        }
    }

    private fun readBack(): Bitmap? {
        val result = staging.acquire(width, height) ?: return null
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, outTex, 0
        )
        val needed = width * height * 4
        val buffer = readback?.takeIf { it.capacity() >= needed }
            ?: java.nio.ByteBuffer.allocateDirect(needed).order(java.nio.ByteOrder.nativeOrder()).also {
                readback = it
            }
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        buffer.position(0)
        result.copyPixelsFromBuffer(buffer)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return result
    }

    private fun drawTo(targetTex: Int, w: Int, h: Int, block: () -> Unit) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, targetTex, 0
        )
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        block()
    }

    fun release() {
        healthy.set(false)
        initialized = false
        try {
            if (egl.isValid()) {
                GpuTextures.delete(srcTex)
                GpuTextures.delete(outTex)
                GpuTextures.delete(bloomTexA)
                GpuTextures.delete(bloomTexB)
                if (fbo != 0) {
                    val ids = intArrayOf(fbo)
                    GLES20.glDeleteFramebuffers(1, ids, 0)
                }
                programs.delete(compositeProgram)
                programs.delete(brightProgram)
                programs.delete(blurProgram)
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "release: ${t.message}")
        } finally {
            egl.release()
            staging.release()
            compositeProgram = 0
            brightProgram = 0
            blurProgram = 0
            srcTex = 0
            outTex = 0
            bloomTexA = 0
            bloomTexB = 0
            fbo = 0
            width = 0
            height = 0
            readback = null
        }
    }
}
