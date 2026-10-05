package com.healix.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.healix.app.R
import com.healix.app.databinding.FragmentPlanReviewBinding
import kotlinx.coroutines.launch

/**
 * 计划 / 回顾（v8 需求 7 重构）。
 *
 * ── 与旧版的差别 ───────────────────────────────────────────────────
 * - **两个可见 Tab**：计划（= 本周训练 + 今日计划的**统一时间轴**）| 回顾。
 *   原「训练」Tab 与手动「更新」「刷新」主按钮一并去除。
 * - **无手动刷新**：数据变化经 Room Flow → [PlanReviewViewModel] 本地重算（0 AI）；
 *   AI 重排由 VM 后台自动触发（≤1 次/日），页顶只留一行「正在按你的最新数据重排…」提示。
 * - **训练空态**：整周计划仍需一个生成入口 → 轴尾一条**低调文字链**（非主按钮）。
 *
 * v8 T03：由 `PlanReviewActivity` 迁为宿主 [MainActivity] 内的二级页 Fragment
 * （[NavHost] 路由），进出零窗口转场。
 *
 * ⚠️ VM 用 `ViewModelProvider(this)`（**Fragment 作用域**）——随本 Fragment 销毁即
 *    `onCleared()`，`viewModelScope` 取消在途自动重排（避免退后台空跑 20s 请求）。
 *    ⚠️ **不能**用 `by viewModels()`：那是 `fragment-ktx` 的扩展，本模块未引入该依赖
 *    （只有 `lifecycle-viewmodel-ktx`），本地无 JDK 编译、漏了要到 CI 才炸。
 *    二级页 `replace + addToBackStack` 回退时会**重建**本 Fragment，故渲染完全
 *    依赖 [PlanReviewViewModel.plan] 的当前值（无状态重建，不做滚动位置保持）。
 */
class PlanReviewFragment : Fragment() {

    private var _binding: FragmentPlanReviewBinding? = null
    private val binding get() = _binding!!

