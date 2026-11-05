package com.focustask.companion.ui.fitness

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.focustask.companion.R

/** Small smooth line chart for the last days of step counts. */
class LineChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var values: List<Int> = emptyList()
    private var labels: List<String> = emptyList()
    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.primary)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_orange)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val fillPath = Path()

    fun setData(values: List<Int>, labels: List<String>) {
        this.values = values
        this.labels = labels
        contentDescription = "Steps over the last ${values.size} days: " +
            values.zip(labels).joinToString { "${it.second} ${it.first}" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val labelSpace = 18f * density
        val pad = 8f * density
        val left = pad
        val right = width - pad
        val top = pad
        val bottom = height - labelSpace
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1).toFloat()
        val stepX = if (values.size > 1) (right - left) / (values.size - 1) else 0f

        val xs = values.indices.map { left + it * stepX }
        val ys = values.map { bottom - (it / max) * (bottom - top) }

        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until values.size) {
            val midX = (xs[i - 1] + xs[i]) / 2
            path.cubicTo(midX, ys[i - 1], midX, ys[i], xs[i], ys[i])
        }
        fillPath.reset()
        fillPath.addPath(path)
        fillPath.lineTo(xs.last(), bottom)
        fillPath.lineTo(xs.first(), bottom)
        fillPath.close()

        fillPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            (linePaint.color and 0x00FFFFFF) or 0x55000000, linePaint.color and 0x00FFFFFF,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        for (i in values.indices) {
            canvas.drawCircle(xs[i], ys[i], 4f * density, dotPaint)
            labels.getOrNull(i)?.let { canvas.drawText(it, xs[i], height - 4f * density, labelPaint) }
        }
    }
}
