package com.dlss5.optimizer.core

import android.graphics.Bitmap
import android.opengl.GLES20
import com.dlss5.optimizer.util.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DLSS upscale pipeline: downscale → temporal blend → quality upscale → unsharp.
 *
 * Reliability rules learned the hard way:
 *
 *  1. **RGBA everywhere.** `GL_ALPHA` render targets are reported `FRAMEBUFFER_UNSUPPORTED` by most
 *     mobile drivers — that was the "upscale pipeline failed" bug. All work is RGBA8 now.
 *  2. **Probe before you render.** [GpuTextures.supportsRenderTarget] checks the exact size we intend to
 *     use. If the GPU refuses (old Adreno caps, VRAM pressure) we shrink the working resolution instead
 *     of throwing.
 *  3. **Shader ladder.** Lanczos → bicubic → bilinear+sharpen. Whatever links wins; nothing crashes.
 *  4. **Zero allocation per frame.** Textures are pooled, readback goes into one cached staging bitmap.
 */
class RenderPipeline {

    companion object {
        private const val TAG = "RenderPipeline"
        private const val MIN_TARGET = 256
    }

    private val egl = EglCore()
    private val programs = ProgramCache()
    private val staging = GpuTextures.StagingPool()

    private var upscaleProgram = 0
    private var sharpenProgram = 0
    private var blendProgram = 0
    private var upscaleTierId: String = "none"

    private var srcTex = 0
    private var downTex = 0
    private var histTex = 0
    private var blendTex = 0
    private var outTex = 0
    private var fbo = 0

    private var outWidth = 0
    private var outHeight = 0
    private var downWidth = 0
    private var downHeight = 0

    private val healthy = AtomicBoolean(false)
    private var initialized = false
    private var haveHistory = false
    private var frames = 0L
    private var lastCostMs = 0L
    private var sharpenStrength = 0.35f
    private var temporalAlpha = 0.20f
    private var qualityScale = 0.70f

    fun isHealthy(): Boolean = healthy.get() && initialized
    fun framesProcessed(): Long = frames
    fun lastFrameCostMs(): Long = lastCostMs
    fun upscaleTier(): String = upscaleTierId
    fun workingSize(): String = "${outWidth}x$outHeight"

    /**
     * Creates the context and compiles the ladder. Returns false only when the device cannot do
     * off-screen rendering at all; callers then fall back to governor/preview mode.
     */
    fun init(width: Int, height: Int, preferLanczos: Boolean): Boolean {
        if (initialized) release()
        return try {
            if (!egl.create()) {
                Logger.e(TAG, "EGL unavailable")
                return false
            }
            val maxTex = GpuTextures.maxTextureSize()

            // Right-size the working surface: never exceed what the driver can render into.
            var w = width.coerceAtMost(maxTex)
            var h = height.coerceAtMost(maxTex)
            var guard = 0
            while (guard < 6 && !GpuTextures.supportsRenderTarget(w, h, maxTex)) {
                w = (w * 3) / 4
                h = (h * 3) / 4
                guard++
            }
            if (w < MIN_TARGET || h < MIN_TARGET) {
                Logger.e(TAG, "renderable surface too small (${w}x$h)")
                egl.release()
                return false
            }
            outWidth = w and 0xFFFFFFFE.toInt()
            outHeight = h and 0xFFFFFFFE.toInt()
            downWidth = (outWidth * qualityScale).toInt().coerceAtLeast(2)
            downHeight = (outHeight * qualityScale).toInt().coerceAtLeast(2)

            compilePrograms(preferLanczos)
            createTargets()
            initialized = true
            healthy.set(true)
            Logger.i(
                TAG,
                "pipeline ready ${outWidth}x$outHeight (down ${downWidth}x$downHeight) tier=$upscaleTierId"
            )
            true
        } catch (t: Throwable) {
            Logger.e(TAG, "init failed: ${t.message}", t)
            release()
            false
        }
    }

