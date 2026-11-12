package com.dlss5.optimizer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.dlss5.optimizer.util.Haptics
import com.dlss5.optimizer.util.Logger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * The draggable 56dp control puck.
 *
 * Behaviour contract:
 *  * Single tap → [onTap] (toggles the stats HUD).
 *  * Long press → [onLongPress] (opens the action menu).
 *  * Drag → moves the whole *window* via [onDrag] with delta in px; the view itself never scrolls
 *    internally so the window stays tiny and can never intercept game touches.
 *  * Snaps to the nearest horizontal edge on release ([onDragEnd]).
 *
 * Everything is drawn manually on the Canvas so there is no layout inflation cost and no ripple
 * animation stealing frames from the game.
 */
class FloatingPuckView(context: Context) : View(context) {

    var onTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null
    var onDragEnd: ((snapToLeft: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val puckSizePx = 56f * density
    private val ringStroke = 2f * density
    private val chevronStroke = 2.2f * density

    private val neonGreen = Color.parseColor("#39FF14")
    private val dangerRed = Color.parseColor("#FF2D55")
    private val amber = Color.parseColor("#FFC400")

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringStroke
        color = neonGreen
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#C7000000")
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringStroke * 4f
        color = Color.parseColor("#3339FF14")
        isDither = true
    }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = chevronStroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = neonGreen
    }
    private val chevronPath = Path()
    private val ringRect = RectF()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false
    private var longPressFired = false
    private var active = true
    private var thermalWarn = false

    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!dragging) {
            longPressFired = true
            try {
                Haptics.confirm(context)
                onLongPress?.invoke()
            } catch (t: Throwable) {
                Logger.w(TAG, "long press failed: ${t.message}")
            }
        }
    }

    private var pulse = 0f

    private val pulseRunnable: Runnable = object : Runnable {
        override fun run() {
            pulse = (pulse + 0.08f) % 1f
            invalidate()
            handler.postDelayed(this, 40L)
        }
    }

    init {
        isClickable = true
        isFocusable = false
        contentDescription = "DLSS-5 controls. Drag to move, long press for options."
        handler.postDelayed(pulseRunnable, 1200L)
    }

    fun setActive(active: Boolean) {
        this.active = active
        invalidate()
    }

    fun setThermalWarning(warn: Boolean) {
        if (thermalWarn == warn) return
        thermalWarn = warn
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = puckSizePx.toInt() + (8 * density).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        try {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val radius = min(w, h) / 2f - ringStroke * 3f
            ringRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

            // outer breathing glow
            glowPaint.alpha = if (active) (60 + (pulse * 90).toInt()) else 26
            canvas.drawCircle(cx, cy, radius, glowPaint)

            canvas.drawCircle(cx, cy, radius, fillPaint)

            ringPaint.color = when {
                thermalWarn -> amber
                active -> neonGreen
                else -> dangerRed
            }
            canvas.drawCircle(cx, cy, radius, ringPaint)

            // Three stacked chevrons: the upscaling glyph, identical to the app logo motif.
            chevronPath.reset()
            val cw = radius * 0.62f
            val gap = radius * 0.34f
            val top = cy - gap * 1.35f
            for (i in 0 until 3) {
                val y = top + i * gap
                chevronPath.moveTo(cx - cw / 2f, y + cw * 0.32f)
                chevronPath.lineTo(cx, y)
                chevronPath.lineTo(cx + cw / 2f, y + cw * 0.32f)
            }
            chevronPaint.color = when {
                thermalWarn -> amber
                active -> neonGreen
                else -> Color.parseColor("#9CA89C")
            }
            chevronPaint.alpha = if (active) 255 else 160
            canvas.drawPath(chevronPath, chevronPaint)
        } catch (t: Throwable) {
            Logger.w(TAG, "puck draw failed: ${t.message}")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return try {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                    longPressFired = false
                    handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    isPressed = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dxTotal = event.rawX - downRawX
                    val dyTotal = event.rawY - downRawY
                    if (!dragging && hypot(dxTotal, dyTotal) > touchSlop) {
                        dragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        val dx = event.rawX - lastRawX
                        val dy = event.rawY - lastRawY
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        onDrag?.invoke(dx, dy)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    isPressed = false
                    if (dragging) {
                        val centerX = event.rawX
                        val screenW = resources.displayMetrics.widthPixels
                        onDragEnd?.invoke(centerX < screenW / 2f)
                    } else if (!longPressFired) {
                        performClick()
                    }
                    dragging = false
                    true
                }

                else -> super.onTouchEvent(event)
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "touch failed: ${t.message}")
            false
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        try {
            Haptics.confirm(context)
            onTap?.invoke()
        } catch (t: Throwable) {
            Logger.w(TAG, "tap failed: ${t.message}")
        }
        return true
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TAG = "Puck"

        /** Distance (dp) from the horizontal edge when snapped. */
        const val EDGE_MARGIN_DP = 10f

        fun absDpToPx(context: Context, dp: Float): Float = dp * context.resources.displayMetrics.density

        fun snapOffset(context: Context): Int = (absDpToPx(context, EDGE_MARGIN_DP)).toInt()

        fun almostEqual(a: Float, b: Float, eps: Float = 0.5f): Boolean = abs(a - b) < eps
    }
}
