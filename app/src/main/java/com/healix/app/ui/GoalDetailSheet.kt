package com.healix.app.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.healix.app.R
import com.healix.app.databinding.SheetGoalDetailBinding
import com.healix.app.db.GoalMetrics
import kotlinx.coroutines.launch

/**
 * v8 问题 4：记录页目标区的**半屏详情弹层**。
 *
 * 目标区被压到 ≈142dp 后，图表细节全部下沉到这里：
 * - **次目标视角**（`TAB_TRAIN` / `TAB_SLEEP` / `TAB_WEIGHT`）：三选一文字 Tab +
 *   大折线（180dp）+「当前 / 目标」行；
 * - **盈余视角**（`TAB_GAP`）：36sp 大数字 + 2dp 进度线 + 「摄入 · 消耗 · 目标」行
 *   —— 记录页「今日盈余」单行点进来的就是这一支。
 *
 * 交互（规范 G7）：
 * - 关闭三方式：下拖 / 点遮罩 / 右上「关闭」（**不用 X 图标**）；
 * - 弹层内切换 Tab **只换折线与数值，不关闭**；
 * - 「改目标 ›」与「添加其他目标」都落在设置页「目标」栏 ——
 *   数值目标的唯一归属地（`fragment_settings.xml` 的动态目标行）。
 *
 * ⚠️ 与规范 §① 的一处**有意偏离**（记录在案）：规范写「`改目标 ›` → GoalSetupSheet
 *    编辑态，定位该 metric」，但 [GoalSetupSheet] 只能编辑**主目标模式 + 目标体重**，
 *    不存在"定位到睡眠/训练"的能力。所以本弹层统一去设置页「目标」栏 ——
 *    §②「所有可增删/可改的数值目标唯一归属」这条更上位，且不会出现"点了改目标却改不到"。
 *    主目标行自己的「去调整 ›」才开 GoalSetupSheet 编辑态（见 `RecordFragment`）。
 *
 * 数据全部来自宿主 Activity 作用域的 [MainViewModel]（三卡与弹层同源，不另开订阅）。
 *
 * 用法：`GoalDetailSheet.newInstance(GoalDetailSheet.TAB_SLEEP).show(childFragmentManager, TAG)`。
 */
class GoalDetailSheet : BottomSheetDialogFragment() {

    private var _binding: SheetGoalDetailBinding? = null
    private val binding get() = _binding!!

    private val vm: MainViewModel by lazy {
        ViewModelProvider(requireActivity())[MainViewModel::class.java]
    }

    /** 当前选中的 Tab（[TAB_TRAIN] / [TAB_SLEEP] / [TAB_WEIGHT] / [TAB_GAP]）。 */
    private var currentTab: Int = TAB_TRAIN

    /** 隐私：体重本次会话内是否已被「显示一次」放行（关闭弹层即失效）。 */
    private var weightRevealedOnce: Boolean = false

    /** 最近一次收到的数据快照，切 Tab / 点「显示一次」时就地重渲染，不必等下一次 Flow 发射。 */
    private var lastTrain: TrainProgress = TrainProgress(0, 0)
    private var lastTrainSeries: List<Double> = emptyList()
    private var lastSleep: List<Double> = emptyList()
    private var lastWeight: List<Double> = emptyList()
    private var lastTargets: Map<String, Double> = emptyMap()
    private var lastHideWeight: Boolean = false
    private var lastGap: MainSummary = MainSummary()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetGoalDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentTab = requireArguments().getInt(ARG_TAB, TAB_TRAIN)

        // 下拖关闭（GrabberLayout 已排除 input / button 上的误拖）
        binding.root.onDragDismiss = { dismiss() }
        binding.btnClose.setOnClickListener { dismiss() }
        binding.tabTrain.setOnClickListener { switchTab(TAB_TRAIN) }
        binding.tabSleep.setOnClickListener { switchTab(TAB_SLEEP) }
        binding.tabWeight.setOnClickListener { switchTab(TAB_WEIGHT) }
        binding.btnEditGoal.setOnClickListener { openGoalSettings() }
        binding.btnAddOther.setOnClickListener { openGoalSettings() }
        binding.detailShowOnce.setOnClickListener {
            weightRevealedOnce = true
            renderMetric()
        }