    private fun compilePrograms(preferLanczos: Boolean) {
        val ladder = if (preferLanczos) {
            ShaderLibrary.upscaleTiers()
        } else {
            listOf(ShaderLibrary.TIER_BICUBIC, ShaderLibrary.TIER_LINEAR)
        }
        val compiled = programs.compileFirstUsable(ladder)
            ?: throw IllegalStateException("no upscale shader compiled")
        upscaleProgram = compiled.program
        upscaleTierId = compiled.tier.id

        sharpenProgram = programs.compileFirstUsable(listOf(ShaderLibrary.SHARPEN))?.program ?: 0
        blendProgram = programs.compileFirstUsable(listOf(ShaderLibrary.BLEND))?.program ?: 0
        if (sharpenProgram == 0) Logger.w(TAG, "unsharp pass unavailable, using upscale-only chain")
    }

    private fun createTargets() {
        srcTex = GpuTextures.createTexture()
        downTex = GpuTextures.createTexture()
        histTex = GpuTextures.createTexture()
        blendTex = GpuTextures.createTexture()
        outTex = GpuTextures.createTexture()
        GpuTextures.allocate(srcTex, outWidth, outHeight)
        GpuTextures.allocate(downTex, downWidth, downHeight)
        GpuTextures.allocate(histTex, downWidth, downHeight)
        GpuTextures.allocate(blendTex, downWidth, downHeight)
        GpuTextures.allocate(outTex, outWidth, outHeight)
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fbo = fbos[0]
        haveHistory = false
    }

    fun setSharpen(strength: Float) {
        sharpenStrength = strength.coerceIn(0f, 1f)
    }

    fun setTemporalAlpha(alpha: Float) {
        temporalAlpha = alpha.coerceIn(0.05f, 0.6f)
    }

    fun setQualityScale(scale: Float) {
        qualityScale = scale.coerceIn(0.5f, 1.0f)
    }

