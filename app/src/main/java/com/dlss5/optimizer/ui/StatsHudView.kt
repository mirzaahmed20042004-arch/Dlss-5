package com.dlss5.optimizer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.dlss5.optimizer.core.EngineController
import com.dlss5.optimizer.core.SessionRecord
import com.dlss5.optimizer.util.Logger

/**
 * Full-screen, entirely non-interactive HUD: live metrics plus (optionally) the TRUE-DLSS preview.
 *
 * Its window is `FLAG_NOT_TOUCHABLE`, so *every* touch lands on the game underneath. This is the piece
 * that makes the overlay safe over PUBG's fire/aim controls.
 */
class StatsHudView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val neon = Color.parseColor("#39FF14")
    private val cyan = Color.parseColor("#00FFC6")
    private val amber = Color.parseColor("#FFC400")
    private val red = Color.parseColor("#FF2D55")
    private val white = Color.WHITE
    private val muted = Color.parseColor("#9CA89C")

    private val panelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D9000000")
        style = Paint.Style.FILL
    }
    private val panelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6639FF14")
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = muted
        textSize = 8.5f * density
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        letterSpacing = 0.16f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        textSize = 17f * density
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val bigFpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = neon
        textSize = 30f * density
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyan
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val sparkPath = Path()
    private val panelRect = RectF()

    private var stats: EngineController.LiveStats? = null
    private var showPanel = true
    private var previewAlpha = 0f
    private var statusText: String = "STANDBY"
    private var statusColor: Int = muted
    private val fpsHistory = ArrayDeque<Float>()

    @Volatile
    private var previewBitmap: android.graphics.Bitmap? = null

    /**
     * Double buffer: the capture thread produces a new ALPHA_8 bitmap every frame while the UI thread is
     * still drawing the previous one. Holding two slots lets us swap without tearing and lets us recycle
     * the bitmap that is no longer referenced (zero allocation churn during a session).
     */
    private var previewSwap: android.graphics.Bitmap? = null
    private var previewRecycleNext = false
    private var previewPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setPanelVisible(visible: Boolean) {
        showPanel = visible
        invalidate()
    }

    fun isPanelVisible(): Boolean = showPanel

    fun setStatus(text: String, color: Int = muted) {
        statusText = text
        statusColor = color
        invalidate()
    }

    fun update(stats: EngineController.LiveStats) {
        this.stats = stats
        synchronized(fpsHistory) {
            fpsHistory.addLast(stats.fps.toFloat())
            if (fpsHistory.size > 48) fpsHistory.removeFirst()
        }
        invalidate()
    }

    /** TRUE DLSS preview frame — drawn dimmed so the game remains playable while it is on. */
    fun setPreview(frame: android.graphics.Bitmap?, alpha: Float) {
        try {
            if (frame == null) {
                previewSwap?.let { if (!it.isRecycled) it.recycle() }
                previewSwap = null
                previewBitmap = null
                previewAlpha = 0f
            } else {
                val outgoing = previewSwap
                previewSwap = frame
                previewBitmap = frame
                if (previewRecycleNext && outgoing != null && !outgoing.isRecycled) {
                    outgoing.recycle()
                }
                previewRecycleNext = true
                previewAlpha = alpha.coerceIn(0f, 1f)
            }
            invalidate()
        } catch (t: Throwable) {
            Logger.w(TAG, "setPreview failed: ${t.message}")
        }
    }

    override fun onDetachedFromWindow() {
        try {
            previewBitmap = null
            previewSwap?.let { if (!it.isRecycled) it.recycle() }
            previewSwap = null
        } catch (t: Throwable) {
            Logger.w(TAG, "preview cleanup failed: ${t.message}")
        }
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        try {
            val bmp = previewBitmap
            if (bmp != null && !bmp.isRecycled && previewAlpha > 0f) {
                previewPaint.alpha = (previewAlpha * 255).toInt()
                val src = android.graphics.Rect(0, 0, bmp.width, bmp.height)
                val dst = android.graphics.Rect(0, 0, width, height)
                canvas.drawBitmap(bmp, src, dst, previewPaint)
            }
            if (!showPanel) return
            drawPanel(canvas)
        } catch (t: Throwable) {
            Logger.w(TAG, "hud draw failed: ${t.message}")
        }
    }

    private fun drawPanel(canvas: Canvas) {
        val pad = 10f * density
        val w = (168f * density)
        val h = (118f * density)
        val left = width - w - 12f * density
        val top = 74f * density
        panelRect.set(left, top, left + w, top + h)

        canvas.drawRoundRect(panelRect, 12f * density, 12f * density, panelBg)
        canvas.drawRoundRect(panelRect, 12f * density, 12f * density, panelStroke)

        // accent bar
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = statusColor
            strokeWidth = 3f * density
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(left, top + 14f * density, left, top + h - 14f * density, bar)

        val s = stats
        var y = top + 22f * density
        val x = left + pad + 6f * density

        canvas.drawText("DLSS-5", x, y, labelPaint)
        canvas.drawText(statusText, x + 62f * density, y, labelPaint.apply { color = statusColor })

        y += 30f * density
        val fps = s?.fps ?: 0.0
        bigFpsPaint.color = when {
            fps >= (s?.targetFps ?: 60) * 0.92 -> neon
            fps >= (s?.targetFps ?: 60) * 0.7 -> amber
            else -> red
        }
        canvas.drawText(if (fps > 0) "%.0f".format(fps) else "--", x, y, bigFpsPaint)
        bigFpsPaint.textSize = 10f * density
        bigFpsPaint.color = muted
        canvas.drawText("FPS", x + 42f * density, y, bigFpsPaint)
        bigFpsPaint.textSize = 30f * density

        y += 22f * density
        canvas.drawText("%.1f ms".format(s?.frameMs ?: 0.0), x, y, valuePaint.apply { textSize = 13f * density })
        canvas.drawText("p95 %.1f".format(s?.p95FrameMs ?: 0.0), x + 56f * density, y, valuePaint)
        y += 18f * density
        canvas.drawText("SCALE %.2fx".format(s?.renderScale ?: 0.7f), x, y, valuePaint.apply { textSize = 12f * density })
        val modeName = SessionRecord.modeName(s?.mode ?: 0).replace('_', ' ')
        canvas.drawText(modeName, x + 68f * density, y, valuePaint.apply { color = cyan; textSize = 10f * density })

        // sparkline
        val sparkTop = top + h - 26f * density
        val sparkLeft = left + 18f * density
        val sparkWidth = w - 34f * density
        val sparkHeight = 16f * density
        synchronized(fpsHistory) {
            if (fpsHistory.size > 2) {
                val maxFps = (fpsHistory.maxOrNull() ?: 60f).coerceAtLeast(30f)
                sparkPath.reset()
                fpsHistory.forEachIndexed { index, value ->
                    val px = sparkLeft + sparkWidth * index / (fpsHistory.size - 1)
                    val py = sparkTop + sparkHeight - (value / maxFps) * sparkHeight
                    if (index == 0) sparkPath.moveTo(px, py) else sparkPath.lineTo(px, py)
                }
                canvas.drawPath(sparkPath, sparkPaint)
            }
        }
    }

    companion object {
        private const val TAG = "Hud"
    }
}
