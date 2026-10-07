package com.healix.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.healix.app.R
import com.healix.app.databinding.FragmentRecordBinding
import com.healix.app.databinding.ItemRecordHeaderBinding
import com.healix.app.db.EventEntity
import com.healix.app.db.GoalSlots
import com.healix.app.db.PresetEntity
import com.healix.app.db.SettingsKeys
import com.healix.app.notify.AppEvent
import com.healix.app.notify.AppEventBus
import com.healix.app.rules.RecentChips
import com.healix.app.ui.widget.InputBarHeightAnimator
import com.healix.app.ui.widget.SparklineView
import com.healix.app.widget.HealixWidgetProvider
import kotlinx.coroutines.launch

/**
 * 「记录」页（设计规范系统 4.1）。v8：由 `MainActivity` 的 pageHome 子树 +
 * 记录逻辑迁为**常驻 Tab Fragment**（`add` 一次 + `show/hide`）。
 *
 * 迁移等价性说明（相对旧 MainActivity 内联实现的逐点对照）：
 * - `MainViewModel` 改为 **Activity 作用域**（`ViewModelProvider(requireActivity())`），
 *   [MineFragment] 复用同一实例读 `homeStatus`（「我的」页状态副行与首页同源）；
 * - `onResume` 的跨零点刷新在 `show/hide` 下不会被触发（Fragment 常驻 RESUMED），
 *   改由宿主 [MainActivity.onResume] → [onAppForeground] 驱动；
 * - 记录页是「打开即记」的入口：`onViewCreated` 后 300ms 自动弹键盘（仅在可见时）。
 *
 * 需求 5：顶部新增主目标行 + 次目标进度区（本周训练 / 近 7 日睡眠 / 近 30 日体重），
 * 首次进入且未完成引导时弹 [GoalSetupSheet]。
 */
class RecordFragment : Fragment() {

    private var _binding: FragmentRecordBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: EventAdapter
    private lateinit var swipe: SwipeController

    // ── v0.3 B2（需求 2：合并滚动区）──
    // binding.list = ConcatAdapter(header, events, empty)。header 的视图引用经
    // [headerBinding]（由 RecordHeaderAdapter 首次绑定时回传）访问；`binding.xxx`
    // 从此只剩固定区（顶栏 / offlineBar / undoBar / inputBar）。
    private lateinit var headerAdapter: RecordHeaderAdapter
    private lateinit var emptyAdapter: RecordEmptyAdapter
    private var headerBinding: ItemRecordHeaderBinding? = null

    /**
     * Activity 作用域 VM：与 [MineFragment] 共享（「我的」页状态副行读同一 `homeStatus`）。
     */
    private val vm: MainViewModel by lazy {
        ViewModelProvider(requireActivity())[MainViewModel::class.java]
    }

    /** 最近一次 UI 状态：offlineBar 同一位置承载"离线 / 未配置"两种语义，须知道现在是哪种。 */
    private var lastUiState: MainUiState = MainUiState.Idle

    /** 状态行快照（B2 后进 header）：null = 尚无 Flow 到达，补渲染时跳过（不伪造初始态）。 */
    private var lastHomeStatus: HomeStatus? = null

    /** 今日记录数快照（监督提示条用）；-1 = 尚无 Flow 到达，补渲染时跳过。 */
    private var lastTodayCount: Int = -1

    // ── v8 问题 4：目标区快照 ─────────────────────────────────────────
    // 五路 Flow（主目标 / 目标值表 / 训练 / 睡眠 / 体重 / 隐私）到达顺序不确定，
    // 各自直接写视图会出现"隐私还没到、体重已经画了"的撕裂。
    // 统一做法：每路只更新自己的字段，再调一次 [renderGoalArea] 全量重渲染 ——
    // 渲染是幂等的，多渲染一次的成本远低于顺序 bug。

    /** 主目标快照（含目标体重与最近体重）。 */
    private var lastGoal: HomeGoal? = null

    /** active 目标 `metric -> targetValue`（判"是否有可显示的次目标"）。 */
    private var lastTargets: Map<String, Double> = emptyMap()

    private var lastTrain: TrainProgress = TrainProgress(0, 0)
    private var lastTrainSeries: List<Double> = emptyList()
    private var lastSleepSeries: List<Double> = emptyList()
    private var lastWeightSeries: List<Double> = emptyList()
    private var lastSummary: MainSummary = MainSummary()
    private var lastHideKcal: Boolean = false
    private var lastHideWeight: Boolean = false

