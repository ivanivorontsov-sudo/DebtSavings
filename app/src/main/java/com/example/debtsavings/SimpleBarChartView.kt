package com.example.debtsavings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Простой столбчатый график на Canvas без внешних зависимостей.
 * Горизонтальные подписи снизу; значения сверху столбцов (если место).
 */
class SimpleBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Bar(
        val label: String,
        val value: Double,
        val color: Int
    )

    private var bars: List<Bar> = emptyList()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF79747E.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(10f)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF49454F.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(9f)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF79747E.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(13f)
    }
    private val rect = RectF()
    private var horizontal: Boolean = false
    private var emptyMessage: String = "Нет данных"

    fun setBars(items: List<Bar>, horizontal: Boolean = false, emptyMessage: String = "Нет данных") {
        this.bars = items
        this.horizontal = horizontal
        this.emptyMessage = emptyMessage
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty() || bars.all { it.value <= 0.0 }) {
            canvas.drawText(emptyMessage, width / 2f, height / 2f, emptyPaint)
            return
        }
        if (horizontal) drawHorizontal(canvas) else drawVertical(canvas)
    }

    private fun drawVertical(canvas: Canvas) {
        val maxVal = bars.maxOf { it.value }.coerceAtLeast(1.0)
        val padL = dp(4f)
        val padR = dp(4f)
        val padT = dp(18f)
        val padB = dp(28f)
        val chartW = width - padL - padR
        val chartH = height - padT - padB
        val n = bars.size
        val gap = dp(4f)
        val barW = ((chartW - gap * (n + 1)) / n).coerceAtLeast(dp(6f))

        bars.forEachIndexed { i, bar ->
            val x = padL + gap + i * (barW + gap)
            val h = (bar.value / maxVal * chartH).toFloat().coerceAtLeast(dp(2f))
            val top = padT + chartH - h
            rect.set(x, top, x + barW, padT + chartH)
            barPaint.color = bar.color
            canvas.drawRoundRect(rect, dp(4f), dp(4f), barPaint)

            val label = if (bar.label.length > 6) bar.label.take(5) + "…" else bar.label
            canvas.drawText(label, x + barW / 2f, height - dp(8f), labelPaint)

            if (bar.value > 0 && n <= 14) {
                val vs = formatShort(bar.value)
                canvas.drawText(vs, x + barW / 2f, top - dp(4f), valuePaint)
            }
        }
    }

    private fun drawHorizontal(canvas: Canvas) {
        val maxVal = bars.maxOf { it.value }.coerceAtLeast(1.0)
        val padL = dp(88f)
        val padR = dp(48f)
        val padT = dp(8f)
        val padB = dp(8f)
        val chartW = width - padL - padR
        val n = bars.size
        val rowH = ((height - padT - padB) / max(n, 1)).coerceAtLeast(dp(22f))
        val barH = (rowH * 0.55f).coerceAtMost(dp(18f))

        bars.forEachIndexed { i, bar ->
            val cy = padT + i * rowH + rowH / 2f
            val w = (bar.value / maxVal * chartW).toFloat().coerceAtLeast(dp(2f))
            rect.set(padL, cy - barH / 2f, padL + w, cy + barH / 2f)
            barPaint.color = bar.color
            canvas.drawRoundRect(rect, dp(4f), dp(4f), barPaint)

            labelPaint.textAlign = Paint.Align.RIGHT
            val name = if (bar.label.length > 12) bar.label.take(11) + "…" else bar.label
            canvas.drawText(name, padL - dp(8f), cy + labelPaint.textSize / 3f, labelPaint)
            labelPaint.textAlign = Paint.Align.CENTER

            valuePaint.textAlign = Paint.Align.LEFT
            canvas.drawText(formatShort(bar.value), padL + w + dp(6f), cy + valuePaint.textSize / 3f, valuePaint)
            valuePaint.textAlign = Paint.Align.CENTER
        }
    }

    private fun formatShort(v: Double): String {
        return when {
            v >= 1_000_000 -> String.format("%.1f млн", v / 1_000_000)
            v >= 1_000 -> String.format("%.0f тыс", v / 1_000)
            else -> String.format("%.0f", v)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
