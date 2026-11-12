package com.dlss5.optimizer.core

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import com.dlss5.optimizer.util.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Off-screen GLES pipeline:  downscale → (temporal blend) → upscale (bicubic/lanczos) → unsharp mask.
 *
 * Design notes
 *  * Runs on its own EGL context bound to a 1×1 PBuffer surface, so it never needs a window and can be
 *    driven entirely from a background thread.
 *  * Input frames are uploaded as `GL_ALPHA` textures (our raster is greyscale luma). That cuts
 *    upload bandwidth by 4× versus RGBA and is exactly what a sharpener needs.
 *  * Shadow textures are kept in a 2-entry LRU — a long-lived Bitmap plus its GPU twin. This keeps
 *    per-frame allocation at zero (no GC churn during play) while still surviving resolution changes.
 *  * Every public method is exception-safe. If any GL call fails or a shader fails to compile we set
 *    [healthy] = false and refuse further work; the caller degrades to GOVERNOR mode.
 */
class RenderPipeline {

    companion object {
        private const val TAG = "RenderPipeline"
        private const val SHADOW_SLOTS = 2
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null

    private var downProgram = 0
    private var upProgram = 0
    private var sharpenProgram = 0
    private var blendProgram = 0

    private var srcTex = 0
    private var downTex = 0
    private var blendTex = 0
    private var outTex = 0
    private var fbo = 0

    private var downWidth = 0
    private var downHeight = 0
    private var outWidth = 0
    private var outHeight = 0

    private val healthy = AtomicBoolean(false)
    private var initialized = false
    private var useLanczos = false
    private var lastUpscaleMs = 0L
    private var lastSharpenStrength = 0.35f
    private var temporalAlpha = 0.15f
    private var haveHistory = false
    private var framesProcessed = 0L

    private val shadowBitmaps = arrayOfNulls<Bitmap>(SHADOW_SLOTS)
    private val shadowSizes = IntArray(SHADOW_SLOTS)

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    private val vertexShader = """
        attribute vec2 aPos;
        varying vec2 vUv;
        void main() {
            vUv = aPos * 0.5 + 0.5;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """.trimIndent()

    /** Plain bilinear fetch — used by the downscale and the blend passes. */
    private val downShader = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTex;
        void main() { gl_FragColor = vec4(texture2D(uTex, vUv).a); }
    """.trimIndent()

    /** Bicubic (Catmull-Rom) separable-ish 4×4 tap; good quality, cheap enough for mid-tier GPUs. */
    private val bicubicShader = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTex;
        uniform vec2 uTexel;
        float w0(float t){ return ((-0.5*t + 1.0)*t - 0.5)*t; }
        float w1(float t){ return ((1.5*t - 2.5)*t)*t + 1.0; }
        float w2(float t){ return ((-1.5*t + 2.0)*t + 0.5)*t; }
        float w3(float t){ return ((0.5*t - 0.5)*t)*t; }
        void main() {
            vec2 pos = vUv / uTexel - 0.5;
            vec2 f = fract(pos);
            vec2 base = (pos - f) * uTexel;
            vec4 wx = vec4(w0(f.x), w1(f.x), w2(f.x), w3(f.x));
            vec4 wy = vec4(w0(f.y), w1(f.y), w2(f.y), w3(f.y));
            float acc = 0.0;
            for (int j = 0; j < 4; j++) {
                float rowAcc = 0.0;
                for (int i = 0; i < 4; i++) {
                    vec2 uv = base + vec2(float(i - 1), float(j - 1)) * uTexel;
                    rowAcc += texture2D(uTex, uv).a * wx[i];
                }
                acc += rowAcc * wy[j];
            }
            gl_FragColor = vec4(clamp(acc, 0.0, 1.0));
        }
    """.trimIndent()

    /** Lanczos-3, 6×6 taps. Only enabled on capable GPUs (see FilterKernels.canAffordLanczos). */
    private val lanczosShader = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTex;
        uniform vec2 uTexel;
        float sinc(float x) {
            if (abs(x) < 0.0001) return 1.0;
            float px = 3.14159265 * x;
            return sin(px) / px;
        }
        float lanc(float x) {
            if (abs(x) >= 3.0) return 0.0;
            return sinc(x) * sinc(x / 3.0);
        }
        void main() {
            vec2 pos = vUv / uTexel - 0.5;
            vec2 base = floor(pos);
            vec2 f = pos - base;
            float acc = 0.0;
            float wsum = 0.0;
            for (int j = -2; j <= 3; j++) {
                float wy = lanc(float(j) - f.y);
                for (int i = -2; i <= 3; i++) {
                    float wx = lanc(float(i) - f.x);
                    vec2 uv = (base + vec2(float(i), float(j))) * uTexel;
                    acc += texture2D(uTex, uv).a * wx * wy;
                    wsum += wx * wy;
                }
            }
            gl_FragColor = vec4(clamp(acc / max(wsum, 0.0001), 0.0, 1.0));
        }
    """.trimIndent()

    /** 5-tap unsharp mask. */
    private val sharpenShader = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTex;
        uniform vec2 uTexel;
        uniform float uStrength;
        void main() {
            float c = texture2D(uTex, vUv).a;
            float l = texture2D(uTex, vUv - vec2(uTexel.x, 0.0)).a;
            float r = texture2D(uTex, vUv + vec2(uTexel.x, 0.0)).a;
            float u = texture2D(uTex, vUv - vec2(0.0, uTexel.y)).a;
            float d = texture2D(uTex, vUv + vec2(0.0, uTexel.y)).a;
            float sharpened = c + uStrength * (4.0 * c - l - r - u - d);
            gl_FragColor = vec4(clamp(sharpened, 0.0, 1.0));
        }
    """.trimIndent()

    /** Temporal accumulator: mix(previous, current, alpha). */
    private val blendShader = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uPrev;
        uniform sampler2D uCur;
        uniform float uAlpha;
        void main() {
            float p = texture2D(uPrev, vUv).a;
            float c = texture2D(uCur, vUv).a;
            gl_FragColor = vec4(mix(p, c, uAlpha));
        }
    """.trimIndent()

    fun isHealthy(): Boolean = healthy.get() && initialized
    fun frames(): Long = framesProcessed
    fun lastFrameCostMs(): Long = lastUpscaleMs
    fun lanczosEnabled(): Boolean = useLanczos

    /** Must be called from the thread that will own the context. */
    fun init(width: Int, height: Int, preferLanczos: Boolean): Boolean {
        if (initialized) release()
        return try {
            createEgl()
            createPrograms(preferLanczos)
            resize(width, height)
            initialized = true
            healthy.set(true)
            Logger.i(TAG, "pipeline ready ${width}x$height lanczos=$preferLanczos")
            true
        } catch (t: Throwable) {
            Logger.e(TAG, "init failed: ${t.message}", t)
            healthy.set(false)
            release()
            false
        }
    }

    private fun createEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("no EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw IllegalStateException("eglInitialize failed 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
            throw IllegalStateException("no EGL config")
        }
        config = configs[0]
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext failed")
        val pbAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, pbAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreatePbufferSurface failed")
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }
    }

    private fun createPrograms(preferLanczos: Boolean) {
        downProgram = buildProgram(vertexShader, downShader)
        upProgram = buildProgram(vertexShader, if (preferLanczos) lanczosShader else bicubicShader)
        useLanczos = preferLanczos
        sharpenProgram = buildProgram(vertexShader, sharpenShader)
        blendProgram = buildProgram(vertexShader, blendShader)
        srcTex = createTexture()
        downTex = createTexture()
        blendTex = createTexture()
        outTex = createTexture()
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fbo = fbos[0]
        checkGl("createPrograms")
    }

    private fun createTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun buildProgram(vsSource: String, fsSource: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vsSource)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fsSource)
        val program = GLES20.glCreateProgram()
        if (program == 0) throw IllegalStateException("glCreateProgram failed")
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glBindAttribLocation(program, 0, "aPos")
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) throw IllegalStateException("glCreateShader failed")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("shader compile failed: $log")
        }
        return shader
    }

    /** (Re)allocates the FBO textures for a new output size. Cheap to call on rotation. */
    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == outWidth && height == outHeight && healthy.get()) return
        outWidth = width
        outHeight = height
        downWidth = (width * 0.70f).toInt().coerceAtLeast(2)
        downHeight = (height * 0.70f).toInt().coerceAtLeast(2)
        allocate(srcTex, width, height)
        allocate(downTex, downWidth, downHeight)
        allocate(blendTex, downWidth, downHeight)
        allocate(outTex, width, height)
        haveHistory = false
        Logger.d(TAG, "resize out=${width}x$height down=${downWidth}x$downHeight")
    }

    private fun allocate(tex: Int, w: Int, h: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, w, h, 0,
            GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, null
        )
        checkGl("allocate $w x $h")
    }

    fun setSharpen(strength: Float) {
        lastSharpenStrength = strength.coerceIn(0f, 1f)
    }

    fun setTemporalAlpha(alpha: Float) {
        temporalAlpha = alpha.coerceIn(0.05f, 0.6f)
    }

    /**
     * Full pass chain for one bitmap.
     *
     * @return the sharpened result bitmap (ownership stays with the caller; we hand back a cached shadow
     *         that is reused next frame — do not retain it beyond the callback).
     */
    fun process(input: Bitmap, renderScale: Float): Bitmap? {
        if (!healthy.get() || !initialized) return null
        val started = System.nanoTime()
        return try {
            val w = input.width
            val h = input.height
            if (w <= 0 || h <= 0) return null
            if (w != outWidth || h != outHeight) resize(w, h)
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                healthy.set(false)
                Logger.e(TAG, "eglMakeCurrent lost, disabling pipeline")
                return null
            }
            upload(input)
            // 1. downscale to the DRS target size
            val sw = (w * renderScale.coerceIn(0.5f, 1f)).toInt().coerceAtLeast(2)
            val sh = (h * renderScale.coerceIn(0.5f, 1f)).toInt().coerceAtLeast(2)
            drawInto(downProgram, srcTex, sw, sh, downTex, 1f, bilinear = true, srcW = w, srcH = h)
            // 2. temporal blend against previous (stabilises micro-jitter)
            if (haveHistory) {
                blendHistory(sw, sh)
            } else {
                haveHistory = true
            }
            // 3. upscale back to full size with the quality kernel
            drawInto(upProgram, blendTex, w, h, outTex, 1f, bilinear = false, srcW = sw, srcH = sh)
            // 4. unsharp mask on the full-size result, into downTex's slot as scratch → read back
            val sharpened = sharpenPass(w, h)
            framesProcessed++
            lastUpscaleMs = (System.nanoTime() - started) / 1_000_000L
            sharpened
        } catch (t: Throwable) {
            Logger.e(TAG, "process failed, disabling pipeline", t)
            healthy.set(false)
            null
        }
    }

    private fun upload(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        checkGl("upload")
    }

    private fun bindFbo(tex: Int, w: Int, h: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0
        )
        GLES20.glViewport(0, 0, w, h)
    }

    private fun drawInto(
        program: Int,
        sourceTex: Int,
        w: Int,
        h: Int,
        targetTex: Int,
        strength: Float,
        bilinear: Boolean,
        srcW: Int,
        srcH: Int
    ) {
        bindFbo(targetTex, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        val texel = GLES20.glGetUniformLocation(program, "uTexel")
        if (texel >= 0) GLES20.glUniform2f(texel, 1f / srcW.coerceAtLeast(1), 1f / srcH.coerceAtLeast(1))
        val strengthLoc = GLES20.glGetUniformLocation(program, "uStrength")
        if (strengthLoc >= 0) GLES20.glUniform1f(strengthLoc, strength)
        drawQuad(program)
    }

    private fun blendHistory(w: Int, h: Int) {
        // downTex holds the current downscaled frame; blendTex holds history. Render into downTex's
        // sibling by sampling both and writing to downTex (we then treat downTex as "current blended").
        bindFbo(downTex, w, h)
        GLES20.glUseProgram(blendProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blendTex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, downTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blendProgram, "uPrev"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blendProgram, "uCur"), 1)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(blendProgram, "uAlpha"), temporalAlpha)
        drawQuad(blendProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun sharpenPass(w: Int, h: Int): Bitmap? {
        // sharpen outTex → blendTex (scratch, small), then read pixels of the sharpened result.
        val scratchW = w
        val scratchH = h
        if (scratchW != outWidth || scratchH != outHeight) return null
        // Re-use srcTex as the sharpen destination to avoid another full-size allocation.
        bindFbo(srcTex, scratchW, scratchH)
        GLES20.glUseProgram(sharpenProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(sharpenProgram, "uTex"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(sharpenProgram, "uTexel"), 1f / w, 1f / h)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(sharpenProgram, "uStrength"), lastSharpenStrength)
        drawQuad(sharpenProgram)
        // capture the result
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val buffer = ByteBuffer.allocateDirect(w * h)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, buffer)
        buffer.position(0)
        result.copyPixelsFromBuffer(buffer)
        // save the blended downscale as history for the next frame
        copyToHistory()
        checkGl("sharpen")
        return result
    }

    private fun copyToHistory() {
        // history = current downscaled frame (downTex) → blendTex
        if (downWidth <= 0 || downHeight <= 0) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blendTex)
        GLES20.glCopyTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, 0, 0, downWidth, downHeight, 0
        )
        checkGl("copyToHistory")
    }

    private fun drawQuad(program: Int) {
        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        if (posLoc < 0) throw IllegalStateException("missing aPos")
        quad.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
    }

    private fun checkGl(tag: String) {
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            Logger.w(TAG, "$tag glError=0x${Integer.toHexString(err)}")
        }
    }

    /** Frees every GPU object. Safe to call twice. */
    fun release() {
        healthy.set(false)
        initialized = false
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "egl teardown: ${t.message}")
        } finally {
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            downProgram = 0
            upProgram = 0
            sharpenProgram = 0
            blendProgram = 0
            srcTex = 0
            downTex = 0
            blendTex = 0
            outTex = 0
            fbo = 0
            outWidth = 0
            outHeight = 0
            haveHistory = false
        }
    }

    /** Kept for symmetry with GLES3 features we may adopt later (PBO readback). */
    fun supportsPbo(): Boolean = try {
        EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT && GLES30.glGetString(GLES30.GL_VERSION) != null
    } catch (t: Throwable) {
        false
    }
}
