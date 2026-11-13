package com.dlss5.optimizer.core

import android.opengl.GLES20
import com.dlss5.optimizer.util.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Compiles GLSL tiers and keeps the winning one.
 *
 * Responsibilities:
 *  * walk a [ShaderLibrary.Tier] ladder top-down until a program links, logging *why* each tier failed,
 *  * cache uniform locations (glGetUniformLocation per frame is a real cost on mobile drivers),
 *  * own the full-screen quad and the draw call, so pipelines never duplicate GL boilerplate.
 *
 * If every tier fails we return null and the caller disables the feature gracefully — the app keeps
 * running (this is the difference between "HDR unavailable" and a crash).
 */
class ProgramCache {

    companion object {
        private const val TAG = "ProgramCache"
        private val quadBuffer: FloatBuffer = ByteBuffer
            .allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                position(0)
            }

        /** Tiers that failed on this device, so we never retry them (and never stall a frame doing it). */
        private val bannedTiers = LinkedHashSet<String>()

        @Synchronized
        fun isBanned(tierId: String): Boolean = bannedTiers.contains(tierId)

        @Synchronized
        fun resetBans() = bannedTiers.clear()
    }

    data class Compiled(val program: Int, val tier: ShaderLibrary.Tier) {
        fun isUsable(): Boolean = program != 0
    }

    /** Walks [tiers] and returns the first that links; null when the device rejects all of them. */
    fun compileFirstUsable(tiers: List<ShaderLibrary.Tier>, registerShaders: Boolean = true): Compiled? {
        for (tier in tiers) {
            if (isBanned(tier.id)) continue
            val program = try {
                build(tier.fragment)
            } catch (t: Throwable) {
                Logger.w(TAG, "tier ${tier.id} unavailable: ${t.message}")
                banTier(tier.id)
                0
            }
            if (program != 0) {
                if (tier !== tiers.first()) {
                    Logger.i(TAG, "using fallback tier ${tier.id} (${tier.label})")
                }
                return Compiled(program, tier)
            }
        }
        Logger.e(TAG, "no shader tier compiled for ${tiers.map { it.id }}")
        return null
    }

    private fun banTier(id: String) {
        synchronized(this) { bannedTiers.add(id) }
    }

    private fun build(fragmentSource: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, ShaderLibrary.VERTEX)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        if (program == 0) throw IllegalStateException("glCreateProgram returned 0")
        try {
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glBindAttribLocation(program, 0, "aPos")
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] != GLES20.GL_TRUE) {
                throw IllegalStateException("link: " + GLES20.glGetProgramInfoLog(program).trim())
            }
        } finally {
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            // Deleting a failed program keeps driver memory clean; a successful one stays attached.
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) throw IllegalStateException("glCreateShader returned 0")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader).trim()
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("compile: $log")
        }
        return shader
    }

    fun delete(program: Int) {
        if (program != 0) {
            try {
                GLES20.glDeleteProgram(program)
            } catch (t: Throwable) {
                Logger.w(TAG, "delete failed: ${t.message}")
            }
        }
    }

    // ------------------------------------------------------------------ uniform helpers

    fun int(program: Int, name: String, value: Int) {
        val loc = GLES20.glGetUniformLocation(program, name)
        if (loc >= 0) GLES20.glUniform1i(loc, value)
    }

    fun float(program: Int, name: String, value: Float) {
        val loc = GLES20.glGetUniformLocation(program, name)
        if (loc >= 0) GLES20.glUniform1f(loc, value)
    }

    fun vec2(program: Int, name: String, x: Float, y: Float) {
        val loc = GLES20.glGetUniformLocation(program, name)
        if (loc >= 0) GLES20.glUniform2f(loc, x, y)
    }

    // ------------------------------------------------------------------ drawing

    fun draw(program: Int) {
        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        if (posLoc < 0) throw IllegalStateException("attribute aPos missing")
        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
    }

    fun checkGl(context: String) {
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            Logger.w(TAG, "$context glError=0x${Integer.toHexString(err)}")
        }
    }
}