    // ── 功能 5：首页横条（预设 + 最近记录）快照 ───────────────────────
    // 两路 Flow 到达顺序不确定，各自只写自己的快照再调一次 [renderPresetStrip]
    // 全量重渲染（与目标区同一套做法）。
    private var lastPresets: List<PresetEntity> = emptyList()
    private var lastRecents: List<EventEntity> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRecordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        swipe = SwipeController(requireContext())
        adapter = EventAdapter(
            onEdit = { entity ->
                EventEditSheet.newInstance(entity.clientEventId)
                    .show(childFragmentManager, EventEditSheet.TAG)
            },
            onRetry = { entity -> vm.retry(entity) },
            onDelete = { entity -> deleteWithUndo(entity) },
            swipe = swipe,
        )

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        // B2：header 恒 1 条，binding 首次绑定时回传 → 存引用 + 挂 header 内点击 +
        // 补渲染（flows 可能先于 header 绑定到达：RecyclerView 首次 layout 在
        // onViewCreated 返回之后）。补渲染全部幂等、纯本地、0 AI。
        headerAdapter = RecordHeaderAdapter(onHeaderBound = { b ->
            headerBinding = b
            bindHeaderInteractions(b)
            renderGoalArea()
            renderPresetStrip()
            renderMetricsState()
            renderNudgeBar()
            renderPlanBar()
        })
        emptyAdapter = RecordEmptyAdapter()
        binding.list.adapter = ConcatAdapter(headerAdapter, adapter, emptyAdapter)
        binding.list.setHasFixedSize(false)

        binding.dateLabel.text = HealixDate.labelOf(vm.todayDayKeyFlow.value)

        binding.btnSend.setOnClickListener { submit() }
        binding.btnSettings.setOnClickListener {
            NavHost.open(requireContext(), SettingsFragment(), NavHost.PAGE_SETTINGS)
        }
        // B2：planBar / nudgeBar / statusRow / goal* 等已随滚动 header 移入
        // item_record_header.xml，点击监听统一在 [bindHeaderInteractions] 挂
        // （header 绑定发生在 onViewCreated 之后，此处拿不到那些视图）。

        // 状态提示条点击：按当前语义分流（离线 → 重试；未配置 → 去设置）
        binding.offlineBar.setOnClickListener {
            if (lastUiState == MainUiState.NotConfigured) {
                NavHost.open(requireContext(), SettingsFragment(), NavHost.PAGE_SETTINGS)
            } else {
                vm.retryFailedPending()
            }
        }

