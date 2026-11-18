package app.merlinbattery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** 270-degree arc gauge: track + progress + centre text. No emoji, no images. */
class GaugeView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var value: Float = Float.NaN          // 0..100, NaN = unknown
    var centerLabel: String = "REAL"
    var arcColor: Int = Color.parseColor("#00E5A0")
    // Track (the border ring of the gauge) is ALWAYS drawn, even when the
    // value is unknown — never a borderless gauge.
    var trackColor: Int = Color.parseColor("#3A4A5C")
    var textColor: Int = Color.parseColor("#F2F5F7")
    var mutedColor: Int = Color.parseColor("#A8B3BF")
    var strokeWidthDp: Float = 16f
    var centerTextSizeSp: Float = 56f

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun setValueAnimated(v: Float) { value = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0) return
        val stroke = strokeWidthDp * resources.displayMetrics.density
        val rect = RectF(stroke / 2 + 8, stroke / 2 + 8, size - stroke / 2 - 8, size - stroke / 2 - 8)
        arcPaint.strokeWidth = stroke
        arcPaint.color = trackColor
        canvas.drawArc(rect, 135f, 270f, false, arcPaint)
        if (!value.isNaN()) {
            arcPaint.color = arcColor
            canvas.drawArc(rect, 135f, 270f * (value.coerceIn(0f, 100f) / 100f), false, arcPaint)
        }
        val cx = size / 2f; val cy = size / 2f
        textPaint.color = textColor
        textPaint.textSize = centerTextSizeSp * resources.displayMetrics.scaledDensity
        textPaint.isFakeBoldText = true
        val main = if (value.isNaN()) "—" else "${value.toInt()}%"
        canvas.drawText(main, cx, cy + textPaint.textSize * 0.3f, textPaint)
        labelPaint.color = mutedColor
        labelPaint.textSize = 12f * resources.displayMetrics.scaledDensity
        canvas.drawText(centerLabel, cx, cy + textPaint.textSize * 0.3f + 28f * resources.displayMetrics.density, labelPaint)
    }
}

/** Simple line chart: grid + line + fill. Values list, min/max labels only. */
class LineChartView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var values: List<Float> = emptyList()
    var lineColor: Int = Color.parseColor("#38BDF8")
    var emptyText: String = "No data yet"
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A4A5C"); strokeWidth = 1f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6B7885"); textSize = 12f * resources.displayMetrics.scaledDensity; textAlign = Paint.Align.CENTER }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        // grid
        for (i in 1..3) canvas.drawLine(0f, h * i / 4f, w, h * i / 4f, gridPaint)
        if (values.size < 2) {
            canvas.drawText(emptyText, w / 2f, h / 2f, textPaint)
            return
        }
        val minV = values.min(); val maxV = values.max()
        val range = (maxV - minV).takeIf { it > 0.001f } ?: 1f
        val pad = 12f * resources.displayMetrics.density
        val path = android.graphics.Path()
        values.forEachIndexed { i, v ->
            val x = pad + (w - 2 * pad) * i / (values.size - 1)
            val y = h - pad - (h - 2 * pad) * (v - minV) / range
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // fill under line
        val fill = android.graphics.Path(path)
        fill.lineTo(pad + (w - 2 * pad), h - pad); fill.lineTo(pad, h - pad); fill.close()
        fillPaint.color = lineColor
        fillPaint.alpha = 28
        canvas.drawPath(fill, fillPaint)
        linePaint.color = lineColor
        canvas.drawPath(path, linePaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("%.1f".format(maxV), 4f, 16f * resources.displayMetrics.density, textPaint)
        canvas.drawText("%.1f".format(minV), 4f, h - 4f, textPaint)
    }
}
