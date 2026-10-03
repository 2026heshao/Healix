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
 * 趋势折线（设计规范 §9.3）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么自绘
 * ══════════════════════════════════════════════════════════════════════════
 * 项目不引入图表库（「不引入依赖」），且现有控件族里没有能遵守
 * 「无网格、无坐标轴、无图例」这套克制规则的东西。约 120 行 Kotlin，纯绘制。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 硬规则（违反即算设计回归，§9.3 / §9.12）
 * ══════════════════════════════════════════════════════════════════════════
 * - 无绘制动画、无网格线、无坐标轴刻度数字、无图例
 * - 折线颜色只来自 `accent`，**不按数值正负切换红绿**
 * - 只有最后一个点画 3dp 实心 accent 圆；中间点不画
 * - 数据点 < 3 时不画线，绘图区居中显示占位文案（文案由调用方传入）
 * - 颜色一律走 `ContextCompat.getColor`，深色模式由 values-night 自动覆盖
 *
 * 几何（80dp 总高，宽度 match_parent；横向内边距 = 屏幕边距 20dp）：
 * ```
 * 58.2 – 58.6 公斤                              ← 13sp text_2，基线 y=18dp
 *        ╭──────╮  ●                            ← 折线 1.6dp accent，末点 3dp 实心圆
 *   ─────╯      ╰────                           ← 绘图区 top=20dp / bottom=62dp
 * ────────────────────────────────────          ← 底线 1dp line
 *                                        近 7 日 ← 13sp text_3，基线 y=78dp
 * ```
 */
class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** dp → px。所有尺寸都由 dp 换算，不硬编码 px。 */
    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    /** sp → px（文字尺寸必须随系统字号缩放，§2.2）。 */
    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    // ── 几何常量 ──────────────────────────────────────────────────────
    private val padH = resources.getDimension(R.dimen.screen_margin) // 20dp，与屏幕边距一致
    private val plotTop = dp(20f)
    private val plotBottom = dp(62f)
    private val rangeBaseline = dp(18f)   // 顶部范围标注基线
    private val windowBaseline = dp(78f)  // 右下角窗口标注基线
    private val markerRadius = dp(1.5f)   // 3dp 直径的实心圆
    private val chartStroke = resources.getDimension(R.dimen.chart_stroke) // 1.6dp

    // ── 数据 ─────────────────────────────────────────────────────────
    private var values: List<Double> = emptyList()
    private var minLabel: String = ""
    private var maxLabel: String = ""
    private var windowLabel: String = ""
    private var unit: String = ""
    private var emptyText: String = ""

    // ── 画笔（全部 ANTI_ALIAS，颜色走资源）────────────────────────────
    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        textAlign = Paint.Align.LEFT
        color = ContextCompat.getColor(context, R.color.text_2)
    }
    private val windowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        textAlign = Paint.Align.RIGHT
        color = ContextCompat.getColor(context, R.color.text_3)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_2)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = ContextCompat.getColor(context, R.color.line)
    }
    private val chartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = chartStroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.accent)
    }

    /** 提交数据点（升序）。数量 < 3 时不画线。 */
    fun submit(values: List<Double>) {
        this.values = values
        invalidate()
    }

    /**
     * 设置文案。
     *
     * @param minLabel 最小值（已格式化，如 "58.2"；为空则不画范围标注）
     * @param maxLabel 最大值
     * @param windowLabel 窗口标注（已格式化，如 "近 7 日"；为空则不画）
     * @param unit 单位（如 "kg" / "小时"），参与范围标注拼接
     * @param emptyText 数据不足 3 点时绘图区中央的占位文案
     */
    fun setMeta(
        minLabel: String,
        maxLabel: String,
        windowLabel: String,
        unit: String,
        emptyText: String,
    ) {
        this.minLabel = minLabel
        this.maxLabel = maxLabel
        this.windowLabel = windowLabel
        this.unit = unit
        this.emptyText = emptyText
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        if (w <= 0 || height <= 0) return
        val left = padH
        val right = w - padH
        if (right <= left) return

        // ① 顶部范围标注：最小值 – 最大值 单位（走 strings.xml 的 chart_range）
        if (minLabel.isNotEmpty() || maxLabel.isNotEmpty()) {
            val range = context.getString(R.string.chart_range, minLabel, maxLabel, unit)
            canvas.drawText(range, left, rangeBaseline, rangePaint)
        }

        // ② 右下角窗口标注
        if (windowLabel.isNotEmpty()) {
            canvas.drawText(windowLabel, right, windowBaseline, windowPaint)
        }

        // ③ 底线（1dp line，横跨绘图区）
        canvas.drawLine(left, plotBottom, right, plotBottom, linePaint)

        // ④ 数据不足 3 点：不画线，绘图区居中显示占位文案
        if (values.size < 3) {
            val centerY = (plotTop + plotBottom) / 2f
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
        val plotH = plotBottom - plotTop
        val stepX = (right - left) / (n - 1).toFloat()

        // Y 轴自适应：上下各留 15% 余量。全平（span == 0）时避免除零，统一定在绘图区中线。
        val lo = if (span == 0.0) 0.0 else minV - 0.15 * span
        val hi = if (span == 0.0) 0.0 else maxV + 0.15 * span

        val path = Path()
        var lastX = left
        var lastY = (plotTop + plotBottom) / 2f
        for (i in 0 until n) {
            val x = left + i * stepX
            val y = if (span == 0.0) {
                plotTop + plotH / 2f
            } else {
                val fraction = ((values[i] - lo) / (hi - lo)).toFloat()
                plotBottom - fraction * plotH
            }
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            lastX = x
            lastY = y
        }
        canvas.drawPath(path, chartPaint)
        // 只有最后一个点画实心标记
        canvas.drawCircle(lastX, lastY, markerRadius, markerPaint)
    }
}
