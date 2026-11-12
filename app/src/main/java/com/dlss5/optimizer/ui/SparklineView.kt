package com.dlss5.optimizer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Tiny FPS sparkline used in each history row. */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = Color.parseColor("#39FF14")
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2239FF14")
    }
    private val path = Path()
    private val fillPath = Path()
    private var values: FloatArray = FloatArray(0)

    fun setValues(newValues: FloatArray, accent: Int = Color.parseColor("#39FF14")) {
        values = newValues
        linePaint.color = accent
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.size < 2) return
        val maxValue = (values.maxOrNull() ?: 60f).coerceAtLeast(1f)
        val w = width.toFloat()
        val h = height.toFloat()
        path.reset()
        fillPath.reset()
        values.forEachIndexed { index, value ->
            val x = w * index / (values.size - 1)
            val y = h - (value / maxValue) * (h - 2f * density) - density
            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(w, h)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}