    private val vm: PlanReviewViewModel by lazy {
        ViewModelProvider(this)[PlanReviewViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPlanReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { NavHost.back(requireContext()) }
        binding.tabPlan.setOnClickListener { selectTab(PlanTab.PLAN) }
        binding.tabReview.setOnClickListener { selectTab(PlanTab.REVIEW) }
        binding.btnGenerateWeek.setOnClickListener { vm.generateTraining() }
        binding.btnGenerateToday.setOnClickListener { vm.generateTodayPlan() }

        selectTab(PlanTab.PLAN)
        observe()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun selectTab(tab: PlanTab) {
        vm.showTab(tab)
        val isPlan = tab == PlanTab.PLAN

        // 选中态：text_1 + 下方 2dp accent 线；未选中：text_2 + 无底线
        binding.tabPlan.setTextColor(color(if (isPlan) R.color.text_1 else R.color.text_2))
        binding.tabReview.setTextColor(color(if (isPlan) R.color.text_2 else R.color.text_1))
        binding.tabPlanUnderline.visibility = if (isPlan) View.VISIBLE else View.INVISIBLE
        binding.tabReviewUnderline.visibility = if (isPlan) View.INVISIBLE else View.VISIBLE

        binding.planGroup.visibility = if (isPlan) View.VISIBLE else View.GONE
        binding.reviewGroup.visibility = if (isPlan) View.GONE else View.VISIBLE

        // 缺口行只属于计划 Tab
        binding.gapLabel.visibility = if (isPlan) View.VISIBLE else View.GONE
        binding.gapDivider.visibility = if (isPlan) View.VISIBLE else View.GONE

        if (!isPlan) UndoBar.hide(binding.undoBar)
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    vm.plan.collect { p ->
                        binding.gapLabel.text = if (p.gapLeft > 0) {
                            getString(R.string.plan_gap_label, p.gapLeft)
                        } else {
                            getString(R.string.plan_reached)
                        }
                        renderPlanHeader(p)
                        renderTrainingSummary(p)
                        renderTimeline(p)
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
    // 计划 Tab 渲染（统一时间轴）
    // ------------------------------------------------------------------

    /** 页顶：来源行 + 一行状态提示（重排中 / 失败 / 配额 / 兜底）。 */
    private fun renderPlanHeader(p: PlanUiState) {
        // 来源行：来源名 · HH:mm 生成（今日无计划条目时不显示）
        val hasPlanItems = p.entries.any { it.source == TimelineSource.PLAN }
        binding.planSourceLabel.visibility = if (hasPlanItems) View.VISIBLE else View.GONE
        if (hasPlanItems) {
            // ⚠️ fallback（本地兜底）时用 plan_source_estimated（"本地简化 · 热量为估算"），
            //    让用户看得出 kcal 是**估算**；不复用 mode_simplified_local（提示行共用）。
            val srcName = if (p.source == TrainingPlanner.SOURCE_AI) {
                getString(R.string.plan_source_ai)
            } else {
                getString(R.string.plan_source_estimated)
            }
            val time = if (p.generatedAt > 0) hhmm(p.generatedAt) else "--:--"
            binding.planSourceLabel.text = getString(R.string.plan_source_line, srcName, time)
        }

        // 提示行：取最相关的一条
        val hint = when {
            p.updating -> getString(R.string.plan_auto_updating)
            // 有旧版 → 屏幕上确实还是旧版；无旧版 → 这是刚建的本地兜底，
            // 不能说「仍显示上一次的计划」（那是谎话）
            p.failed && p.fromCache -> getString(R.string.plan_update_failed)
            p.failed -> getString(R.string.plan_update_local_fallback)
            p.quotaExhausted -> getString(R.string.quota_exhausted_short)
            p.source == TrainingPlanner.SOURCE_FALLBACK -> getString(R.string.mode_simplified_local)
            else -> null
        }
        binding.planModeLabel.visibility = if (hint == null) View.GONE else View.VISIBLE
        if (hint != null) binding.planModeLabel.text = hint
    }

    /** 本周训练汇总行 + 训练空态的低调生成入口（互斥）。 */
    private fun renderTrainingSummary(p: PlanUiState) {
        binding.trainingSummary.visibility = if (p.hasTraining) View.VISIBLE else View.GONE
        if (p.hasTraining) {
            binding.trainingSummary.text =
                getString(R.string.status_train_week, p.trainingSessions, p.trainingDone)
        }

        // 本周训练重点（原「训练」Tab 页脚）：仅在已生成且有内容时显示
        val focus = p.trainingFocus
        binding.trainingFocus.visibility =
            if (p.hasTraining && focus.isNotBlank()) View.VISIBLE else View.GONE
        if (p.hasTraining && focus.isNotBlank()) {
            binding.trainingFocus.text = getString(R.string.training_focus, focus)
        }

        binding.trainingGenerateRow.visibility = if (p.hasTraining) View.GONE else View.VISIBLE
        if (p.hasTraining) return

        binding.trainingIntro.text = getString(R.string.training_generate_intro, p.goalLabel)
        binding.trainingGoalFooter.text = getString(R.string.training_goal_footer, p.sessionsGoal)
        binding.trainingFailed.visibility = if (p.trainingFailed) View.VISIBLE else View.GONE

        binding.btnGenerateWeek.isEnabled = !p.generatingTraining
        binding.btnGenerateWeek.isClickable = !p.generatingTraining
        binding.btnGenerateWeek.text = getString(
            when {
                p.generatingTraining -> R.string.training_generating
                p.trainingFailed -> R.string.retry
                else -> R.string.training_generate
            },
        )
        binding.btnGenerateWeek.setTextColor(
            color(if (p.generatingTraining) R.color.text_3 else R.color.accent),
        )
    }

    /**
     * 统一时间轴：按 `dayIndex` 分组，每组前插一条「日头」（今天 / 明天 / `M月D日 周X`）。
     *
     * 渲染约定：`timeLabel` 为空（训练日 / 认不出时段的锚点）→ 时间列 `INVISIBLE`
     * （保留 52dp 对齐，不塌陷）；每天**最后一条**的竖线置 `INVISIBLE`（不让竖线拖出
     * 组外）。「记一笔」由条目 `canLog` / `done` 驱动：可点（accent）/ 已记录（text_3
     * 禁用）/ 无（隐藏 —— 明天的锚点走这一支，它按设计不可记）。
     *
     * 一条都没有时走空态入口（问题 3 方案 C）：`今天还没有计划` + `生成今日计划`。
     */
    private fun renderTimeline(p: PlanUiState) {
        binding.itemContainer.removeAllViews()

        if (p.entries.isEmpty()) {
            binding.planEmptyRow.visibility = View.VISIBLE
            binding.planNote.visibility = View.GONE
            binding.btnGenerateToday.isEnabled = !p.generatingToday
            binding.btnGenerateToday.isClickable = !p.generatingToday
            binding.btnGenerateToday.text = getString(
                if (p.generatingToday) R.string.plan_generating else R.string.plan_generate_today,
            )
            binding.btnGenerateToday.setTextColor(
                color(if (p.generatingToday) R.color.text_3 else R.color.accent),
            )
            return
        }
        binding.planEmptyRow.visibility = View.GONE

        val lastIndex = p.entries.lastIndex
        var lastDay = -1
        p.entries.forEachIndexed { index, entry ->
            if (entry.dayIndex != lastDay) {
                lastDay = entry.dayIndex
                binding.itemContainer.addView(dayHeader(entry.dayIndex, p))
            }
            val isDayEnd = index == lastIndex || p.entries[index + 1].dayIndex != entry.dayIndex
            binding.itemContainer.addView(entryRow(entry, isDayEnd))
        }

        binding.planNote.text = p.note
        binding.planNote.visibility = if (p.note.isBlank()) View.GONE else View.VISIBLE
    }

    /**
     * 日头：今天 / 明天 / `M月D日 周X`。
     *
     * ⚠️ 文案由 [PlanReviewViewModel] 统一格式化（`PlanUiState.dayLabels`）——三档口径
     *    只此一处；列表缺失（日界线未读到）时回落旧的「周X · 今天」。
     */
    private fun dayHeader(dayIndex: Int, p: PlanUiState): View {
        val v = layoutInflater.inflate(R.layout.item_timeline_day, binding.itemContainer, false)
        val dow = dayIndex + 1
        val label = p.dayLabels.getOrNull(dayIndex) ?: run {
            val name = dowLabel(dow)
            if (dow == p.todayDow) getString(R.string.timeline_today_label, name) else name
        }
        v.findViewById<TextView>(R.id.dayLabel).text = label
        return v
    }

    /** 单条时间轴行（复用 `item_plan_timeline.xml`）。 */
    private fun entryRow(entry: TimelineEntry, isDayEnd: Boolean): View {
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

        // 时间列：训练日 timeLabel 为空 → INVISIBLE（保留列宽，保证竖线/圆点跨行对齐）
        time.text = entry.timeLabel
        time.visibility = if (entry.timeLabel.isNotBlank()) View.VISIBLE else View.INVISIBLE
        rail.visibility = View.VISIBLE
        // 每天最后一行的竖线不外露（圆点保留，不影响对齐）
        if (isDayEnd) line.visibility = View.INVISIBLE

        title.text = entry.title
        detail.text = entry.detail
        detail.visibility = if (entry.detail.isBlank()) View.GONE else View.VISIBLE

        meta.text = entry.meta
        meta.visibility = if (entry.meta.isBlank()) View.GONE else View.VISIBLE

        when {
            entry.canLog -> {
                logBtn.visibility = View.VISIBLE
                logBtn.isEnabled = true
                logBtn.isClickable = true
                logBtn.text = getString(R.string.log_this)
                logBtn.setTextColor(color(R.color.accent))
                logBtn.setOnClickListener { onLog(entry) }
            }
            entry.done -> {
                logBtn.visibility = View.VISIBLE
                logBtn.isEnabled = false
                logBtn.isClickable = false
                logBtn.text = getString(R.string.recorded)
                logBtn.setTextColor(color(R.color.text_3))
            }
            else -> logBtn.visibility = View.GONE
        }
        return row
    }

    /** 「记一笔」按来源分流：计划条目 → 直写建议；训练日 → 按计划原文直写。 */
    private fun onLog(entry: TimelineEntry) {
        when (entry.source) {
            TimelineSource.TRAINING -> vm.logTraining(entry.dayIndex + 1)
            else -> vm.logSuggestion(entry)
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun color(resId: Int): Int = ContextCompat.getColor(requireContext(), resId)

    /** epoch millis → 本地 `HH:mm`（24 小时制两位补零，与时间轴同口径）。 */
    private fun hhmm(ts: Long): String {
        val t = java.time.Instant.ofEpochMilli(ts)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
        return String.format(java.util.Locale.US, "%02d:%02d", t.hour, t.minute)
    }

    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    // ⚠️ 星期短名用包级 `dowLabel()`（TrainingPlanner.kt 顶层，CLDR 本地化）——
    //    本类**不得**再定义同名成员，否则成员优先会遮蔽它、且是一份硬编码中文的私本。
}
