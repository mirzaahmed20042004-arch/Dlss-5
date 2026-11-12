package com.dlss5.optimizer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.dlss5.optimizer.util.Logger

/**
 * The hero FPS ring: an animated neon arc that fills proportionally to measured FPS, with the number in
 * the middle and a sub-label underneath. Pure Canvas — no images, no allocations per frame.
 */
class NeonRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private val neon = Color.parseColor("#39FF14")
    private val cyan = Color.parseColor("#00FFC6")
    private val amber = Color.parseColor("#FFC400")
    private val red = Color.parseColor("#FF2D55")
    private val white = Color.WHITE
    private val muted = Color.parseColor("#9CA89C")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f * density
        color = Color.parseColor("#1A39FF14")
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f * density
        strokeCap = Paint.Cap.ROUND
        color = neon
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 22f * density
        color = Color.parseColor("#2639FF14")
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = muted
        textSize = 9.5f * density
        letterSpacing = 0.2f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        textSize = 46f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = neon
        textSize = 11f * density
        letterSpacing = 0.14f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private val arcRect = RectF()
    private var progress = 0f
    private var animatedProgress = 0f
    private var measuredFps = 0.0
    private var targetFps = 60
    private var stateLabel = "ENGINE IDLE"
    private var subLabel = "0.70x → NATIVE"
    private var active = false
    private var scanning = 0f

    private var sweepShader: Shader? = null

    private val scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            scanning = it.animatedValue as Float
            if (active) invalidate()
        }
    }

    init {
        setWillNotDraw(false)
        try {
            scanAnimator.start()
        } catch (t: Throwable) {
            Logger.w(TAG, "scan animator failed: ${t.message}")
        }
    }

    fun setActive(active: Boolean) {
        this.active = active
        invalidate()
    }

    fun setTarget(targetFps: Int) {
        this.targetFps = targetFps.coerceIn(30, 144)
        invalidate()
    }

    fun setStats(fps: Double, scale: Float, stateText: String? = null, sub: String? = null) {
        measuredFps = fps
        progress = (fps / targetFps.toDouble()).coerceIn(0.0, 1.0).toFloat()
        stateLabel = stateText ?: if (active) "ENGINE ACTIVE" else "ENGINE IDLE"
        subLabel = sub ?: "%.2fx → NATIVE".format(scale)
        animateTo(progress)
    }

    fun setIdle(scale: Float = 0.7f) {
        measuredFps = 0.0
        progress = 0f
        animateTo(0f)
        stateLabel = "ENGINE IDLE"
        subLabel = "%.2fx → NATIVE".format(scale)
        active = false
        invalidate()
    }

    private fun animateTo(target: Float) {
        try {
            ValueAnimator.ofFloat(animatedProgress, target).apply {
                duration = 420
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener {
                    animatedProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } catch (t: Throwable) {
            animatedProgress = target
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = 22f * density
        arcRect.set(pad, pad, w - pad, h - pad)
        sweepShader = try {
            SweepGradient(
                w / 2f, h / 2f,
                intArrayOf(neon, cyan, neon),
                floatArrayOf(0f, 0.5f, 1f)
            )
        } catch (t: Throwable) {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        try {
            val cx = width / 2f
            val cy = height / 2f
            val radius = (minOf(width, height) / 2f) - 30f * density

            // outer glow ring
            glowPaint.alpha = if (active) 140 else 50
            canvas.drawArc(arcRect, 0f, 360f, false, glowPaint)

            // track + ticks
            canvas.drawArc(arcRect, 135f, 270f, false, trackPaint)
            for (i in 0..20) {
                val angle = Math.toRadians((135f + i * (270f / 20f)).toDouble())
                val inner = radius - 20f * density
                val outer = radius - 15f * density
                tickPaint.color = if (i % 5 == 0) Color.parseColor("#6639FF14") else Color.parseColor("#3339FF14")
                canvas.drawLine(
                    (cx + inner * Math.cos(angle)).toFloat(),
                    (cy + inner * Math.sin(angle)).toFloat(),
                    (cx + outer * Math.cos(angle)).toFloat(),
                    (cy + outer * Math.sin(angle)).toFloat(),
                    tickPaint
                )
            }

            // progress arc
            arcPaint.color = when {
                !active -> Color.parseColor("#1F8F12")
                measuredFps >= targetFps * 0.92 -> neon
                measuredFps >= targetFps * 0.7 -> amber
                else -> red
            }
            canvas.drawArc(arcRect, 135f, 270f * animatedProgress.coerceIn(0f, 1f), false, arcPaint)

            // scanning highlight while active
            if (active) {
                val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 10f * density
                    strokeCap = Paint.Cap.ROUND
                    color = cyan
                    alpha = 90
                    shader = sweepShader
                }
                canvas.drawArc(arcRect, 135f + scanning * 270f, 26f, false, scanPaint)
            }

            // centre readout
            val valueText = if (measuredFps > 0.5) "%.0f".format(measuredFps) else "--"
            canvas.drawText(valueText, cx, cy + 8f * density, valuePaint)
            valuePaint.textSize = 12f * density
            valuePaint.color = muted
            canvas.drawText("FPS", cx, cy + 26f * density, valuePaint)
            valuePaint.color = white
            valuePaint.textSize = 46f * density

            canvas.drawText(stateLabel, cx, cy - 40f * density, labelPaint)
            canvas.drawText(subLabel, cx, cy + 52f * density, subPaint.apply {
                color = if (active) cyan else muted
            })

            // target caption
            labelPaint.color = Color.parseColor("#6639FF14")
            canvas.drawText("TARGET ${targetFps} FPS", cx, cy + 72f * density, labelPaint)
            labelPaint.color = muted
        } catch (t: Throwable) {
            Logger.w(TAG, "ring draw failed: ${t.message}")
        }
    }

    override fun onDetachedFromWindow() {
        try {
            scanAnimator.cancel()
        } catch (t: Throwable) {
            // ignore
        }
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TAG = "NeonRing"
    }
}