        // 左滑"点其它区域自动回弹"：列表内按下非滑开行 → 收起（全局单开，11.3）
        // B2 后 header 也在这条监听覆盖范围内（findChildViewUnder 命中 header 行），
        // 按住目标区等 header 内容同样能收起已滑开的行 —— 挂载点零改动。
        binding.list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    val child = rv.findChildViewUnder(e.x, e.y)
                    swipe.closeIfOutside(child)
                }
                return false
            }
        })

        // 列表可视区外的按下（汇总区 / 顶栏等）也收起滑开的行
        binding.root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) swipe.closeIfOutside(null)
            false // 不消费
        }

        // ── P0-1 发送键双态：空文本 → text_3，非空 → accent（selector 驱动，零动画代码）──
        //     isSelected 变化会 refreshDrawableState → 重解析 @color/btn_send_tint。
        binding.input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                binding.btnSend.isSelected = !s.isNullOrEmpty()
            }
        })

        // ── P0-2 输入条高度自适应：随行数 200ms 平滑增高；增高时列表跟到底部（有一行才滚）──
        // B2：smoothScrollToPosition 用的是 ConcatAdapter **根坐标**，
        // EventAdapter 子坐标须 + HEADER_ITEM_COUNT（header 恒 1 条）。
        InputBarHeightAnimator.bind(binding.input, binding.inputBar) {
            val last = adapter.itemCount - 1
            if (last >= 0) binding.list.smoothScrollToPosition(last + HEADER_ITEM_COUNT)
        }

        observe()

        // 进入 300ms 后自动弹键盘（规范硬要求：打开即弹键盘、光标在输入框）。
        // ⚠️ 延时后再判 isHidden：三 Tab 在同一事务里 add + hide，onViewCreated 执行时
        //    hide 未必已落到本 Fragment 上；300ms 后状态稳定，非当前 Tab 不抢焦点。
        binding.input.postDelayed({
            if (isAdded && !isHidden) focusInput()
        }, 300)

        maybeShowGoalSetup()
    }

    /**
     * 复点当前 Tab 时由宿主 [MainActivity] 调用（v6 §5.1）：列表滚回顶部。
     * 三页常驻不销毁，视图可能已销毁（`_binding == null`）→ 空值守卫，不崩。
     */
    fun scrollToTop() {
        _binding?.list?.smoothScrollToPosition(0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // header 随列表销毁；引用一并清空，防渲染函数在销毁后经旧引用写视图
        headerBinding = null
        _binding = null
    }

    /**
     * `show/hide` 不触发 onResume（Fragment 常驻 RESUMED），跨零点刷新会话/日期必须挂这里。
     * 仅在被"显示"时执行 —— 每次切回记录页都重算今日 day_key。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) onVisible()
    }

    /** 切回本 Tab（或首次可见）时调用：跨零点重算今日 day_key + 刷新日期标签。 */
    fun onVisible() {
        vm.refresh()
    }

    /** 宿主回到前台（[MainActivity.onResume]）：跨零点 + 软删清理 + 规则扫描 + 桌面小工具推送。 */
    fun onAppForeground() {
        vm.refresh()
        // 规则扫描走"打开时计算"，不依赖后台定时器（PRD §7.4）。整个流程 0 次 AI 调用。
        vm.scanSignals()
        // 桌面小工具：回前台推一次（覆盖跨天 / 跨周后本周口径变化，app-pushes-updates）
        HealixWidgetProvider.push(requireActivity())
    }

    /** 外部入口（桌面小工具「记一笔」/ 通知）请求聚焦速记框。 */
    fun requestFocusInput() {
        if (_binding == null) return
        binding.input.postDelayed({ focusInput() }, 200)
    }

    /**
     * header 内视图的点击监听（B2：这些视图随滚动区进了 item_record_header.xml，
     * 只能在 RecordHeaderAdapter 首次绑定时挂）。语义与抽离前逐一对应：
     * - 主目标行 / 「去调整 ›」→ [onPrimaryGoalRowClick]；
     * - Hero 卡 → 盈余视角详情弹层（沿用旧「今日盈余」单行的去向）；
     * - 指标 chip（运动 / 睡眠 / 体重）→ [openGoalDetail]（点哪个 chip 定位哪个 metric）；
     * - 信号态 chip → 状态详情页（未读信号默认落「身体」段）并标记已读（规范 §9.2）；
     * - 计划提示条 → 计划页；监督提示条 → 聚焦输入框。
     */
    private fun bindHeaderInteractions(b: ItemRecordHeaderBinding) {
        b.goalPrimaryRow.setOnClickListener { onPrimaryGoalRowClick() }
        b.goalAdjust.setOnClickListener { onPrimaryGoalRowClick() }
        b.heroCard.setOnClickListener { openGoalDetail(GoalDetailSheet.TAB_GAP) }
        b.metricTrain.setOnClickListener { openGoalDetail(GoalDetailSheet.TAB_TRAIN) }
        b.metricSleep.setOnClickListener { openGoalDetail(GoalDetailSheet.TAB_SLEEP) }
        b.metricWeight.setOnClickListener { openGoalDetail(GoalDetailSheet.TAB_WEIGHT) }
        b.metricSignal.setOnClickListener {
            NavHost.open(
                requireContext(),
                StatusDetailFragment.newInstance(StatusDetailFragment.TAB_BODY),
                NavHost.PAGE_STATUS_DETAIL,
            )
            // 进了状态页就算看过了 → 已读后必须切回摘要态（规范 §9.2）
            vm.acknowledgeSignals()
        }
        b.planBar.setOnClickListener {
            NavHost.open(requireContext(), PlanReviewFragment(), NavHost.PAGE_PLAN_REVIEW)
        }
        b.nudgeBar.setOnClickListener { focusInput() }
    }

    /**
     * 主目标行（整行 / 右侧文字入口共同去向）：
     * - 已设主目标 → [GoalSetupSheet] **编辑态**（预填当前模式与目标体重），保存后就地返回；
     * - 未设 → 设置页「目标」栏（该栏主目标行的点击才开首启引导）。
     *
     * 判据取最近一次 [HomeGoal] 快照，不额外查库 —— 与行上显示的文字必然同源同帧。
     */
    private fun onPrimaryGoalRowClick() {
        val g = lastGoal
        if (g?.set == true) {
            openGoalEditor(g)
        } else {
            // focusGoal = true → 设置页**一步直达「目标」栏**，不再让用户自己下滑。
            // 与「个人信息页 → 我的目标」两条入口共用同一参数（ARG_FOCUS_GOAL）：
            // keep-alive 下 arguments 不同的同名页会先 remove 再 add（见 NavHost.open
            // 复用规则），命中复用则由 SettingsFragment.onHiddenChanged 补做滚动。
            NavHost.open(
                requireContext(),
                SettingsFragment.newInstance(focusGoal = true),
                NavHost.PAGE_SETTINGS,
            )
        }
    }

    /** 开 [GoalSetupSheet] 编辑态；落库复用宿主 VM 的 `completeGoalSetup`（与首启引导同一段逻辑）。 */
    private fun openGoalEditor(g: HomeGoal) {
        // 弹层属于本 Fragment → `childFragmentManager`（二级页 pop 时一起销毁）。
        // isStateSaved 时 show() 会抛 IllegalStateException（用户刚切后台）→ 跳过即可。
        if (childFragmentManager.isStateSaved) return
        GoalSetupSheet.newInstanceForEdit(
            modeIndex = g.modeIndex,
            weightKg = g.weightTargetKg,
            customText = g.statement,
        ).apply {
            onDone = { modeIndex, weightKg, customText ->
                vm.completeGoalSetup(modeIndex, weightKg, customText)
            }
        }.show(childFragmentManager, GoalSetupSheet.TAG)
    }

    /** 开目标详情半屏弹层（三卡默认定位该 metric；盈余行走 `TAB_GAP`）。 */
    private fun openGoalDetail(tab: Int) {
        if (childFragmentManager.isStateSaved) return
        GoalDetailSheet.newInstance(tab).show(childFragmentManager, GoalDetailSheet.TAG)
    }

    fun focusInput() {
        if (_binding == null) return
        binding.input.requestFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as InputMethodManager
        imm.showSoftInput(binding.input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun submit() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        // 空文本：不静默 return，改为聚焦（发送键灰态 = 可点但暂不可用，点击行为可预期）
        if (text.isEmpty()) {
            focusInput()
            return
        }
        // 一次轻触觉（P2-9 两处之一：发送点击）
        binding.btnSend.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        // 先清空再发送：vm.submit 是 persist-first，不存在丢文本路径，故不做失败回填
        // （回填会造成重复 pending 行）。清空后**只** requestFocus —— 键盘已在，
        // 重复 showSoftInput 会闪，焦点保持即可连续录入。
        binding.input.setText("")
        binding.input.requestFocus()
        vm.submit(text)
    }

    /**
     * 左滑删除（11.3）：一步删除 → 复用撤销条（5 秒，与"已记录"同一容器同套机制）；
     * 点「撤销」原位插回 —— 软删除恢复后 ts 不变，Room Flow 自动按原序回插。
     */
    private fun deleteWithUndo(e: EventEntity) {
        vm.deleteEvent(e.clientEventId)
        UndoBar.bind(
            container = binding.undoBar,
            leftText = binding.undoLeft,
            action = binding.undoAction,
            text = getString(R.string.undo_deleted, e.rawText.take(14)),
            announce = null,
            onUndo = { vm.restoreEvent(e.clientEventId) },
        )
    }

    /**
     * 指标 chip 行两态互斥渲染（规范 §9.2 / v6 §四，写入 header）。
     * - 无未读信号：右一显示体重 chip；
     * - 有未读信号：右一原地切成 warning 衬底 + warning 文字（体重 chip 隐藏），
     *   行高与列数不变（恒单行 48dp）。
     * [lastHomeStatus] 为 null = Flow 尚未到达（补渲染时跳过，不伪造初始态）。
     */
    private fun renderMetricsState() {
        val h = headerBinding ?: return
        val signal = lastHomeStatus as? HomeStatus.Signal
        if (signal == null) {
            h.metricWeight.visibility = View.VISIBLE
            h.metricSignal.visibility = View.GONE
            return
        }
        h.metricWeight.visibility = View.GONE
        h.metricSignal.visibility = View.VISIBLE
        h.metricSignal.setBackgroundResource(R.drawable.bg_metric_chip_warn)
        h.metricSignalText.text = signal.text
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // 进程内事件（通知栏录入 → 前台反馈，AppEventBus）：
                // 数据刷新由 Room Flow 承担，这里只接「即时提示」职责。
                launch {
                    AppEventBus.events.collect { event ->
                        when (event) {
                            is AppEvent.RequestFocusInput -> focusInput()
                            is AppEvent.Failed -> android.widget.Toast.makeText(
                                requireContext(), event.reason,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            else -> Unit
                        }
                    }
                }

                launch {
                    vm.events.collect { list ->
                        adapter.submit(list)
                        // B2：空态由 footer 段驱动（原 emptyState 覆盖层已删，BP-1）
                        emptyAdapter.setEmpty(list.isEmpty())
                    }
                }

                launch {
                    vm.summary.collect { s ->
                        // v6：热量汇总升级为 Hero 卡（左列 Text.Hero 主数字 + 8dp 进度条
                        // + 右侧 76dp 达标环）；大数字与明细仍下沉到 GoalDetailSheet 盈余视角。
                        lastSummary = s
                        renderGoalArea()
                        renderPlanBar()
                    }
                }

                launch {
                    vm.uiState.collect { state ->
                        lastUiState = state
                        when (state) {
                            MainUiState.Idle -> {
                                binding.spinner.visibility = View.GONE
                                binding.stateLabel.visibility = View.GONE
                            }
                            MainUiState.Parsing -> {
                                binding.spinner.visibility = View.VISIBLE
                                binding.stateLabel.visibility = View.VISIBLE
                                binding.stateLabel.text = getString(R.string.state_parsing)
                            }
                            is MainUiState.Queued -> {
                                binding.spinner.visibility = View.VISIBLE
                                binding.stateLabel.visibility = View.VISIBLE
                                // 限流必须显示预估秒数，不静默转圈（规范 3.10）
                                binding.stateLabel.text =
                                    getString(R.string.state_queued, state.seconds)
                            }
                            MainUiState.Offline -> {
                                binding.spinner.visibility = View.GONE
                                binding.stateLabel.visibility = View.GONE
                                binding.offlineBar.text = getString(R.string.state_offline)
                                binding.offlineBar.visibility = View.VISIBLE
                            }
                            MainUiState.NotConfigured -> {
                                // 未配置 ≠ 离线。同一条提示条，文案与动作不同：点一下直接去设置页
                                binding.spinner.visibility = View.GONE
                                binding.stateLabel.visibility = View.GONE
                                binding.offlineBar.text = getString(R.string.no_provider_config)
                                binding.offlineBar.visibility = View.VISIBLE
                            }
                        }
                    }
                }

                // 预设 + 最近记录（功能 5）：共用一条横滚；两路各写快照后整体重渲染
                launch { vm.presets.collect { lastPresets = it; renderPresetStrip() } }
                launch { vm.recentChips.collect { lastRecents = it; renderPresetStrip() } }

                // 状态：v6 起并入指标 chip 行（两态互斥，恒单行 48dp）；
                // 「我的」页副行由 MineFragment 自行订阅同源 flow
                launch {
                    vm.homeStatus.collect { status ->
                        lastHomeStatus = status
                        renderMetricsState()
                    }
                }

                // 内联撤销条：写入成功后 5 秒可撤销（规范 §9.6 / PRD §15.7）
                launch {
                    vm.undo.collect { payload ->
                        val text = if (payload.totalCount > 1) {
                            getString(
                                R.string.undo_recorded_extra,
                                payload.totalCount,
                                payload.valueText.ifEmpty { "" },
                            )
                        } else {
                            getString(R.string.undo_recorded, payload.typeName, payload.valueText)
                        }
                        UndoBar.bind(
                            container = binding.undoBar,
                            leftText = binding.undoLeft,
                            action = binding.undoAction,
                            text = text,
                            announce = getString(R.string.undo_announce, payload.typeName),
                            onUndo = { vm.undo(payload.clientEventId) },
                        )
                    }
                }

                // 隐私：隐藏热量数字时，「今日盈余」行与计划条**整块不显示**（PRD §14.3）
                launch {
                    vm.hideKcal.collect { hidden ->
                        lastHideKcal = hidden
                        adapter.hideKcal = hidden
                        renderGoalArea()
                        renderPlanBar()
                    }
                }

                launch {
                    vm.todayCount.collect { count ->
                        // 监督提示条：今日无记录时出现（被动监督，不依赖后台定时器）
                        lastTodayCount = count
                        renderNudgeBar()
                    }
                }

                launch {
                    // 日期标签与列表同口径：跨零点 refresh() 后今日 day_key 变化 → 标签同步刷新
                    vm.todayDayKeyFlow.collect { key ->
                        binding.dateLabel.text = HealixDate.labelOf(key)
                    }
                }

                // ── 需求 5 / v8 问题 4：主目标 + 三卡 + 今日盈余 ──
                launch { vm.homeGoal.collect { lastGoal = it; renderGoalArea() } }
                launch { vm.goalTargets.collect { lastTargets = it; renderGoalArea() } }
                launch { vm.trainProgress.collect { lastTrain = it; renderGoalArea() } }
                launch { vm.trainSeries.collect { lastTrainSeries = it; renderGoalArea() } }
                launch { vm.sleepSeries.collect { lastSleepSeries = it; renderGoalArea() } }
                launch { vm.weightSeries.collect { lastWeightSeries = it; renderGoalArea() } }
                // 隐私：隐藏体重数字 → 体重卡值打码、迷你图不绘制，但**保留 20dp 空高**（卡片不塌陷）
                launch { vm.hideWeight.collect { lastHideWeight = it; renderGoalArea() } }
            }
        }
    }

    // ── 需求 5 / v6 记录页渲染 ─────────────────────────────────────────

    /**
     * 目标区**全量重渲染**（幂等）。
     *
     * 结构（自上而下）：主目标行 48dp + 1dp 线 + Hero 卡 + 指标 chip 行 48dp。
     * 显隐规则：
     * - 主目标未设 → 行内改「未设置主目标 / 去设置 ›」；
     * - 没有任何 active 次目标且无未读信号 → 指标 chip 行整段收起；
     * - 隐藏热量数字（HIDE_KCAL）或主目标未设 → Hero 卡整卡隐藏。
     *
     * 指标 chip 行**恒为**运动 / 睡眠 / 体重三格（两态互斥，恒单行 48dp），
     * 与用户启用了几个次目标无关 —— 行列结构在 XML 写死，代码只填值与图。
     */
    private fun renderGoalArea() {
        if (_binding == null) return
        // B2：目标区进了 header —— 未绑定前直接跳过（onHeaderBound 会补渲染一次）
        val h = headerBinding ?: return

        val g = lastGoal
        val primarySet = g?.set == true
        val hasSecondary = GoalSlots.ADDABLE.any { slot -> slot.metrics.any { it in lastTargets.keys } }
        // 有未读信号时也必须显示指标行（信号占右一格），否则未读信号会被整段收起
        val hasSignal = lastHomeStatus is HomeStatus.Signal
        val showMetrics = hasSecondary || hasSignal
        // 隐藏热量数字时，"能算盈余"这件事本身就不该显示（Hero 卡整卡隐藏）
        val showHero = primarySet && !lastHideKcal

        renderPrimaryRow(g, primarySet)
        renderHero()
        renderMetrics()
        renderMetricsState()

        h.heroCard.visibility = if (showHero) View.VISIBLE else View.GONE
        h.metricsRow.visibility = if (showMetrics) View.VISIBLE else View.GONE
        // 分隔线跟随相邻内容：有内容才画线，避免收起后留下孤立的 1dp 线
        h.goalDivider.visibility = if (showMetrics || showHero) View.VISIBLE else View.GONE
    }

    /**
     * 主目标行。已设 → `增重 · 目标 70 kg` + `现 65.4 kg，还差 4.6 kg` + 「去调整 ›」(accent)；
     * 未设 → `未设置主目标`(text_2) + 「去设置 ›」(text_2)，副行隐藏。
     */
    private fun renderPrimaryRow(g: HomeGoal?, primarySet: Boolean) {
        val h = headerBinding ?: return
        if (primarySet && g != null) {
            val mode = primaryModeLabel(g)
            h.goalPrimaryText.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_1),
            )
            h.goalPrimaryText.text = if (g.weightTargetKg > 0.0) {
                getString(R.string.goal_primary_target, mode, trimNumber(g.weightTargetKg))
            } else {
                getString(R.string.goal_primary_line, mode)
            }

            // 副行：有现值才报值；目标体重存在时才算"还差"
            h.goalPrimarySub.text = when {
                g.latestWeightKg <= 0.0 -> ""
                g.weightTargetKg <= 0.0 ->
                    getString(R.string.goal_primary_current_only, trimNumber(g.latestWeightKg))
                else -> getString(
                    R.string.goal_primary_current,
                    trimNumber(g.latestWeightKg),
                    trimNumber(kotlin.math.abs(g.weightTargetKg - g.latestWeightKg)),
                )
            }
            h.goalPrimarySub.visibility =
                if (h.goalPrimarySub.text.isEmpty()) View.GONE else View.VISIBLE

            h.goalAdjust.setText(R.string.goal_adjust)
            h.goalAdjust.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.accent),
            )
        } else {
            h.goalPrimaryText.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_2),
            )
            h.goalPrimaryText.setText(R.string.goal_primary_none)
            h.goalPrimarySub.visibility = View.GONE
            h.goalAdjust.setText(R.string.goal_go_settings)
            h.goalAdjust.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_2),
            )
        }
    }

    /**
     * Hero 卡内容：Text.Hero 主数字（盈亏正负号保留）+ 单位 + 8dp 进度条 + 达标环。
     * 进度与环的百分比都取 `摄入 / 目标`（clamp 0..1）；目标为 0 时按 0 处理。
     * 卡片显隐由 [renderGoalArea] 统一控制（HIDE_KCAL / 主目标未设 → 隐藏）。
     */
    private fun renderHero() {
        val h = headerBinding ?: return
        val s = lastSummary
        h.heroNum.text = if (s.gap >= 0) {
            getString(R.string.gap_format, s.gap)
        } else {
            getString(R.string.gap_negative_format, s.gap)
        }
        h.heroSub.text = getString(R.string.summary_format, s.kcalIn, s.kcalOut, s.target)
        val pct = if (s.target > 0) (s.kcalIn.toFloat() / s.target).coerceIn(0f, 1f) else 0f
        setHeroProgress(pct)
        h.heroRing.submit(pct, getString(R.string.rec_ring_label))
    }

    /** 8dp 进度条按权重分配填充 / 余量（weightSum 100，免去测量后再设宽度的时序问题）。 */
    private fun setHeroProgress(pct: Float) {
        val h = headerBinding ?: return
        val fill = h.heroBarFill.layoutParams as LinearLayout.LayoutParams
        fill.weight = pct * 100f
        h.heroBarFill.layoutParams = fill
        val rest = h.heroBarRest.layoutParams as LinearLayout.LayoutParams
        rest.weight = (1f - pct) * 100f
        h.heroBarRest.layoutParams = rest
    }

    /**
     * 指标 chip 行内容：运动 / 睡眠 / 体重三格值 + 20dp 迷你趋势线。
     * 信号态的衬底切换在 [renderMetricsState] 里做（两态互斥，恒单行 48dp）。
     */
    private fun renderMetrics() {
        val h = headerBinding ?: return
        h.metricTrainValue.text =
            getString(R.string.sub_goal_train_card, lastTrain.done, lastTrain.goal)
        renderSpark(h.metricTrainSpark, lastTrainSeries)

        h.metricSleepValue.text = lastSleepSeries.lastOrNull()?.let {
            getString(R.string.unit_hour_short, trimNumber(it))
        } ?: EMPTY_VALUE
        renderSpark(h.metricSleepSpark, lastSleepSeries)

        if (lastHideWeight) {
            h.metricWeightValue.text = getString(R.string.goal_card_masked)
        } else {
            h.metricWeightValue.text = lastWeightSeries.lastOrNull()?.let {
                getString(R.string.unit_kg, trimNumber(it))
            } ?: EMPTY_VALUE
        }
        // 隐私：打码时**不绘制**折线，但 20dp 槽位保留（chip 高度固定 → 不塌陷）
        renderSpark(h.metricWeightSpark, lastWeightSeries, masked = lastHideWeight)
    }

    /**
     * 迷你趋势线渲染。<3 点时不连线，改显示占位文案（0 点 `暂无记录` / 1–2 点 `攒够 3 次`）。
     * [masked] 为 true 时数据位留空、连占位文案也不画（隐私口径）。
     */
    private fun renderSpark(view: SparklineView, values: List<Double>, masked: Boolean = false) {
        if (masked) {
            view.submit(emptyList())
            view.setEmptyText("")
            return
        }
        view.setEmptyText(
            getString(
                if (values.isEmpty()) R.string.goal_mini_empty else R.string.goal_mini_insufficient,
            ),
        )
        view.submit(values)
    }

    /**
     * 计划提示条（B2 后写入 header）—— **记录页通往「计划 / 复盘」二级页的唯一入口**。
     *
     * 恒可见：旧版在 HIDE_KCAL 打开时把整条收起，计划页在记录页就彻底没有入口了，
     * 只能绕「状态详情 → 运动 → 查看本周训练计划」才找得到（已实测为「找不到入口」）。
     * PRD §14.3 的隐私口径是「不显示热量数字 / 该块不显示」，不等于「连入口一起藏」——
     * 故隐藏热量时改为不带数字的一句「查看今日建议 ›」，入口保留、数字不泄露。
     *
     * 缺口为 0 时同样补上箭头后缀：只写「今日已达标」是状态陈述，用户看不出它可点、
     * 更看不出它通往计划页（这是入口"存在但找不到"的另一半原因）。
     */
    private fun renderPlanBar() {
        val h = headerBinding ?: return
        val enter = getString(R.string.view_advice)
        h.planBar.text = when {
            lastHideKcal -> enter
            lastSummary.gap > 0 -> getString(R.string.plan_gap, lastSummary.gap) + "　" + enter
            else -> getString(R.string.plan_reached) + "　" + enter
        }
        h.planBar.visibility = View.VISIBLE
    }

    /** 监督提示条（B2 后写入 header）：今日无记录时出现；-1 = Flow 尚未到达，跳过。 */
    private fun renderNudgeBar() {
        val h = headerBinding ?: return
        if (lastTodayCount < 0) return
        h.nudgeBar.visibility = if (lastTodayCount == 0) View.VISIBLE else View.GONE
    }

    /** 主目标模式 → 展示文案（增重/减重/保持/自定义文本；自定义回落保持文案）。 */
    private fun primaryModeLabel(g: HomeGoal): String = when (g.modeIndex) {
        SettingsViewModel.GOAL_MODE_LOSS -> getString(R.string.goal_loss)
        SettingsViewModel.GOAL_MODE_KEEP -> getString(R.string.goal_keep)
        SettingsViewModel.GOAL_MODE_CUSTOM -> g.statement.trim().ifBlank { getString(R.string.goal_keep) }
        else -> getString(R.string.goal_gain)
    }

    /** 去掉无意义的小数尾巴：58.0 → "58"，58.2 → "58.2"。 */
    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    /**
     * 需求 5：首次进入目标引导。
     *
     * 判据（§D.2）：
     * - 已完成（`GOAL_SETUP_DONE == "true"`）→ 不弹；
     * - **老用户**（已有 active 目标行）→ 静默补写标记，不弹（避免升级后被无意义地弹一次）；
     * - **全新安装**（goals 空表）→ 弹 [GoalSetupSheet]。
     *
     * 放在首帧之后（`onViewCreated` 末尾）异步执行，不阻塞首帧。
     */
    private fun maybeShowGoalSetup() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (vm.rawSetting(SettingsKeys.GOAL_SETUP_DONE) == "true") return@launch
            if (vm.hasAnyActiveGoal()) {
                vm.completeGoalSetup(modeIndex = null, weightKg = null)
                return@launch
            }
            if (!isAdded || _binding == null) return@launch
            // 读库是挂起调用，期间 Activity 可能已 onSaveInstanceState（用户切后台）——
            // 此时 show() 会抛 IllegalStateException。跳过即可：标记未写，下次启动补弹。
            if (childFragmentManager.isStateSaved) return@launch
            GoalSetupSheet.newInstance().apply {
                onDone = { modeIndex, weightKg, customText ->
                    vm.completeGoalSetup(modeIndex, weightKg, customText)
                }
            }.show(childFragmentManager, GoalSetupSheet.TAG)
        }
    }

    /**
     * 首页横条：预设 chips + 「最近记录」chips（功能补充 2.1 / v8 需求 9 功能 5）。
     *
     * 点一下 = 一条记录，完全不打字、不调 AI —— 这是全 App 摩擦最低的路径。
     * v6：两段**共用同一条横滚**，且都渲染为 Filter chip（item_rec_chip，chip 形态 +
     * 点选态由 bg_chip / chip_text selector 承载）；不再插竖线分隔（chip 自身的
     * 圆角与间距已足够分组）。最近 chip 的文案与体重遮罩由 [RecentChips.label]
     * 决定（受 HIDE_WEIGHT 约束，与指标 chip 同口径）。
     */
    private fun renderPresetStrip() {
        val h = headerBinding ?: return
        h.presetRow.removeAllViews()
        val presets = lastPresets
        val recents = lastRecents
        val visible = presets.isNotEmpty() || recents.isNotEmpty()
        h.presetScroll.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        for (preset in presets) {
            val tv = layoutInflater.inflate(R.layout.item_rec_chip, h.presetRow, false)
                as android.widget.TextView
            tv.text = preset.name
            tv.setOnClickListener { vm.logPreset(preset) }
            tv.bindPressScale()
            h.presetRow.addView(tv)
        }

        for (event in recents) {
            val tv = layoutInflater.inflate(R.layout.item_rec_chip, h.presetRow, false)
                as android.widget.TextView
            tv.text = RecentChips.label(requireContext(), event, lastHideWeight)
            tv.setOnClickListener { vm.logRecent(event) }
            tv.bindPressScale()
            h.presetRow.addView(tv)
        }
    }

    private companion object {
        /** 无数据时的值占位（全角破折号，与状态详情页同一口径）。 */
        const val EMPTY_VALUE = "—"

        /** ConcatAdapter 根坐标偏移：header 段恒 1 条（迁移点② smoothScrollToPosition 用）。 */
        const val HEADER_ITEM_COUNT = 1
    }
}

/**
 * 记录列表适配器已迁至 `RecordListAdapters.kt`（v6：分组卡逻辑与另两段轻适配器同处一文件）。
 */

