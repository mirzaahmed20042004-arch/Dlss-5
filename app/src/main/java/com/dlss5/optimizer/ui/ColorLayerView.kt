package com.dlss5.optimizer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import com.dlss5.optimizer.core.ColorLook
import com.dlss5.optimizer.util.Logger

/**
 * The always-visible colour layer.
 *
 * Paints a stack of full-screen gradients over the game:
 *   global scrim → vertical sky/ground gradient → vignette → optional warm/cool wash.
 *
 * This is composited by the system over whatever app is on screen, so it works on **every** device with
 * just `SYSTEM_ALERT_WINDOW` — no MediaProjection, no shaders, no GPU support required. That is what makes
 * the colour change instantly visible even when the capture pipeline is unavailable.
 *
 * The view is `FLAG_NOT_TOUCHABLE`: PUBG keeps every input. Everything is pre-allocated; one `onDraw`
 * costs 3 draw calls, so it cannot cost the game frames.
 */
class ColorLayerView(context: Context) : View(context) {

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var look: ColorLook = ColorLook.DLSS_EXTREME
    private var strength = 1f
    private var visibleNow = false
    private var dirty = true

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    fun isShowing(): Boolean = visibleNow

    fun currentLook(): ColorLook = ColorLook.scaled(look, strength)

    fun applyLook(newLook: ColorLook, newStrength: Float = 1f) {
        look = newLook.sanitized()
        strength = newStrength.coerceIn(0f, 1f)
        dirty = true
        invalidate()
    }

    fun setStrength(value: Float) {
        strength = value.coerceIn(0f, 1f)
        dirty = true
        invalidate()
    }

    fun setShowing(show: Boolean) {
        visibleNow = show
        visibility = if (show) VISIBLE else GONE
        if (show) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dirty = true
    }

    private fun rebuildGradients() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val effective = ColorLook.scaled(look, strength)

        scrimPaint.color = effective.scrimColor
        scrimPaint.alpha = (effective.scrimAlpha * 255).toInt().coerceIn(0, 255)

        // Sky → ground vertical gradient (the "night sky" effect).
        try {
            skyPaint.shader = null
            if (effective.topAlpha > 0.001f || effective.bottomAlpha > 0.001f) {
                val top = withAlpha(effective.topColor, effective.topAlpha)
                val bottom = withAlpha(effective.bottomColor, effective.bottomAlpha)
                skyPaint.shader = LinearGradient(
                    0f, 0f, 0f, h,
                    intArrayOf(top, top, bottom, bottom),
                    floatArrayOf(0f, 0.18f, 0.72f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "sky gradient failed: ${t.message}")
        }

        // Vignette.
        try {
            vignettePaint.shader = null
            if (effective.vignette > 0.001f) {
                val radius = (maxOf(w, h) * 0.78f)
                vignettePaint.shader = RadialGradient(
                    w / 2f, h / 2f, radius,
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, withAlpha(Color.BLACK, effective.vignette)),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "vignette failed: ${t.message}")
        }

        // Warm/cool wash (white-balance illusion).
        try {
            washPaint.shader = null
            washPaint.colorFilter = null
            if (kotlin.math.abs(effective.warmth) > 0.01f) {
                val warm = effective.warmth > 0f
                val colour = if (warm) Color.rgb(255, 170, 90) else Color.rgb(110, 170, 255)
                val alpha = (kotlin.math.abs(effective.warmth) * 34).toInt().coerceIn(0, 255)
                washPaint.colorFilter = PorterDuffColorFilter(colour, PorterDuff.Mode.SRC_OVER)
                washPaint.color = Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour))
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "wash failed: ${t.message}")
        }

        dirty = false
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    override fun onDraw(canvas: Canvas) {
        if (!visibleNow) return
        try {
            if (dirty) rebuildGradients()
            val effective = ColorLook.scaled(look, strength)
            val w = width.toFloat()
            val h = height.toFloat()

            if (effective.scrimAlpha > 0.001f) {
                canvas.drawRect(0f, 0f, w, h, scrimPaint)
            }
            if (skyPaint.shader != null) {
                canvas.drawRect(0f, 0f, w, h, skyPaint)
            }
            if (effective.warmth != 0f && washPaint.colorFilter != null) {
                canvas.drawRect(0f, 0f, w, h, washPaint)
            }
            if (vignettePaint.shader != null) {
                canvas.drawRect(0f, 0f, w, h, vignettePaint)
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "draw failed: ${t.message}")
        }
    }

    override fun onDetachedFromWindow() {
        scrimPaint.shader = null
        skyPaint.shader = null
        vignettePaint.shader = null
        washPaint.colorFilter = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TAG = "ColorLayer"
    }
}
