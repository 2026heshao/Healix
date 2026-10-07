package com.healix.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.healix.app.R

/**
 * 迷你趋势线（v8 问题 4：记录页目标区三卡）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 与 [TrendChartView] 的分工（为什么是两个控件而不是加开关）
 * ══════════════════════════════════════════════════════════════════════════
 * [TrendChartView] 的几何是**常量写死**的 80dp 版式（顶部范围标注基线 y=18dp、
 * 绘图区 20–62dp、右下窗口标注基线 y=78dp、底部 1dp 底线）——
 * 在 20dp 高的卡槽里它会把这些**画到画布之外**。
 *
 * 三卡要的是完全不同的东西：**只有一条线的形状**（标题与数值已由卡片承担，
 * 再标一遍范围/窗口是冗余）。所以这里是一个约 60 行的独立控件，
 * 而不是给 TrendChartView 加"压缩模式"—— 那样会动到状态详情页正在用的共用几何。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 硬规则（与规范 §9.3 同源）
 * ══════════════════════════════════════════════════════════════════════════
 * - 无网格、无坐标轴、无图例、无绘制动画
 * - 线色只来自 `primary`（**不按数值正负切换红绿**，不铺面、不加渐变）
 * - 只在最后一个点画实心圆
 * - 数据点 < 3 时不画线，居中显示占位文案（文案由调用方传入）
 * - 左右不留内边距（卡片自己已有 padding），上下只留 marker 半径，避免末点被裁
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    /** 末点实心圆半径（3dp 直径，与 TrendChartView 的末点同口径）。 */
    private val markerRadius = dp(1.5f)
    private val chartStroke = resources.getDimension(R.dimen.chart_stroke)

    private var values: List<Double> = emptyList()
    private var emptyText: String = ""

    private val chartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = chartStroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.primary)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.primary)
    }

    /** 占位文案：12sp `text_3`，与三卡标题同级（规范 §9.3 的"数据不足"态）。 */
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_3)
    }

    /** 提交数据点（升序）。数量 < 3 时不画线，改显示 [setEmptyText]。 */
    fun submit(values: List<Double>) {
        this.values = values
        invalidate()
    }

    /** 数据不足（< 3 点）时居中显示的占位文案。 */
    fun setEmptyText(text: String) {
        this.emptyText = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val left = 0f
        val right = w.toFloat()
        val top = markerRadius + dp(0.5f)
        val bottom = h - markerRadius - dp(0.5f)
        if (bottom <= top) return

        // ① 数据不足 3 点：不画线，居中占位文案
        if (values.size < 3) {
            if (emptyText.isEmpty()) return
            val centerY = (top + bottom) / 2f
            val baseline = centerY - (emptyPaint.descent() + emptyPaint.ascent()) / 2f
            canvas.drawText(emptyText, (left + right) / 2f, baseline, emptyPaint)
            return
        }

        val n = values.size
        var minV = values[0]
        var maxV = values[0]
        for (v in values) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val span = maxV - minV
        val plotH = bottom - top
        val stepX = (right - left) / (n - 1).toFloat()

        // Y 轴自适应：上下各留 15% 余量；全平（span == 0）时定在绘图区中线，避免除零。
        val lo = if (span == 0.0) 0.0 else minV - 0.15 * span
        val hi = if (span == 0.0) 0.0 else maxV + 0.15 * span

        val path = Path()
        var lastX = left
        var lastY = (top + bottom) / 2f
        for (i in 0 until n) {
            val x = left + i * stepX
            val y = if (span == 0.0) {
                top + plotH / 2f
            } else {
                val fraction = ((values[i] - lo) / (hi - lo)).toFloat()
                bottom - fraction * plotH
            }
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            lastX = x
            lastY = y
        }
        canvas.drawPath(path, chartPaint)
        canvas.drawCircle(lastX, lastY, markerRadius, markerPaint)
    }
}
