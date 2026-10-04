package com.healix.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.ActivityStatusDetailBinding
import com.healix.app.databinding.ItemEventBinding
import com.healix.app.databinding.ItemIllnessTimelineBinding
import com.healix.app.databinding.RowSettingValueBinding
import com.healix.app.notify.EventText
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * 状态详情页（设计规范 §9.4）。
 *
 * 入口：首页状态行（摘要态默认「运动」段，信号态默认「身体」段，由 Intent extra 决定）。
 * 返回：页头 ← 回首页。
 *
 * ⚠️ **本页只读 `body_signals`，不负责写入扫描** —— 规则求值与落库由首页负责，
 * 这里绝不重复实现，否则「同一规则两处判定」必然漂移。
 *
 * ⚠️ **体重段安全条款（PRD 规则 H8）**：`BMI < 18.5` 且近 14 日体重停滞/下降时，
 * 体重段追加的就医引导里**不得出现「多吃 / 加餐 / 上调摄入」字样** ——
 * 那正是本条要避免的误导。引导样式与普通正文一致，不做成"警告条"。
 */
class StatusDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatusDetailBinding
    private lateinit var vm: StatusDetailViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatusDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = StatusDetailViewModel(HealixApp.from(this))

        binding.btnBack.setOnClickListener { finish() }
        binding.tabExercise.setOnClickListener { selectTab(TAB_EXERCISE) }
        binding.tabSleep.setOnClickListener { selectTab(TAB_SLEEP) }
        binding.tabWeight.setOnClickListener { selectTab(TAB_WEIGHT) }
        binding.tabBody.setOnClickListener { selectTab(TAB_BODY) }

        // 「查看本周训练计划 ›」→ 计划页（训练 Tab 由并行任务加入，本页不传任何 extra）
        binding.exerciseViewPlan.setOnClickListener {
            startActivity(Intent(this, PlanReviewActivity::class.java))
        }
        // 「去记录」→ 回首页去记一笔
        binding.weightGoRecord.setOnClickListener { finish() }

        selectTab(normalizeTab(intent.getStringExtra(EXTRA_DEFAULT_TAB)))
        observe()
    }

    /** v6（11.2）：二级页返回统一 in_back —— 返回页从 -22% 滑入。 */
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.in_back, R.anim.out_back)
    }

    // ── Tab ──────────────────────────────────────────────────────────

    private fun normalizeTab(tab: String?): String = when (tab) {
        TAB_SLEEP -> TAB_SLEEP
        TAB_WEIGHT -> TAB_WEIGHT
        TAB_BODY -> TAB_BODY
        else -> TAB_EXERCISE
    }

    private fun selectTab(tab: String) {
        val text1 = ContextCompat.getColor(this, R.color.text_1)
        val text2 = ContextCompat.getColor(this, R.color.text_2)

        // 选中：text_1 + 下方 2dp accent 线；未选中：text_2 + 无底线
        binding.tabExercise.setTextColor(if (tab == TAB_EXERCISE) text1 else text2)
        binding.tabSleep.setTextColor(if (tab == TAB_SLEEP) text1 else text2)
        binding.tabWeight.setTextColor(if (tab == TAB_WEIGHT) text1 else text2)
        binding.tabBody.setTextColor(if (tab == TAB_BODY) text1 else text2)

        binding.tabExerciseUnderline.visibility = underline(tab == TAB_EXERCISE)
        binding.tabSleepUnderline.visibility = underline(tab == TAB_SLEEP)
        binding.tabWeightUnderline.visibility = underline(tab == TAB_WEIGHT)
        binding.tabBodyUnderline.visibility = underline(tab == TAB_BODY)

        binding.exerciseGroup.visibility = if (tab == TAB_EXERCISE) View.VISIBLE else View.GONE
        binding.sleepGroup.visibility = if (tab == TAB_SLEEP) View.VISIBLE else View.GONE
        binding.weightGroup.visibility = if (tab == TAB_WEIGHT) View.VISIBLE else View.GONE
        binding.bodyGroup.visibility = if (tab == TAB_BODY) View.VISIBLE else View.GONE
    }

    private fun underline(visible: Boolean): Int =
        if (visible) View.VISIBLE else View.INVISIBLE

    // ── 观察 ─────────────────────────────────────────────────────────

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { ui ->
                    renderExercise(ui.exercise)
                    renderSleep(ui.sleep)
                    renderWeight(ui.weight)
                    renderBody(ui.body)
                }
            }
        }
    }

    // ── 运动段 ───────────────────────────────────────────────────────

    private fun renderExercise(section: ExerciseSection) {
        binding.exerciseProgress.text = getString(
            R.string.status_train_progress,
            section.sessionsDone,
            section.sessionsGoal,
            section.minutesDone,
            section.minutesGoal,
        )

        binding.exerciseRecentContainer.removeAllViews()
        val hasRecent = section.recent.isNotEmpty()
        binding.exerciseRecentTitle.visibility = if (hasRecent) View.VISIBLE else View.GONE
        binding.exerciseEmpty.visibility = if (hasRecent) View.GONE else View.VISIBLE

        section.recent.forEachIndexed { index, event ->
            val row = ItemEventBinding.inflate(
                layoutInflater, binding.exerciseRecentContainer, false,
            )
            // 「最近训练」清单：圆点固定用 exercise 色，只读（不接编辑路径）
            row.dot.background.setTint(EventText.typeColor(this, TYPE_EXERCISE))
            row.typeLabel.text = EventText.typeName(this, TYPE_EXERCISE)
            row.timeLabel.text = HealixDate.timeLabel(event.ts)
            row.bodyText.text = event.rawText
            val summary = EventText.summary(this, event)
            row.summaryText.text = summary
            row.summaryText.visibility = if (summary.isNullOrEmpty()) View.GONE else View.VISIBLE
            row.pendingText.visibility = View.GONE
            row.errorText.visibility = View.GONE
            row.divider.visibility =
                if (index == section.recent.size - 1) View.GONE else View.VISIBLE
            row.root.isClickable = false
            row.root.isFocusable = false
            binding.exerciseRecentContainer.addView(row.root)
        }
    }

    // ── 睡眠段 ───────────────────────────────────────────────────────

    private fun renderSleep(section: SleepSection) {
        binding.sleepChart.submit(section.values)
        if (section.values.size >= MIN_CHART_POINTS) {
            val minV = section.values.minOrNull() ?: 0.0
            val maxV = section.values.maxOrNull() ?: 0.0
            binding.sleepChart.setMeta(
                num1(minV),
                num1(maxV),
                getString(R.string.chart_window_7),
                getString(R.string.unit_hour_plain),
                getString(R.string.chart_nodata),
            )
            binding.sleepChart.contentDescription = getString(
                R.string.chart_desc,
                CHART_WINDOW_7,
                getString(R.string.type_sleep),
                num1(minV),
                num1(maxV),
                getString(R.string.unit_hour_plain),
                trendText(section.values.first(), section.values.last()),
            )
        } else {
            binding.sleepChart.setMeta("", "", "", "", getString(R.string.chart_nodata))
            binding.sleepChart.contentDescription = getString(R.string.chart_desc_nodata)
        }

        if (section.avg > 0.0) {
            binding.sleepAvg.visibility = View.VISIBLE
            binding.sleepAvg.text =
                getString(R.string.status_sleep_avg, num1(section.avg), num1(section.goal))
        } else {
            binding.sleepAvg.visibility = View.GONE
        }

        val diff = section.diffVsPrev
        if (diff == null) {
            binding.sleepDiff.visibility = View.GONE
        } else {
            binding.sleepDiff.visibility = View.VISIBLE
            val magnitude = abs(diff)
            binding.sleepDiff.text = when {
                magnitude < TREND_EPSILON -> getString(R.string.status_sleep_flat)
                diff > 0 -> getString(R.string.status_sleep_more, num1(magnitude))
                else -> getString(R.string.status_sleep_less, num1(magnitude))
            }
        }
    }

    // ── 体重段 ───────────────────────────────────────────────────────

    private fun renderWeight(section: WeightSection) {
        binding.weightChart.submit(section.values)
        val enough = section.values.size >= MIN_CHART_POINTS
        val hidden = section.weightHidden

        if (!enough) {
            // 数据不足：折线位置显示占位，另给「去记录」入口。
            // 从未记录过 → 「先记录一次体重」；已有少量记录 → 「记录不足 3 天」。
            val placeholder = if (section.hasEverRecorded) {
                getString(R.string.chart_nodata)
            } else {
                getString(R.string.status_weight_need_first)
            }
            binding.weightChart.setMeta("", "", "", "", placeholder)
            binding.weightChart.contentDescription = getString(R.string.chart_desc_nodata)
            binding.weightCurrent.visibility = View.GONE
            binding.weightDelta.visibility = View.GONE
            binding.weightBmi.visibility = View.GONE
            binding.weightGoRecord.visibility = View.VISIBLE
        } else if (hidden) {
            // 隐私开关（§9.7 ④）：折线本体保留（形状不泄露数值），
            // 范围标注去掉数字，读屏换成无数字的 chart_desc_hidden。
            binding.weightChart.setMeta(
                "",
                "",
                getString(R.string.chart_window_30),
                getString(R.string.unit_kg_plain),
                getString(R.string.chart_nodata),
            )
            binding.weightChart.contentDescription = getString(
                R.string.chart_desc_hidden,
                CHART_WINDOW_30,
                getString(R.string.type_body),
            )
            // 数字行整行 GONE，不留空位，且绝不出现 *** / 「已隐藏」提示
            binding.weightCurrent.visibility = View.GONE
            binding.weightDelta.visibility = View.GONE
            binding.weightBmi.visibility = View.GONE
            binding.weightGoRecord.visibility = View.GONE
        } else {
            val minV = section.values.minOrNull() ?: 0.0
            val maxV = section.values.maxOrNull() ?: 0.0
            binding.weightChart.setMeta(
                num1(minV),
                num1(maxV),
                getString(R.string.chart_window_30),
                getString(R.string.unit_kg_plain),
                getString(R.string.chart_nodata),
            )
            binding.weightChart.contentDescription = getString(
                R.string.chart_desc,
                CHART_WINDOW_30,
                getString(R.string.type_body),
                num1(minV),
                num1(maxV),
                getString(R.string.unit_kg_plain),
                trendText(section.values.first(), section.values.last()),
            )
            binding.weightGoRecord.visibility = View.GONE

            val goalText = if (section.goal > 0.0) num1(section.goal) else DASH
            binding.weightCurrent.visibility = View.VISIBLE
            binding.weightCurrent.text =
                getString(R.string.status_weight_current, num1(section.current), goalText)

            val delta = section.delta14
            if (delta == null) {
                binding.weightDelta.visibility = View.GONE
            } else {
                binding.weightDelta.visibility = View.VISIBLE
                val magnitude = abs(delta)
                binding.weightDelta.text = when {
                    magnitude < TREND_EPSILON -> getString(R.string.status_weight_flat)
                    delta > 0 -> getString(R.string.status_weight_up, num1(magnitude))
                    else -> getString(R.string.status_weight_down, num1(magnitude))
                }
            }

            if (section.bmi > 0.0) {
                binding.weightBmi.visibility = View.VISIBLE
                binding.weightBmi.text = bmiLine(section.bmi)
            } else {
                binding.weightBmi.visibility = View.GONE
            }
        }

        // H8 就医引导：不是警告条，是引导 —— 不加线、不加底色、不加图标。
        // **安全条款优先于隐私开关**：文案本身不含具体数字，任何时候都按条件独立显示，
        // 绝不因隐藏体重而关掉。
        binding.bmiGuide.visibility = if (section.showBmiGuide) View.VISIBLE else View.GONE
    }

    /** `BMI 18.4 · 偏低`；「偏低」用 text_2（偏低不是失败，不用 negative）。 */
    private fun bmiLine(bmi: Double): CharSequence {
        val label = getString(bmiLabelRes(bmi))
        val full = getString(R.string.status_bmi, num1(bmi), label)
        val spannable = SpannableString(full)
        val start = full.length - label.length
        spannable.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_2)),
            start,
            full.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return spannable
    }

    /** 中国标准：<18.5 偏低 / 18.5–23.9 正常 / 24–27.9 超重 / ≥28 肥胖。 */
    private fun bmiLabelRes(bmi: Double): Int = when {
        bmi < 18.5 -> R.string.bmi_low
        bmi < 24.0 -> R.string.bmi_normal
        bmi < 28.0 -> R.string.bmi_over
        else -> R.string.bmi_obese
    }

    // ── 身体段 ───────────────────────────────────────────────────────

    private fun renderBody(section: BodySection) {
        val hasContent = section.signals.isNotEmpty() ||
            section.timeline.isNotEmpty() ||
            section.reminders.isNotEmpty()
        // 唯一的空状态页：用户主动点进来，静默会像加载失败
        binding.bodyEmpty.visibility = if (hasContent) View.GONE else View.VISIBLE

        // ① 需要留意：完整三段式（_full）
        val hasSignals = section.signals.isNotEmpty()
        binding.signalTitle.visibility = if (hasSignals) View.VISIBLE else View.GONE
        binding.signalContainer.visibility = if (hasSignals) View.VISIBLE else View.GONE
        binding.signalContainer.removeAllViews()
        section.signals.forEachIndexed { index, signal ->
            // 落库时 title 存短文案、detail 存完整三段式；取 detail 优先，缺失才回落 title
            val text = signal.detail?.takeIf { it.isNotBlank() } ?: signal.title
            binding.signalContainer.addView(
                signalText(text, topMarginDp = if (index == 0) 0 else 12),
            )
        }

        // ② 病程时间线
        val timeline = section.timeline
        val hasTimeline = timeline.isNotEmpty()
        binding.illnessDayHeader.visibility = if (hasTimeline) View.VISIBLE else View.GONE
        binding.illnessTimelineContainer.visibility = if (hasTimeline) View.VISIBLE else View.GONE
        binding.illnessTimelineContainer.removeAllViews()
        if (hasTimeline) {
            binding.illnessDayHeader.text =
                getString(R.string.status_illness_day, section.illnessDay)
            timeline.forEachIndexed { index, row ->
                val item = ItemIllnessTimelineBinding.inflate(
                    layoutInflater, binding.illnessTimelineContainer, false,
                )
                item.illnessIndex.text = row.index.toString().padStart(2, '0')
                item.illnessDate.text = row.date
                item.illnessText.text = row.text
                item.illnessDivider.visibility =
                    if (index == timeline.size - 1) View.GONE else View.VISIBLE
                binding.illnessTimelineContainer.addView(item.root)
            }
        }

        // 病程超 3 天：在时间线下方给就医阈值引导（只给行动，不给诊断、不打分）
        binding.illnessGuide.visibility =
            if (hasTimeline && section.illnessDay > ILLNESS_VISIT_DAY) View.VISIBLE else View.GONE

        // ③ 提醒清单（复用 row_setting_value.xml，零新增布局）
        val reminders = section.reminders
        val hasReminders = reminders.isNotEmpty()
        binding.reminderTitle.visibility = if (hasReminders) View.VISIBLE else View.GONE
        binding.reminderContainer.visibility = if (hasReminders) View.VISIBLE else View.GONE
        binding.reminderContainer.removeAllViews()
        for (reminder in reminders) {
            val row = RowSettingValueBinding.inflate(
                layoutInflater, binding.reminderContainer, false,
            )
            row.label.text = reminder.name
            row.value.text = getString(R.string.status_reminder_next, dateLabel(reminder.nextDueAt))
            row.chevron.visibility = View.GONE
            row.root.isClickable = false
            row.root.isFocusable = false
            binding.reminderContainer.addView(row.root)
        }
    }

    /** 程序化构造一条 15sp text_1 正文（与 `Text.Body` 同规格：行高 24sp）。 */
    private fun signalText(text: String, topMarginDp: Int): TextView {
        val tv = TextView(this)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_1))
        tv.typeface = Typeface.DEFAULT
        tv.setLineSpacing(spToPx(6f), 1f)
        tv.text = text
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dpToPx(topMarginDp.toFloat())
        tv.layoutParams = lp
        return tv
    }

    // ── 工具 ─────────────────────────────────────────────────────────

    /** `chart_desc` 的趋势尾巴：上升 X / 下降 X / 持平。 */
    private fun trendText(first: Double, last: Double): String {
        val diff = last - first
        val magnitude = abs(diff)
        return when {
            magnitude < TREND_EPSILON -> getString(R.string.chart_trend_flat)
            diff > 0 -> getString(R.string.chart_trend_up, num1(magnitude))
            else -> getString(R.string.chart_trend_down, num1(magnitude))
        }
    }

    /** 保留 1 位小数，固定 `Locale.US` 保证小数点一致。 */
    private fun num1(value: Double): String = String.format(Locale.US, "%.1f", value)

    /** epoch millis → 本地日期 `yyyy-MM-dd`。 */
    private fun dateLabel(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun spToPx(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun dpToPx(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
            .toInt()

    companion object {
        /** 入口段：`exercise | sleep | weight | body`。从信号态进来传 `body`。 */
        const val EXTRA_DEFAULT_TAB = "default_tab"

        const val TAB_EXERCISE = "exercise"
        const val TAB_SLEEP = "sleep"
        const val TAB_WEIGHT = "weight"
        const val TAB_BODY = "body"

        private const val TYPE_EXERCISE = "exercise"

        /** 规范 §9.4：病程超过 3 天时给就医阈值引导（只给行动，不给诊断、不打分）。 */
        private const val ILLNESS_VISIT_DAY = 3

        /** 折线至少 3 点才画（§9.3）。 */
        private const val MIN_CHART_POINTS = 3
        private const val CHART_WINDOW_7 = 7
        private const val CHART_WINDOW_30 = 30

        /** 小于半个最小刻度即视为持平（保留 1 位后为 0.0）。 */
        private const val TREND_EPSILON = 0.05

        private const val DASH = "—"
    }
}
