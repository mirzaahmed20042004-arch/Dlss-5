package com.dlss5.optimizer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.dlss5.optimizer.core.HdrPreset
import com.dlss5.optimizer.util.Logger

/**
 * The LIVE HDR presentation surface.
 *
 * Draws the **correction layer** produced by [com.dlss5.optimizer.core.HdrCompositor] edge-to-edge on a
 * transparent window: the game below keeps rendering and receiving touches, while this layer pushes its
 * pixels toward the graded look. Alpha comes from the bitmap itself (per-pixel), so nothing here forces
 * an opaque replacement — which is what keeps the capture loop convergent instead of recursive.
 *
 * The window is `FLAG_NOT_TOUCHABLE`; a small badge shows the active preset and pipeline cost.
 *
 * Triple buffering: the capture thread writes [next], the UI thread draws [current]; the buffer that
 * scrolls out is recycled only when safe, keeping allocation at ~zero during a session.
 */
class HdrSurfaceView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3000000")
        style = Paint.Style.FILL
    }
    private val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6639FF14")
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#39FF14")
        textSize = 9f * density
        letterSpacing = 0.14f
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val badgeRect = RectF()

    @Volatile
    private var current: Bitmap? = null

    private var spare: Bitmap? = null
    private var presetLabel: String = "EXTREME HDR"
    private var costMs: Long = 0
    private var framesDrawn = 0L
    private var visible = false

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Transparent window: the game must stay visible underneath the correction layer.
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun isShowing(): Boolean = visible

    fun setVisible(show: Boolean) {
        visible = show
        visibility = if (show) VISIBLE else GONE
        if (!show) clearFrame()
        invalidate()
    }

    fun setPresetLabel(label: String) {
        presetLabel = label
        invalidate()
    }

    /**
     * Hands a freshly graded frame to the view. Called from the capture thread; the swap itself is
     * cheap and the previous buffer is recycled only once it is no longer referenced.
     */
    fun submitFrame(bitmap: Bitmap, cost: Long) {
        try {
            val outgoing = current
            val oldSpare = spare
            current = bitmap
            spare = outgoing
            costMs = cost
            if (oldSpare != null && oldSpare !== bitmap && !oldSpare.isRecycled) {
                oldSpare.recycle()
            }
            postInvalidateOnAnimation()
        } catch (t: Throwable) {
            Logger.w(TAG, "submitFrame failed: ${t.message}")
        }
    }

    fun clearFrame() {
        try {
            current?.let { if (!it.isRecycled) it.recycle() }
            spare?.let { if (!it.isRecycled) it.recycle() }
        } catch (t: Throwable) {
            Logger.w(TAG, "clearFrame failed: ${t.message}")
        } finally {
            current = null
            spare = null
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!visible) return
        try {
            val bmp = current
            if (bmp != null && !bmp.isRecycled) {
                srcRect.set(0, 0, bmp.width, bmp.height)
                dstRect.set(0, 0, width, height)
                canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
                framesDrawn++
            }
            // No frame yet → draw nothing at all so the game shows through untouched.
            drawBadge(canvas)
        } catch (t: Throwable) {
            Logger.w(TAG, "draw failed: ${t.message}")
        }
    }

    private fun drawBadge(canvas: Canvas) {
        val text = "$presetLabel · ${if (costMs > 0) "${costMs}ms/frame" else "warming up"}"
        val textWidth = badgeText.measureText(text)
        val padH = 8f * density
        val padV = 5f * density
        val left = 12f * density
        val top = 12f * density
        badgeRect.set(left, top, left + textWidth + padH * 2, top + badgeText.textSize + padV * 2)
        canvas.drawRoundRect(badgeRect, 6f * density, 6f * density, badgeBg)
        canvas.drawRoundRect(badgeRect, 6f * density, 6f * density, badgeStroke)
        canvas.drawText(text, left + padH, top + padV + badgeText.textSize * 0.86f, badgeText)
    }

    fun drawnFrames(): Long = framesDrawn

    override fun onDetachedFromWindow() {
        clearFrame()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TAG = "HdrSurface"

        fun describe(preset: HdrPreset): String =
            "${preset.label} · ${"%.2f".format(preset.exposure)}x · vib ${"%.2f".format(preset.saturation)}"
    }
}
