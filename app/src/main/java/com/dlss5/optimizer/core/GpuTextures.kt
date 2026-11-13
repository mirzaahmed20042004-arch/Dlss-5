package com.dlss5.optimizer.core

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import com.dlss5.optimizer.util.Logger

/**
 * RGBA texture pool + a reusable [android.graphics.Bitmap] staging buffer.
 *
 * **Critical fix:** the previous implementation used `GL_ALPHA` textures. In GLES 2.0 `GL_ALPHA` is not
 * guaranteed to be a *renderable* (FBO-attachable) format — on most Adreno/Mali drivers the framebuffer
 * reports `GL_FRAMEBUFFER_UNSUPPORTED`, which is exactly the "upscale pipeline failed" report. Every
 * texture here is `GL_RGBA`/`GL_UNSIGNED_BYTE`, which is the one format the spec guarantees for colour
 * attachments, and it also keeps full colour for the HDR grade.
 *
 * Staging bitmaps are pooled per size: creating a 1080p ARGB bitmap per frame allocates ~8 MB every
 * 33 ms, which alone would stall a game. We allocate once and reuse.
 */
object GpuTextures {

    private const val TAG = "GpuTextures"

    fun createTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    fun allocate(tex: Int, width: Int, height: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
    }

    fun bind(unit: Int, tex: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
    }

    fun upload(tex: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
    }

    fun delete(tex: Int) {
        if (tex != 0) {
            try {
                val ids = intArrayOf(tex)
                GLES20.glDeleteTextures(1, ids, 0)
            } catch (t: Throwable) {
                Logger.w(TAG, "delete texture failed: ${t.message}")
            }
        }
    }

    /**
     * Returns true when the driver can actually render into a texture of this size. When it cannot (old
     * GPUs cap FBOs at 1024–2048 px, or VRAM is exhausted), callers downscale rather than fail.
     */
    fun supportsRenderTarget(width: Int, height: Int, maxTextureSize: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width > maxTextureSize || height > maxTextureSize) return false
        val tex = createTexture()
        val fbos = IntArray(1)
        var ok = false
        try {
            allocate(tex, width, height)
            GLES20.glGenFramebuffers(1, fbos, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0
            )
            ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        } catch (t: Throwable) {
            Logger.w(TAG, "render target probe failed: ${t.message}")
        } finally {
            try {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                if (fbos[0] != 0) GLES20.glDeleteFramebuffers(1, fbos, 0)
            } catch (t: Throwable) {
                // ignore
            }
            delete(tex)
        }
        return ok
    }

    fun maxTextureSize(): Int = try {
        val out = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, out, 0)
        if (out[0] <= 0) 2048 else out[0]
    } catch (t: Throwable) {
        2048
    }

    /**
     * Staging bitmap pool. [acquire] never returns a bitmap smaller than requested and reuses the
     * previous allocation when the size matches, which is the common case during a session.
     */
    class StagingPool {
        private var current: Bitmap? = null
        private var lastW = 0
        private var lastH = 0

        @Synchronized
        fun acquire(width: Int, height: Int): Bitmap? {
            if (width <= 0 || height <= 0) return null
            val existing = current
            if (existing != null && !existing.isRecycled && existing.width == width && existing.height == height) {
                existing.eraseColor(android.graphics.Color.BLACK)
                return existing
            }
            return try {
                if (existing != null && !existing.isRecycled) existing.recycle()
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    current = it
                    lastW = width
                    lastH = height
                }
            } catch (t: Throwable) {
                Logger.e(TAG, "staging bitmap ${width}x$height failed: ${t.message}")
                null
            }
        }

        @Synchronized
        fun release() {
            try {
                current?.let { if (!it.isRecycled) it.recycle() }
            } catch (t: Throwable) {
                Logger.w(TAG, "staging release: ${t.message}")
            } finally {
                current = null
                lastW = 0
                lastH = 0
            }
        }

        @Synchronized
        fun size(): String = "${lastW}x$lastH"
    }
}