        observe()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 点遮罩关闭时也走同一出口（下拖由 [onDragDismiss] 覆盖）。 */
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        weightRevealedOnce = false
    }

    // ── 数据 ──────────────────────────────────────────────────────────

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.trainProgress.collect { lastTrain = it; if (currentTab == TAB_TRAIN) renderMetric() }
                }
                launch {
                    vm.trainSeries.collect { lastTrainSeries = it; if (currentTab == TAB_TRAIN) renderMetric() }
                }
                launch {
                    vm.sleepSeries.collect { lastSleep = it; if (currentTab == TAB_SLEEP) renderMetric() }
                }
                launch {
                    vm.weightSeries.collect { lastWeight = it; if (currentTab == TAB_WEIGHT) renderMetric() }
                }
                launch {
                    vm.goalTargets.collect { lastTargets = it; renderMetric() }
                }
                launch {
                    vm.hideWeight.collect { lastHideWeight = it; renderMetric() }
                }
                launch {
                    vm.summary.collect { lastGap = it; if (currentTab == TAB_GAP) renderGap() }
                }
            }
        }
    }

    // ── 渲染 ──────────────────────────────────────────────────────────

    private fun switchTab(tab: Int) {
        currentTab = tab
        render()
    }

    /** 全量重渲染：按当前 Tab 决定显示哪一支内容，并刷新 Tab 选中态。 */
    private fun render() {
        val gapMode = currentTab == TAB_GAP
        binding.metricContent.visibility = if (gapMode) View.GONE else View.VISIBLE
        binding.gapContent.visibility = if (gapMode) View.VISIBLE else View.GONE

        renderTabSelection()
        if (gapMode) renderGap() else renderMetric()
    }

    /**
     * Tab 选中态：选中 `text_1` + 2dp accent 下划线；未选中 `text_2`（规范 G6）。
     * 字重均为 500（`Text.Button`），**不靠加粗区分**。
     */
    private fun renderTabSelection() {
        // 盈余视角没有 Tab（三卡只有一个「今日盈余」行），下划线整体隐藏
        val showTabs = currentTab != TAB_GAP
        binding.detailTabRow.visibility = if (showTabs) View.VISIBLE else View.GONE
        reveal(binding.tabTrainUnderline, showTabs && currentTab == TAB_TRAIN)
        reveal(binding.tabSleepUnderline, showTabs && currentTab == TAB_SLEEP)
        reveal(binding.tabWeightUnderline, showTabs && currentTab == TAB_WEIGHT)
        tint(binding.tabTrain, currentTab == TAB_TRAIN)
        tint(binding.tabSleep, currentTab == TAB_SLEEP)
        tint(binding.tabWeight, currentTab == TAB_WEIGHT)
    }

    private fun reveal(view: View, on: Boolean) {
        view.visibility = if (on) View.VISIBLE else View.INVISIBLE
    }

    private fun tint(view: android.widget.TextView, selected: Boolean) {
        view.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (selected) R.color.text_1 else R.color.text_2,
            ),
        )
    }

    /** 次目标视角：大折线 + 「当前 / 目标」（体重受隐私开关约束）。 */
    private fun renderMetric() {
        if (_binding == null || currentTab == TAB_GAP) return

        val masked = currentTab == TAB_WEIGHT && lastHideWeight && !weightRevealedOnce
        val series = when (currentTab) {
            TAB_SLEEP -> lastSleep
            TAB_WEIGHT -> lastWeight
            // 训练用「周一 → 今天 的累计完成数」——与记录页三卡①的迷你图**同源同序列**，
            // 不在这里另造一条"0 → done"的伪折线（两张图必须长得一样，否则用户会怀疑数据错了）。
            else -> lastTrainSeries
        }

        binding.detailChart.visibility =
            if (masked) View.INVISIBLE else View.VISIBLE
        if (!masked) {
            binding.detailChart.submit(series)
            binding.detailChart.setMeta(
                minLabel = series.minOrNull()?.let { trim(it) } ?: "",
                maxLabel = series.maxOrNull()?.let { trim(it) } ?: "",
                windowLabel = "",
                unit = unitOf(currentTab),
                emptyText = getString(R.string.goal_detail_empty),
            )
        } else {
            binding.detailChart.submit(emptyList())
        }

        binding.detailValue.text = if (masked) {
            getString(
                R.string.goal_detail_current_target,
                getString(R.string.goal_card_masked),
                getString(R.string.goal_card_masked),
            )
        } else {
            getString(R.string.goal_detail_current_target, currentText(), targetText())
        }
        binding.detailShowOnce.visibility =
            if (currentTab == TAB_WEIGHT && lastHideWeight && !weightRevealedOnce) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    /** 盈余视角：36sp 大数字 + 2dp 进度线 + 「摄入 · 消耗 · 目标」行。 */
    private fun renderGap() {
        if (_binding == null) return
        val s = lastGap
        binding.gapBigValue.text = if (s.gap >= 0) {
            getString(R.string.gap_format, s.gap)
        } else {
            getString(R.string.gap_negative_format, s.gap)
        }
        binding.gapProgress.progress = if (s.target > 0) {
            (s.kcalIn * 100 / s.target).coerceIn(0, 100)
        } else {
            0
        }
        binding.gapDetailLine.text = getString(R.string.summary_format, s.kcalIn, s.kcalOut, s.target)
    }

    // ── 文案 ──────────────────────────────────────────────────────────

    private fun currentText(): String = when (currentTab) {
        TAB_SLEEP -> {
            val v = lastSleep.lastOrNull()
            if (v == null) "—" else getString(R.string.unit_hour_short, trim(v))
        }
        TAB_WEIGHT -> {
            val v = lastWeight.lastOrNull()
            if (v == null) "—" else getString(R.string.unit_kg, trim(v))
        }
        else -> getString(R.string.unit_times, lastTrain.done)
    }

    private fun targetText(): String = when (currentTab) {
        TAB_SLEEP -> {
            val v = lastTargets[GoalMetrics.SLEEP_H]
            if (v == null || v <= 0.0) "—" else getString(R.string.unit_hour_short, trim(v))
        }
        TAB_WEIGHT -> {
            val v = lastTargets[GoalMetrics.WEIGHT_KG]
            if (v == null || v <= 0.0) "—" else getString(R.string.unit_kg, trim(v))
        }
        else -> getString(R.string.unit_times, lastTrain.goal)
    }

    private fun unitOf(tab: Int): String = when (tab) {
        TAB_SLEEP -> getString(R.string.unit_hour)
        TAB_WEIGHT -> getString(R.string.unit_kg_chart)
        else -> getString(R.string.unit_times_plain)
    }

    /** 去掉无意义的小数尾巴：58.0 → "58"，58.2 → "58.2"。 */
    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    /** 「改目标 / 添加其他目标」→ 设置页「目标」栏（数值目标唯一归属地）。 */
    private fun openGoalSettings() {
        dismiss()
        NavHost.open(requireContext(), SettingsFragment(), NavHost.PAGE_SETTINGS)
    }

    companion object {
        const val TAG = "GoalDetailSheet"

        /** 次目标①：本周训练。 */
        const val TAB_TRAIN = 0

        /** 次目标②：近 7 日睡眠。 */
        const val TAB_SLEEP = 1

        /** 次目标③：近 30 日体重。 */
        const val TAB_WEIGHT = 2

        /** 盈余视角（记录页「今日盈余」单行 → 不切 metric）。 */
        const val TAB_GAP = 3

        private const val ARG_TAB = "tab"

        fun newInstance(tab: Int): GoalDetailSheet = GoalDetailSheet().apply {
            arguments = Bundle().apply { putInt(ARG_TAB, tab) }
        }
    }
}
