package com.dlss5.optimizer.core

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import com.dlss5.optimizer.util.Logger

/**
 * Minimal, defensive EGL owner for off-screen rendering.
 *
 * One instance = one GLES2 context on a 1×1 pbuffer, created on the thread that will render. Every call
 * is guarded: [create] returns false rather than throwing, so a stubborn driver degrades a feature
 * instead of crashing the process.
 *
 * Deliberate choices for maximum device coverage:
 *  * ES 2.0 only (every Android device since API 8) — no ES3-only entry points.
 *  * `EGL_PBUFFER_BIT` surface, RGBA8888 config with RGBA fallbacks tried in order.
 *  * `eglMakeCurrent` is re-asserted per frame because some drivers drop the context on backgrounding.
 */
class EglCore {

    companion object {
        private const val TAG = "EglCore"
    }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    fun isValid(): Boolean =
        display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT &&
            surface != EGL14.EGL_NO_SURFACE

    fun create(): Boolean {
        if (isValid()) return true
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                Logger.e(TAG, "no EGL display")
                return false
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                Logger.e(TAG, "eglInitialize failed")
                return false
            }

            val config = chooseConfig() ?: run {
                Logger.e(TAG, "no usable EGL config")
                return false
            }

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) {
                Logger.e(TAG, "eglCreateContext failed")
                return false
            }
            val pbAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, config, pbAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                Logger.e(TAG, "eglCreatePbufferSurface failed")
                return false
            }
            makeCurrent()
            Logger.i(TAG, "EGL ready: ${EGL14.eglQueryString(display, EGL14.EGL_VERSION)}")
            true
        } catch (t: Throwable) {
            Logger.e(TAG, "create failed: ${t.message}", t)
            release()
            false
        }
    }

    /** Tries progressively simpler configs; some Mali/Adreno drivers reject the first pick. */
    private fun chooseConfig(): EGLConfig? {
        val candidates = listOf(
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            ),
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 5, EGL14.EGL_GREEN_SIZE, 6,
                EGL14.EGL_BLUE_SIZE, 5, EGL14.EGL_ALPHA_SIZE, 0,
                EGL14.EGL_NONE
            ),
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
        )
        for (attribs in candidates) {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                configs[0]?.let { return it }
            }
        }
        return null
    }

    fun makeCurrent(): Boolean = try {
        if (!isValid()) false
        else EGL14.eglMakeCurrent(display, surface, surface, context)
    } catch (t: Throwable) {
        Logger.w(TAG, "makeCurrent failed: ${t.message}")
        false
    }

    fun release() {
        try {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "release: ${t.message}")
        } finally {
            display = EGL14.EGL_NO_DISPLAY
            surface = EGL14.EGL_NO_SURFACE
            context = EGL14.EGL_NO_CONTEXT
        }
    }
}
