package com.healix.app.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.ActivityPlanReviewBinding
import kotlinx.coroutines.launch

/**
 * 计划 / 训练 / 回顾（同一页三个文字 Tab，设计规范系统 §4.3 / §9.5）。
 *
 * 定位调整（UI 设计方案第八节）：**以计划为主，复盘降为辅**。
 * v4 新增「训练」Tab：周训练计划 + 「记一笔」直写 + 5 秒撤销。
 */
class PlanReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlanReviewBinding
    private lateinit var vm: PlanReviewViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlanReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = PlanReviewViewModel(HealixApp.from(this))

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { vm.refresh() }
        binding.tabPlan.setOnClickListener { selectTab(PlanTab.PLAN) }
        binding.tabTraining.setOnClickListener { selectTab(PlanTab.TRAINING) }
        binding.tabReview.setOnClickListener { selectTab(PlanTab.REVIEW) }
        binding.btnGenerate.setOnClickListener { vm.generateTraining() }
        binding.btnTrainingRetry.setOnClickListener { vm.generateTraining() }
        binding.btnUpdatePlan.setOnClickListener { vm.updatePlan() }

        selectTab(PlanTab.PLAN)
        observe()
    }

    /** v6（11.2）：二级页返回统一 in_back —— 返回页从 -22% 滑入。 */
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.in_back, R.anim.out_back)
    }

    private fun selectTab(tab: PlanTab) {
        vm.showTab(tab)
        val isPlan = tab == PlanTab.PLAN
        val isTraining = tab == PlanTab.TRAINING
        val isReview = tab == PlanTab.REVIEW

        // 选中态：text_1 + 下方 2dp accent 线；未选中：text_2 + 无底线
        binding.tabPlan.setTextColor(color(if (isPlan) R.color.text_1 else R.color.text_2))
        binding.tabTraining.setTextColor(color(if (isTraining) R.color.text_1 else R.color.text_2))
        binding.tabReview.setTextColor(color(if (isReview) R.color.text_1 else R.color.text_2))
        binding.tabPlanUnderline.visibility = if (isPlan) View.VISIBLE else View.INVISIBLE
        binding.tabTrainingUnderline.visibility = if (isTraining) View.VISIBLE else View.INVISIBLE
        binding.tabReviewUnderline.visibility = if (isReview) View.VISIBLE else View.INVISIBLE

        binding.planGroup.visibility = if (isPlan) View.VISIBLE else View.GONE
        binding.trainingGroup.visibility = if (isTraining) View.VISIBLE else View.GONE
        binding.reviewGroup.visibility = if (isReview) View.VISIBLE else View.GONE

        // 缺口行只属于计划 Tab
        binding.gapLabel.visibility = if (isPlan) View.VISIBLE else View.GONE
        binding.gapDivider.visibility = if (isPlan) View.VISIBLE else View.GONE

        if (!isTraining) UndoBar.hide(binding.undoBar)
        updateRefreshVisibility(isTraining)
    }

    /** 训练 Tab 下「刷新」只在已生成时显示（规范 §9.5）；其余 Tab 保持既有常驻。 */
    private fun updateRefreshVisibility(isTraining: Boolean) {
        binding.btnRefresh.visibility =
            if (isTraining && !vm.training.value.hasPlan) View.GONE else View.VISIBLE
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    vm.plan.collect { plan ->
                        binding.gapLabel.text = if (plan.gapLeft > 0) {
                            getString(R.string.plan_gap_label, plan.gapLeft)
                        } else {
                            getString(R.string.plan_reached)
                        }
                        renderPlanHeader(plan)
                        renderTimeline(plan)
                    }
                }

                launch {
                    vm.review.collect { r ->
                        binding.statIn.text = if (r.kcalIn > 0) r.kcalIn.toString() else "—"
                        binding.statOut.text = if (r.kcalOut > 0) r.kcalOut.toString() else "—"
                        binding.statWeight.text = if (r.weightKg > 0) trimNumber(r.weightKg) else "—"
                        binding.reviewText.text = r.content.ifBlank { getString(R.string.nodata) }
                    }
                }

                launch { vm.training.collect { renderTraining(it) } }

                // 内联撤销条：写入成功后 5 秒可撤销（规范 §9.6 / PRD §15.7）
                launch {
                    vm.undo.collect { payload ->
                        val text = getString(
                            R.string.undo_recorded,
                            payload.typeName,
                            payload.valueText,
                        )
                        UndoBar.bind(
                            container = binding.undoBar,
                            leftText = binding.undoLeft,
                            action = binding.undoAction,
                            text = text,
                            announce = getString(R.string.undo_announce, payload.typeName),
                            onUndo = { vm.undo(payload.clientEventId) },
                        )
                        // 撤销条在滚动区顶部：滚回顶部，保证「撤得回」看得见（PRD §15.7）
                        binding.contentScroll.smoothScrollTo(0, 0)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 计划 Tab 渲染（单条时间轴）
    // ------------------------------------------------------------------

    /** 计划 Tab 顶部：来源行（左）+ 更新按钮（右）+ 兜底/失败提示行。 */
    private fun renderPlanHeader(plan: PlanUiState) {
        // 更新按钮：生成中置「更新中」并禁用（与训练页「生成本周计划」一致）
        binding.btnUpdatePlan.isEnabled = !plan.updating
        binding.btnUpdatePlan.text = getString(
            if (plan.updating) R.string.plan_updating else R.string.plan_update,
        )

        // 来源行：来源名 · HH:mm 生成（无条目时不显示）
        val hasItems = plan.items.isNotEmpty()
        binding.planSourceLabel.visibility = if (hasItems) View.VISIBLE else View.GONE
        if (hasItems) {
            val srcName = if (plan.source == TrainingPlanner.SOURCE_AI) {
                getString(R.string.plan_source_ai)
            } else {
                getString(R.string.mode_simplified_local)
            }
            val time = if (plan.generatedAt > 0) hhmm(plan.generatedAt) else "--:--"
            binding.planSourceLabel.text = getString(R.string.plan_source_line, srcName, time)
        }

        // 提示行：失败 / 配额不足 / 兜底模式，取最相关的一条
        val hint = when {
            // 有旧版 → 屏幕上确实还是旧版；无旧版 → 这是刚建的本地兜底，
            // 不能说「仍显示上一次的计划」（那是谎话）
            plan.failed && plan.fromCache -> getString(R.string.plan_update_failed)
            plan.failed -> getString(R.string.plan_update_local_fallback)
            plan.quotaExhausted -> getString(R.string.quota_exhausted_short)
            plan.source == TrainingPlanner.SOURCE_FALLBACK -> getString(R.string.mode_simplified_local)
            else -> null
        }
        binding.planModeLabel.visibility = if (hint == null) View.GONE else View.VISIBLE
        if (hint != null) binding.planModeLabel.text = hint
    }

    /**
     * 计划 Tab 内容：一条按 `HH:mm` 的时间轴（吃/练/睡排在同一条轴上）。
     *
     * 渲染约定（增量设计附录 B）：`time` 为空 → 时间列与线/点 `GONE`；`meta` 复用
     * `plan_action_meta`（duration 空则「——」、why 空则不显示）；仅 `meal`/`exercise`
     * 显示「记一笔」；**最后一项** `tlLine` 置 `INVISIBLE`（不让竖线拖出屏幕）。
     */
    private fun renderTimeline(plan: PlanUiState) {
        binding.itemContainer.removeAllViews()
        if (plan.items.isEmpty()) {
            binding.planNote.text = getString(R.string.nodata)
            binding.planNote.visibility = View.VISIBLE
            return
        }
        val lastIndex = plan.items.lastIndex
        plan.items.forEachIndexed { index, item ->
            val row = layoutInflater.inflate(
                R.layout.item_plan_timeline, binding.itemContainer, false,
            )
            val time = row.findViewById<TextView>(R.id.tlTime)
            val rail = row.findViewById<View>(R.id.tlRail)
            val line = row.findViewById<View>(R.id.tlLine)
            val title = row.findViewById<TextView>(R.id.tlTitle)
            val detail = row.findViewById<TextView>(R.id.tlDetail)
            val meta = row.findViewById<TextView>(R.id.tlMeta)
            val logBtn = row.findViewById<TextView>(R.id.btnLogThis)

            // 时间列：旧数据 time 可能为空 → 时间列与线/点整块隐藏
            val hasTime = item.time.isNotBlank()
            time.text = item.time
            time.visibility = if (hasTime) View.VISIBLE else View.GONE
            rail.visibility = if (hasTime) View.VISIBLE else View.GONE

            // 最后一项的竖线不外露（保留占位，不影响对齐）
            if (index == lastIndex) line.visibility = View.INVISIBLE

            title.text = item.title
            detail.text = item.detail
            detail.visibility = if (item.detail.isBlank()) View.GONE else View.VISIBLE

            // meta：duration · why；两者都空则不占行
            val durationText = item.duration.ifBlank { "——" }
            meta.text = if (item.why.isBlank()) {
                durationText
            } else {
                getString(R.string.plan_action_meta, durationText, item.why)
            }
            meta.visibility = if (item.duration.isBlank() && item.why.isBlank()) {
                View.GONE
            } else {
                View.VISIBLE
            }

            // 「记一笔」仅对 meal / exercise 显示（sleep/habit 直写会造出假数据）
            val canLog = item.type == "meal" || item.type == "exercise"
            logBtn.visibility = if (canLog) View.VISIBLE else View.GONE
            if (canLog) logBtn.setOnClickListener { vm.logSuggestion(item) }

            binding.itemContainer.addView(row)
        }

        binding.planNote.text = plan.note
        binding.planNote.visibility = if (plan.note.isBlank()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------
    // 训练渲染（规范 §9.5）
    // ------------------------------------------------------------------

    private fun renderTraining(st: TrainingUiState) {
        if (vm.tab.value == PlanTab.TRAINING) updateRefreshVisibility(true)

        binding.trainingModeLabel.visibility =
            if (st.hasPlan && st.source == TrainingPlanner.SOURCE_FALLBACK) {
                View.VISIBLE
            } else {
                View.GONE
            }

        val plan = st.plan
        if (st.hasPlan && plan != null) {
            binding.trainingEmpty.visibility = View.GONE
            binding.trainingSummary.visibility = View.VISIBLE
            binding.trainingSummary.text = getString(
                R.string.status_train_week,
                plan.days.count { !it.isRest },
                st.completed.size,
            )
            renderTrainingDays(st, plan)

            binding.trainingNote.visibility =
                if (plan.note.isBlank()) View.GONE else View.VISIBLE
            binding.trainingNote.text = getString(R.string.training_focus, plan.note)
        } else {
            binding.trainingSummary.visibility = View.GONE
            binding.trainingContainer.removeAllViews()
            binding.trainingNote.visibility = View.GONE
            binding.trainingEmpty.visibility = View.VISIBLE

            binding.trainingIntro.text = getString(R.string.training_generate_intro, st.goalLabel)
            binding.trainingGoalFooter.text =
                getString(R.string.training_goal_footer, st.sessionsGoal)

            binding.btnGenerate.isEnabled = !st.generating
            binding.btnGenerate.text = getString(
                if (st.generating) R.string.training_generating else R.string.training_generate,
            )
            binding.btnGenerate.setTextColor(
                color(if (st.generating) R.color.text_3 else R.color.btn_primary_text),
            )
            binding.trainingGeneratingHint.visibility =
                if (st.generating) View.VISIBLE else View.GONE
            binding.trainingFailedRow.visibility =
                if (st.failed) View.VISIBLE else View.GONE
        }
    }

    private fun renderTrainingDays(st: TrainingUiState, plan: TrainingPlan) {
        binding.trainingContainer.removeAllViews()
        for (day in plan.days) {
            val row = layoutInflater.inflate(
                R.layout.item_training_day, binding.trainingContainer, false,
            )
            val root = row as LinearLayout
            val marker = row.findViewById<View>(R.id.trainingMarker)
            val dow = row.findViewById<TextView>(R.id.trainingDow)
            val title = row.findViewById<TextView>(R.id.trainingTitle)
            val items = row.findViewById<TextView>(R.id.trainingItems)
            val logBtn = row.findViewById<TextView>(R.id.btnLogDay)

            dow.text = dowLabel(day.dow)

            if (day.isRest) {
                // 休息日：48dp 单行，无「记一笔」
                marker.visibility = View.GONE
                items.visibility = View.GONE
                logBtn.visibility = View.GONE

                title.text = day.title
                title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                title.setTextColor(color(R.color.text_2))

                root.minimumHeight = dp(48)
                root.gravity = Gravity.CENTER_VERTICAL
            } else {
                marker.visibility = if (day.dow == st.todayDow) View.VISIBLE else View.INVISIBLE
                items.visibility = View.VISIBLE
                items.text = day.itemsLine()

                title.text = day.title
                title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                title.setTextColor(color(R.color.text_1))

                val recorded = day.dow in st.completed
                logBtn.visibility = View.VISIBLE
                logBtn.isEnabled = !recorded
                logBtn.isClickable = !recorded
                logBtn.text = getString(if (recorded) R.string.recorded else R.string.log_this)
                logBtn.setTextColor(color(if (recorded) R.color.text_3 else R.color.accent))
                if (!recorded) {
                    logBtn.setOnClickListener { vm.logTraining(day.dow) }
                }
            }
            binding.trainingContainer.addView(row)
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** epoch millis → 本地 `HH:mm`（24 小时制两位补零，与时间轴同口径）。 */
    private fun hhmm(ts: Long): String {
        val t = java.time.Instant.ofEpochMilli(ts)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
        return String.format(java.util.Locale.US, "%02d:%02d", t.hour, t.minute)
    }

    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