    /**
     * Runs the chain on one captured frame.
     *
     * @return an upscaled + sharpened bitmap sized to the working surface, or null when unhealthy.
     */
    fun process(input: Bitmap, renderScale: Float): Bitmap? {
        if (!healthy.get() || !initialized) return null
        val started = System.nanoTime()
        return try {
            if (!egl.makeCurrent()) {
                healthy.set(false)
                Logger.e(TAG, "EGL context lost")
                return null
            }
            val scale = renderScale.coerceIn(0.5f, 1f)
            val (inW, inH) = fitInput(input)

            GpuTextures.upload(srcTex, input)

            // 1 — downscale to the DRS target (bilinear hardware filter, 1 tap)
            val targetW = (downWidth * scale).toInt().coerceIn(2, downWidth)
            val targetH = (downHeight * scale).toInt().coerceIn(2, downHeight)
            drawTo(downTex, targetW, targetH) {
                // The upscale tier samples `uTex`, so reuse it with unit texel = input size.
                renderStage(upscaleProgram, srcTex, targetW, targetH, inW, inH, sharpen = 0.42f)
            }

            // 2 — temporal blend (in-place rotation, no feedback loop)
            if (blendProgram != 0 && haveHistory) {
                drawTo(blendTex, targetW, targetH) {
                    GLES20.glUseProgram(blendProgram)
                    GpuTextures.bind(0, histTex)
                    GpuTextures.bind(1, downTex)
                    programs.int(blendProgram, "uPrev", 0)
                    programs.int(blendProgram, "uCur", 1)
                    programs.float(blendProgram, "uAlpha", temporalAlpha)
                    programs.draw(blendProgram)
                }
                // rotate: blendTex (mixed) becomes the source, histTex receives it for the next frame
                val previous = histTex
                histTex = blendTex
                blendTex = previous
            } else {
                copyTex(downTex, histTex, downWidth, downHeight)
                haveHistory = true
            }

            // 3 — upscale back to the working resolution with the quality kernel
            drawTo(outTex, outWidth, outHeight) {
                renderStage(upscaleProgram, histTex, outWidth, outHeight, targetW, targetH, sharpen = 0.42f)
            }

            // 4 — unsharp mask into the readback target (srcTex is free now)
            if (sharpenProgram != 0) {
                drawTo(srcTex, outWidth, outHeight) {
                    GLES20.glUseProgram(sharpenProgram)
                    GpuTextures.bind(0, outTex)
                    programs.int(sharpenProgram, "uTex", 0)
                    programs.vec2(sharpenProgram, "uTexel", 1f / outWidth, 1f / outHeight)
                    programs.float(sharpenProgram, "uStrength", sharpenStrength)
                    programs.draw(sharpenProgram)
                }
            } else {
                copyTex(outTex, srcTex, outWidth, outHeight)
            }

            // 5 — read back into the cached staging bitmap
            val result = staging.acquire(outWidth, outHeight) ?: return null
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, srcTex, 0
            )
            GLES20.glReadPixels(0, 0, outWidth, outHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, stagingBuffer())
            stagingBuffer().position(0)
            result.copyPixelsFromBuffer(stagingBuffer())
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            frames++
            lastCostMs = (System.nanoTime() - started) / 1_000_000L
            programs.checkGl("process")
            result
        } catch (t: Throwable) {
            Logger.e(TAG, "process failed: ${t.message}", t)
            healthy.set(false)
            null
        }
    }

    private fun fitInput(input: Bitmap): Pair<Int, Int> =
        if (input.width > outWidth || input.height > outHeight) {
            outWidth to outHeight
        } else {
            input.width to input.height
        }

    private fun renderStage(
        program: Int,
        sourceTex: Int,
        targetW: Int,
        targetH: Int,
        srcW: Int,
        srcH: Int,
        sharpen: Float
    ) {
        GLES20.glUseProgram(program)
        GpuTextures.bind(0, sourceTex)
        programs.int(program, "uTex", 0)
        programs.vec2(program, "uTexel", 1f / srcW.coerceAtLeast(1), 1f / srcH.coerceAtLeast(1))
        programs.vec2(program, "uSize", targetW.toFloat(), targetH.toFloat())
        programs.float(program, "uStrength", sharpen)
        programs.draw(program)
    }

    private fun drawTo(targetTex: Int, width: Int, height: Int, block: () -> Unit) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, targetTex, 0
        )
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        block()
    }

    /** Direct texture-to-texture copy using the blend shader at alpha 1 (avoids CPU round trips). */
    private fun copyTex(sourceTex: Int, targetTex: Int, width: Int, height: Int) {
        if (blendProgram == 0) return
        drawTo(targetTex, width, height) {
            GLES20.glUseProgram(blendProgram)
            GpuTextures.bind(0, sourceTex)
            GpuTextures.bind(1, sourceTex)
            programs.int(blendProgram, "uPrev", 0)
            programs.int(blendProgram, "uCur", 1)
            programs.float(blendProgram, "uAlpha", 1f)
            programs.draw(blendProgram)
        }
    }

    private var readback: java.nio.ByteBuffer? = null

    private fun stagingBuffer(): java.nio.ByteBuffer {
        val needed = outWidth * outHeight * 4
        val existing = readback
        if (existing != null && existing.capacity() >= needed) return existing
        val fresh = java.nio.ByteBuffer.allocateDirect(needed).order(java.nio.ByteOrder.nativeOrder())
        readback = fresh
        return fresh
    }

    fun release() {
        healthy.set(false)
        initialized = false
        try {
            if (egl.isValid()) {
                GpuTextures.delete(srcTex)
                GpuTextures.delete(downTex)
                GpuTextures.delete(histTex)
                GpuTextures.delete(blendTex)
                GpuTextures.delete(outTex)
                if (fbo != 0) {
                    val ids = intArrayOf(fbo)
                    GLES20.glDeleteFramebuffers(1, ids, 0)
                }
                programs.delete(upscaleProgram)
                programs.delete(sharpenProgram)
                programs.delete(blendProgram)
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "release: ${t.message}")
        } finally {
            egl.release()
            staging.release()
            upscaleProgram = 0
            sharpenProgram = 0
            blendProgram = 0
            srcTex = 0
            downTex = 0
            histTex = 0
            blendTex = 0
            outTex = 0
            fbo = 0
            outWidth = 0
            outHeight = 0
            haveHistory = false
            readback = null
        }
    }
}
