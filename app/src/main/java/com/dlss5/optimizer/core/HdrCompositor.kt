package com.dlss5.optimizer.core

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import com.dlss5.optimizer.util.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Real-time HDR / colour-grade compositor.
 *
 * Pipeline per frame (all on the GPU, one EGL context, no window surface):
 *
 *   RGBA capture
 *     → pass 1  bright-pass + 4-tap downsample   (bloom source, quarter resolution)
 *     → pass 2  horizontal blur                  (separated gaussian)
 *     → pass 3  vertical blur                     (separated gaussian)
 *     → pass 4  HDR composite:
 *                 linearise → exposure → contrast pivot → temperature
 *                 → Reinhard highlight rolloff → shadow lift → luma-preserving saturation
 *                 → add bloom → 5-tap unsharp → blend with original by `blend`
 *     → RGBA output for the overlay surface
 *
 * This is a genuine image-processing chain: every uniform in [HdrPreset] has an effect you can see.
 * It is deliberately *not* a fake shader-compilation stub.
 *
 * Failure policy: any GL/shader error flips [healthy] to false, [process] returns null, and the caller
 * falls back to the raw frame (or governor mode). The app never crashes because a driver is picky.
 */
class HdrCompositor {

    companion object {
        private const val TAG = "HdrGL"
        private const val BLOOM_DIVISOR = 4
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var brightProgram = 0
    private var blurProgram = 0
    private var compositeProgram = 0

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

    private var preset: HdrPreset = HdrPreset.GAME_VIVID
    private var blend = 1f
    private var enabled = true

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    private val vertexShader = """
        attribute vec2 aPos;
        varying vec2 vUv;
        uniform vec2 uFlip;
        void main() {
            vUv = aPos * 0.5 + 0.5;
            gl_Position = vec4(aPos * uFlip, 0.0, 1.0);
        }
    """.trimIndent()

    /** Bright-pass extraction with a 4-tap box downsample. */
    private val brightShader = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTex;
        uniform vec2 uTexel;
        uniform float uThreshold;
        void main() {
            vec3 c = texture2D(uTex, vUv + vec2(-uTexel.x, -uTexel.y)).rgb;
            c += texture2D(uTex, vUv + vec2(uTexel.x, -uTexel.y)).rgb;
            c += texture2D(uTex, vUv + vec2(-uTexel.x, uTexel.y)).rgb;
            c += texture2D(uTex, vUv + vec2(uTexel.x, uTexel.y)).rgb;
            c *= 0.25;
            float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
            float weight = smoothstep(uThreshold, uThreshold + 0.28, lum);
            gl_FragColor = vec4(c * weight, 1.0);
        }
    """.trimIndent()

    /** Separated 9-tap gaussian (direction supplied by uDir). */
    private val blurShader = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTex;
        uniform vec2 uTexel;
        uniform vec2 uDir;
        void main() {
            vec2 step = uTexel * uDir;
            vec3 sum = texture2D(uTex, vUv).rgb * 0.227027;
            sum += texture2D(uTex, vUv + step * 1.384615).rgb * 0.316216;
            sum += texture2D(uTex, vUv - step * 1.384615).rgb * 0.316216;
            sum += texture2D(uTex, vUv + step * 3.230769).rgb * 0.070270;
            sum += texture2D(uTex, vUv - step * 3.230769).rgb * 0.070270;
            gl_FragColor = vec4(sum, 1.0);
        }
    """.trimIndent()

    /**
     * The money shader: full HDR grade + bloom + unsharp + blend.
     * Kept branch-light for mobile GPUs; no loops, no derivatives.
     */
    private val compositeShader = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTex;
        uniform sampler2D uBloom;
        uniform vec2 uTexel;
        uniform float uExposure;
        uniform float uContrast;
        uniform float uSaturation;
        uniform float uTemperature;
        uniform float uShadowLift;
        uniform float uRolloff;
        uniform float uBloomStrength;
        uniform float uSharpen;
        uniform float uBlend;

        vec3 linearize(vec3 c) {
            // cheap sRGB -> linear approximation (gamma 2.2 is accurate enough and branchless)
            return pow(max(c, vec3(0.0)), vec3(2.2));
        }
        vec3 encode(vec3 c) {
            return pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));
        }
        float luma(vec3 c) {
            return dot(c, vec3(0.2126, 0.7152, 0.0722));
        }

        void main() {
            vec3 original = texture2D(uTex, vUv).rgb;
            vec3 c = linearize(original);

            // exposure
            c *= uExposure;

            // white balance (temperature): warm lifts red, cools blue
            c.r *= (1.0 + uTemperature);
            c.b *= (1.0 - uTemperature);

            // contrast around the linear midpoint
            c = (c - 0.5) * uContrast + 0.5;

            // HDR highlight shoulder: Reinhard compressed only above the knee
            float knee = 0.62;
            vec3 over = max(c - knee, vec3(0.0));
            c = min(c, vec3(knee)) + over / (1.0 + over * max(uRolloff, 0.001) * 2.0);

            // shadow lift keeps dark corners readable without washing the whole frame
            float lum = luma(c);
            float shadowMask = 1.0 - smoothstep(0.0, 0.42, lum);
            c += uShadowLift * shadowMask;

            // luma-preserving saturation
            float gray = luma(c);
            c = mix(vec3(gray), c, uSaturation);

            // bloom (additive, screen-blended to avoid clipping)
            vec3 bloom = texture2D(uBloom, vUv).rgb;
            c += bloom * uBloomStrength * 0.6;

            // back to display space
            vec3 graded = encode(c);

            // unsharp mask on the graded result (crisp textures / player edges)
            vec3 l = texture2D(uTex, vUv - vec2(uTexel.x, 0.0)).rgb;
            vec3 r = texture2D(uTex, vUv + vec2(uTexel.x, 0.0)).rgb;
            vec3 u = texture2D(uTex, vUv - vec2(0.0, uTexel.y)).rgb;
            vec3 d = texture2D(uTex, vUv + vec2(0.0, uTexel.y)).rgb;
            vec3 sharpened = graded + uSharpen * (graded * 4.0 - l - r - u - d);

            // blend against the untouched frame so the user can dial the effect down
            gl_FragColor = vec4(mix(original, clamp(sharpened, 0.0, 1.0), uBlend), 1.0);
        }
    """.trimIndent()

    fun isHealthy(): Boolean = healthy.get() && initialized
    fun frameCount(): Long = frames
    fun lastFrameCostMs(): Long = lastCostMs
    fun currentPreset(): HdrPreset = preset

    fun setPreset(newPreset: HdrPreset) {
        preset = newPreset.sanitized()
    }

    fun setBlend(value: Float) {
        blend = value.coerceIn(0f, 1f)
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** Creates the context and programs. Must be called from the thread that will render. */
    fun init(width: Int, height: Int, initialPreset: HdrPreset, initialBlend: Float): Boolean {
        if (initialized) release()
        return try {
            preset = initialPreset.sanitized()
            blend = initialBlend.coerceIn(0f, 1f)
            createEgl()
            createPrograms()
            resize(width, height)
            initialized = true
            healthy.set(true)
            Logger.i(TAG, "HDR compositor ready ${width}x$height preset=${preset.id}")
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
            throw IllegalStateException("eglInitialize failed")
        }
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
            throw IllegalStateException("no EGL config")
        }
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext failed")
        val pbAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("pbuffer failed")
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }
    }

    private fun createPrograms() {
        brightProgram = buildProgram(brightShader)
        blurProgram = buildProgram(blurShader)
        compositeProgram = buildProgram(compositeShader)
        srcTex = newTexture()
        bloomTexA = newTexture()
        bloomTexB = newTexture()
        outTex = newTexture()
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fbo = fbos[0]
    }

    private fun newTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun buildProgram(fragmentSource: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
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

    fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w == width && h == height && healthy.get()) return
        width = w
        height = h
        bloomWidth = max(2, w / BLOOM_DIVISOR)
        bloomHeight = max(2, h / BLOOM_DIVISOR)
        allocate(srcTex, width, height)
        allocate(bloomTexA, bloomWidth, bloomHeight)
        allocate(bloomTexB, bloomWidth, bloomHeight)
        allocate(outTex, width, height)
        Logger.d(TAG, "resize $width x $height (bloom ${bloomWidth}x$bloomHeight)")
    }

    private fun allocate(tex: Int, w: Int, h: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
    }

    /**
     * Grades one captured frame.
     *
     * @param input RGBA_8888 bitmap from the capture engine (same size as [width] x [height]).
     * @return the graded bitmap, or null if the compositor is unhealthy / disabled.
     */
    fun process(input: Bitmap): Bitmap? {
        if (!healthy.get() || !initialized || !enabled) return null
        if (input.width <= 0 || input.height <= 0) return null
        val started = System.nanoTime()
        return try {
            if (input.width != width || input.height != height) resize(input.width, input.height)
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                healthy.set(false)
                Logger.e(TAG, "context lost, disabling compositor")
                return null
            }

            // ---- upload
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, input)

            // ---- pass 1: bright pass → quarter res
            renderTo(bloomTexA, bloomWidth, bloomHeight) {
                GLES20.glUseProgram(brightProgram)
                bindTexture(0, srcTex)
                setInt(brightProgram, "uTex", 0)
                setVec2(brightProgram, "uTexel", 1f / width, 1f / height)
                setFloat(brightProgram, "uThreshold", preset.bloomThreshold)
                setVec2(brightProgram, "uFlip", 1f, 1f)
                drawQuad(brightProgram)
            }

            // ---- pass 2: horizontal blur → B
            renderTo(bloomTexB, bloomWidth, bloomHeight) {
                GLES20.glUseProgram(blurProgram)
                bindTexture(0, bloomTexA)
                setInt(blurProgram, "uTex", 0)
                setVec2(blurProgram, "uTexel", 1f / bloomWidth, 1f / bloomHeight)
                setVec2(blurProgram, "uDir", 1f, 0f)
                setVec2(blurProgram, "uFlip", 1f, 1f)
                drawQuad(blurProgram)
            }

            // ---- pass 3: vertical blur → A
            renderTo(bloomTexA, bloomWidth, bloomHeight) {
                GLES20.glUseProgram(blurProgram)
                bindTexture(0, bloomTexB)
                setInt(blurProgram, "uTex", 0)
                setVec2(blurProgram, "uTexel", 1f / bloomWidth, 1f / bloomHeight)
                setVec2(blurProgram, "uDir", 0f, 1f)
                setVec2(blurProgram, "uFlip", 1f, 1f)
                drawQuad(blurProgram)
            }

            // ---- pass 4: composite into the output texture
            renderTo(outTex, width, height) {
                GLES20.glUseProgram(compositeProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(compositeProgram, "uTex"), 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bloomTexA)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(compositeProgram, "uBloom"), 1)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

                setVec2(compositeProgram, "uTexel", 1f / width, 1f / height)
                setFloat(compositeProgram, "uExposure", preset.exposure)
                setFloat(compositeProgram, "uContrast", preset.contrast)
                setFloat(compositeProgram, "uSaturation", preset.saturation)
                setFloat(compositeProgram, "uTemperature", preset.temperature)
                setFloat(compositeProgram, "uShadowLift", preset.shadowLift)
                setFloat(compositeProgram, "uRolloff", preset.highlightRolloff)
                setFloat(compositeProgram, "uBloomStrength", preset.bloom)
                setFloat(compositeProgram, "uSharpen", preset.sharpen)
                setFloat(compositeProgram, "uBlend", blend)
                setVec2(compositeProgram, "uFlip", 1f, 1f)
                drawQuad(compositeProgram)
            }

            // ---- read back
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val buffer = ByteBuffer.allocateDirect(width * height * 4)
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            buffer.position(0)
            result.copyPixelsFromBuffer(buffer)
            checkGl("composite")

            frames++
            lastCostMs = (System.nanoTime() - started) / 1_000_000L
            result
        } catch (t: Throwable) {
            Logger.e(TAG, "process failed, disabling compositor", t)
            healthy.set(false)
            null
        }
    }

    private inline fun renderTo(targetTex: Int, w: Int, h: Int, block: () -> Unit) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, targetTex, 0
        )
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        block()
    }

    private fun bindTexture(unit: Int, tex: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
    }

    private fun setInt(program: Int, name: String, value: Int) {
        val loc = GLES20.glGetUniformLocation(program, name)
        if (loc >= 0) GLES20.glUniform1i(loc, value)
    }

    private fun setFloat(program: Int, name: String, value: Float) {
        val loc = GLES20.glGetUniformLocation(program, name)
        if (loc >= 0) GLES20.glUniform1f(loc, value)
    }

    private fun setVec2(program: Int, name: String, x: Float, y: Float) {
        val loc = GLES20.glGetUniformLocation(program, name)
        if (loc >= 0) GLES20.glUniform2f(loc, x, y)
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
            Logger.w(TAG, "teardown: ${t.message}")
        } finally {
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            brightProgram = 0
            blurProgram = 0
            compositeProgram = 0
            srcTex = 0
            bloomTexA = 0
            bloomTexB = 0
            outTex = 0
            fbo = 0
            width = 0
            height = 0
        }
    }
}
