package com.healix.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.healix.app.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 趋势折线（设计规范 §9.3 / v6 §四「趋势图」）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么自绘
 * ══════════════════════════════════════════════════════════════════════════
 * 项目不引入图表库（「不引入依赖」），且现有控件族里没有能遵守
 * 「无网格、无坐标轴、无图例」这套克制规则的东西。纯绘制。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 硬规则（违反即算设计回归，§9.3 / §9.12）
 * ══════════════════════════════════════════════════════════════════════════
 * - 无绘制动画、无网格线、无坐标轴刻度数字、无图例
 * - 折线与末点只来自 `primary`（**不按数值正负切换红绿**）
 * - v6：折线下方铺面积渐变（primary 12% → 0%）
 * - 只有最后一个点（今日）画 3dp 实心 primary 圆；中间点不画
 * - 数据点 < 3 时不画线，绘图区居中显示占位文案（文案由调用方传入）
 * - 颜色一律走 `ContextCompat.getColor`，深色模式由 values-night 自动覆盖
 *
 * 几何（80dp 总高，宽度 match_parent；横向内边距 = 屏幕边距 20dp）：
 * ```
 * 58.2 – 58.6 公斤                              ← 13sp text_2，基线 y=18dp
 *        ╭──────╮  ●                            ← 折线 1.6dp primary，末点 3dp 实心圆
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

    /** 面积渐变顶色（primary 12% 透明度）。 */
    private val primaryColor = ContextCompat.getColor(context, R.color.primary)
    private val areaTopColor = Color.argb(31, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))

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
        color = primaryColor
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = primaryColor
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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
        val area = Path()
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
            if (i == 0) {
                path.moveTo(x, y)
                area.moveTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
            lastX = x
            lastY = y
        }

        // ⑤ 面积渐变（primary 12% → 0%）：折线路径下行到绘图区底边后闭合
        area.lineTo(lastX, plotBottom)
        area.lineTo(left, plotBottom)
        area.close()
        areaPaint.shader = LinearGradient(
            0f, plotTop, 0f, plotBottom,
            areaTopColor, Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        canvas.drawPath(area, areaPaint)

        // ⑥ 折线 + 今日（末点）primary 实心圆
        canvas.drawPath(path, chartPaint)
        canvas.drawCircle(lastX, lastY, markerRadius, markerPaint)
    }
}

/**
 * Hero 卡右侧环形达标率（v6 记录页 §四）。
 *
 * 76dp 圆环：底轨 `progress_track`、进度弧 `primary`（描边 6dp、圆头、从 12 点起顺时针），
 * 中心两行文字 = 百分比（15sp/600）+ 小标签（10sp，如「达标」，由调用方传入）。
 * 纯绘制、无动画（规范 §3.4 卡片级不做循环动画）。
 */
class HeroRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val stroke = resources.getDimension(R.dimen.hero_ring_stroke) // 6dp
    private val onContainerColor = ContextCompat.getColor(context, R.color.on_primary_container)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = ContextCompat.getColor(context, R.color.progress_track)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.primary)
    }
    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(15f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        color = onContainerColor
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = onContainerColor
    }

    private var percent: Float = 0f
    private var label: String = ""

    /** 提交达标率（0f..1f）与中心小标签。 */
    fun submit(percent: Float, label: String) {
        this.percent = percent.coerceIn(0f, 1f)
        this.label = label
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f - stroke / 2f
        if (r <= 0f) return

        // 底轨
        canvas.drawCircle(cx, cy, r, trackPaint)
        // 进度弧（12 点起，顺时针）
        val sweep = percent * 360f
        if (sweep > 0.01f) {
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, sweep, false, arcPaint)
        }

        // 中心文字：百分比 + 小标签（整体略上移，两行居中于环心）
        val percentText = "${(percent * 100f).roundToInt()}%"
        val percentBaseline = cy + percentPaint.textSize * 0.18f
        canvas.drawText(percentText, cx, percentBaseline, percentPaint)
        if (label.isNotEmpty()) {
            val labelBaseline = percentBaseline + labelPaint.textSize + sp(2f)
            canvas.drawText(label, cx, labelBaseline, labelPaint)
        }
    }
}
