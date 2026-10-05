package com.healix.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.healix.app.R
import com.healix.app.databinding.FragmentRecordBinding
import com.healix.app.databinding.ItemEventBinding
import com.healix.app.db.EventEntity
import com.healix.app.db.PresetEntity
import com.healix.app.db.SettingsKeys
import com.healix.app.notify.AppEvent
import com.healix.app.notify.AppEventBus
import com.healix.app.notify.EventText
import com.healix.app.ui.widget.TrendChartView
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

    /**
     * Activity 作用域 VM：与 [MineFragment] 共享（「我的」页状态副行读同一 `homeStatus`）。
     */
    private val vm: MainViewModel by lazy {
        ViewModelProvider(requireActivity())[MainViewModel::class.java]
    }

    /** 最近一次 UI 状态：offlineBar 同一位置承载"离线 / 未配置"两种语义，须知道现在是哪种。 */
    private var lastUiState: MainUiState = MainUiState.Idle

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
        binding.list.adapter = adapter
        binding.list.setHasFixedSize(false)

        binding.dateLabel.text = HealixDate.labelOf(vm.todayDayKeyFlow.value)

        binding.btnSend.setOnClickListener { submit() }
        binding.btnSettings.setOnClickListener {
            NavHost.open(requireContext(), SettingsFragment(), NavHost.PAGE_SETTINGS)
        }
        binding.planBar.setOnClickListener {
            NavHost.open(requireContext(), PlanReviewFragment(), NavHost.PAGE_PLAN_REVIEW)
        }
        binding.nudgeBar.setOnClickListener { focusInput() }

        // 状态行：整行进入状态详情页（规范 §9.2）。有信号时默认落在「身体」段，
        // 否则落在「运动」段 —— 入口决定默认段，用户不用再猜。
        binding.statusRow.setOnClickListener {
            val tab = if (binding.statusRow.tag == StatusDetailFragment.TAB_BODY) {
                StatusDetailFragment.TAB_BODY
            } else {
                StatusDetailFragment.TAB_EXERCISE
            }
            NavHost.open(
                requireContext(),
                StatusDetailFragment.newInstance(tab),
                NavHost.PAGE_STATUS_DETAIL,
            )
            // 进了状态页就算看过了 → 已读后必须切回摘要态（规范 §9.2）
            vm.acknowledgeSignals()
        }

        // 状态提示条点击：按当前语义分流（离线 → 重试；未配置 → 去设置）
        binding.offlineBar.setOnClickListener {
            if (lastUiState == MainUiState.NotConfigured) {
                NavHost.open(requireContext(), SettingsFragment(), NavHost.PAGE_SETTINGS)
            } else {
                vm.retryFailedPending()
            }
        }

        // ── 需求 5：主目标行 / 次目标进度区 ──
        binding.goalPrimaryRow.setOnClickListener {
            NavHost.open(requireContext(), SettingsFragment(), NavHost.PAGE_SETTINGS)
        }
        binding.goalAdjust.setOnClickListener {
            NavHost.open(requireContext(), SettingsFragment(), NavHost.PAGE_SETTINGS)
        }
        binding.subTrainRow.setOnClickListener { openStatus(StatusDetailFragment.TAB_EXERCISE) }
        binding.subSleepRow.setOnClickListener { openStatus(StatusDetailFragment.TAB_SLEEP) }
        binding.subWeightRow.setOnClickListener { openStatus(StatusDetailFragment.TAB_WEIGHT) }

        // 左滑"点其它区域自动回弹"：列表内按下非滑开行 → 收起（全局单开，11.3）
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

        observe()

        // 进入 300ms 后自动弹键盘（规范硬要求：打开即弹键盘、光标在输入框）。
        // ⚠️ 延时后再判 isHidden：三 Tab 在同一事务里 add + hide，onViewCreated 执行时
        //    hide 未必已落到本 Fragment 上；300ms 后状态稳定，非当前 Tab 不抢焦点。
        binding.input.postDelayed({
            if (isAdded && !isHidden) focusInput()
        }, 300)

        maybeShowGoalSetup()
    }

    override fun onDestroyView() {
        super.onDestroyView()
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

    /** 打开状态详情页的指定段（需求 5 次目标项整块可点）。 */
    private fun openStatus(tab: String) {
        NavHost.open(
            requireContext(),
            StatusDetailFragment.newInstance(tab),
            NavHost.PAGE_STATUS_DETAIL,
        )
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
        if (text.isEmpty()) return
        binding.input.setText("")
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
     * 状态行右侧 chevron 取色：摘要态 `text_3`、信号态 `accent`（规范 §9.2）。
     */
    private fun tintChevron(colorRes: Int) {
        binding.statusChevron.drawable?.setTint(ContextCompat.getColor(requireContext(), colorRes))
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
                        binding.emptyState.visibility =
                            if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    vm.summary.collect { s ->
                        binding.gapValue.text = if (s.gap >= 0) {
                            getString(R.string.gap_format, s.gap)
                        } else {
                            getString(R.string.gap_negative_format, s.gap)
                        }
                        binding.summaryLine.text = getString(
                            R.string.summary_format, s.kcalIn, s.kcalOut, s.target,
                        )
                        val pct = if (s.target > 0) s.kcalIn * 100 / s.target else 0
                        binding.progressLine.progress = pct.coerceIn(0, 100)

                        // 计划提示条：缺口为 0 时改为「今日已达标」
                        binding.planBar.text = if (s.gap > 0) {
                            getString(R.string.plan_gap, s.gap) + "　" + getString(R.string.view_advice)
                        } else {
                            getString(R.string.plan_reached)
                        }
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

                launch { vm.presets.collect { renderPresets(it) } }

                // 状态行：两态互斥渲染（规范 §9.2）；「我的」页副行由 MineFragment 自行订阅同源 flow
                launch {
                    vm.homeStatus.collect { status ->
                        when (status) {
                            is HomeStatus.Signal -> {
                                binding.statusRow.tag = StatusDetailFragment.TAB_BODY
                                binding.statusText.text = status.text
                                binding.statusText.setTextColor(
                                    ContextCompat.getColor(requireContext(), R.color.accent),
                                )
                                tintChevron(R.color.accent)
                            }
                            is HomeStatus.Summary -> {
                                binding.statusRow.tag = StatusDetailFragment.TAB_EXERCISE
                                binding.statusText.text = status.text
                                binding.statusText.setTextColor(
                                    ContextCompat.getColor(requireContext(), R.color.text_2),
                                )
                                tintChevron(R.color.text_3)
                            }
                            HomeStatus.Empty -> {
                                binding.statusRow.tag = StatusDetailFragment.TAB_EXERCISE
                                binding.statusText.setText(R.string.status_none)
                                binding.statusText.setTextColor(
                                    ContextCompat.getColor(requireContext(), R.color.text_3),
                                )
                                tintChevron(R.color.text_3)
                            }
                        }
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

                // 隐私：隐藏热量数字时，汇总区与计划条**整块不显示**（PRD §14.3）
                launch {
                    vm.hideKcal.collect { hidden ->
                        binding.summaryBlock.visibility =
                            if (hidden) View.GONE else View.VISIBLE
                        binding.planBar.visibility = if (hidden) View.GONE else View.VISIBLE
                        adapter.hideKcal = hidden
                    }
                }

                launch {
                    vm.todayCount.collect { count ->
                        // 监督提示条：今日无记录时出现（被动监督，不依赖后台定时器）
                        binding.nudgeBar.visibility =
                            if (count == 0) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    // 日期标签与列表同口径：跨零点 refresh() 后今日 day_key 变化 → 标签同步刷新
                    vm.todayDayKeyFlow.collect { key ->
                        binding.dateLabel.text = HealixDate.labelOf(key)
                    }
                }

                // ── 需求 5：主目标 + 次目标进度 ──
                launch { vm.homeGoal.collect { renderGoal(it) } }
                launch { vm.trainProgress.collect { renderTrain(it) } }
                launch {
                    vm.sleepSeries.collect {
                        renderSeries(
                            chart = binding.subSleepChart,
                            values = it,
                            unit = getString(R.string.unit_hour),
                            emptyText = getString(R.string.sub_goal_empty_sleep),
                        )
                    }
                }
                launch {
                    vm.weightSeries.collect {
                        renderSeries(
                            chart = binding.subWeightChart,
                            values = it,
                            unit = getString(R.string.unit_kg_chart),
                            emptyText = getString(R.string.sub_goal_empty_weight),
                        )
                    }
                }
                // 隐私：隐藏体重数字时体重项整块不显示（图表折线一并隐藏，口径同汇总区）
                launch {
                    vm.hideWeight.collect { hidden ->
                        binding.subWeightRow.visibility = if (hidden) View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }

    // ── 需求 5 渲染 ──────────────────────────────────────────────────────

    private fun renderGoal(g: HomeGoal) {
        binding.goalPrimaryText.text = if (g.set) {
            getString(R.string.goal_primary_line, primaryModeLabel(g.modeIndex))
        } else {
            getString(R.string.goal_primary_none)
        }
        binding.goalStatementText.text = g.statement
        binding.goalStatementText.visibility =
            if (g.statement.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderTrain(p: TrainProgress) {
        binding.subTrainValue.text = getString(R.string.sub_goal_train_value, p.done, p.goal)
        val pct = if (p.goal > 0) p.done * 100 / p.goal else 0
        binding.subTrainProgress.progress = pct.coerceIn(0, 100)
    }

    private fun renderSeries(chart: TrendChartView, values: List<Double>, unit: String, emptyText: String) {
        chart.submit(values)
        // 窗口标注留空：行标题已写「近 7 日 / 近 30 日」，图表再标一遍是冗余。
        chart.setMeta(
            minLabel = values.minOrNull()?.let { trimNumber(it) } ?: "",
            maxLabel = values.maxOrNull()?.let { trimNumber(it) } ?: "",
            windowLabel = "",
            unit = unit,
            emptyText = emptyText,
        )
    }

    /** 主目标模式 → 展示文案（增重/减重/保持）。 */
    private fun primaryModeLabel(mode: Int): String = when (mode) {
        SettingsViewModel.GOAL_MODE_LOSS -> getString(R.string.goal_loss)
        SettingsViewModel.GOAL_MODE_KEEP -> getString(R.string.goal_keep)
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
                onDone = { modeIndex, weightKg -> vm.completeGoalSetup(modeIndex, weightKg) }
            }.show(childFragmentManager, GoalSetupSheet.TAG)
        }
    }

    /**
     * 预设横条：点一下 = 一条记录，完全不打字、不调 AI（功能补充 2.1）。
     * 这是全 App 摩擦最低的路径。
     */
    private fun renderPresets(list: List<PresetEntity>) {
        binding.presetRow.removeAllViews()
        val visible = list.isNotEmpty()
        binding.presetScroll.visibility = if (visible) View.VISIBLE else View.GONE
        binding.presetDivider.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        for (preset in list) {
            val tv = layoutInflater.inflate(R.layout.item_preset, binding.presetRow, false)
                as android.widget.TextView
            tv.text = preset.name
            tv.setOnClickListener { vm.logPreset(preset) }
            tv.bindPressScale()
            binding.presetRow.addView(tv)
        }
    }
}

/**
 * 记录列表适配器。
 * 无卡片、无阴影、无彩色徽章 —— 靠 6px 圆点 + 1dp 分隔线组织信息。
 *
 * v6：每行包 swipewrap（item_event.xml），左滑露「编辑/删除」（11.3）；
 * 按压缩放双反馈（11.4）。手势由共享的 [SwipeController] 统一裁决
 * （全局单开 + 300ms click 屏蔽）。
 *
 * v8：由 `MainActivity.kt` 底部迁入本文件（只服务记录页）。
 */
class EventAdapter(
    private val onEdit: (EventEntity) -> Unit,
    private val onRetry: (EventEntity) -> Unit,
    private val onDelete: (EventEntity) -> Unit,
    private val swipe: SwipeController,
) : RecyclerView.Adapter<EventAdapter.VH>() {

    private var items: List<EventEntity> = emptyList()

    /**
     * 隐私：隐藏热量数字（规范 §9.7 ④）。为 true 时 meal / exercise 的摘要
     * 不再显示 kcal，改为显示用户自己填的数量文本；没有数量就整行隐藏。
     */
    var hideKcal: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    fun submit(list: List<EventEntity>) {
        items = list
        notifyItemRangeChanged(0, items.size)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemEventBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemEventBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(e: EventEntity) {
            val ctx = b.root.context

            // ── 复用防残留：滑开态 ViewHolder 被复用到新 item 时，swipeWrap
            //    可能带着上一次的 -144dp 平移。bind 前先取消残留动画、归位平移，
            //    并解除 SwipeController 对这个视图的滑开跟踪。──
            b.swipeItem.animate().cancel()
            b.swipeItem.translationX = 0f
            swipe.release(b.swipeItem)

            // ── v6 左滑：拖拽跟随 + 按压缩放，同一个触摸监听承载两种反馈 ──
            b.swipeItem.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE).setDuration(120).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                swipe.onTouch(v, ev)
                false // 不消费：点击 / 长按照旧
            }
            b.actEdit.setOnClickListener {
                if (!swipe.clickAllowed()) return@setOnClickListener
                swipe.closeAll()
                onEdit(e)
            }
            b.actDelete.setOnClickListener {
                if (!swipe.clickAllowed()) return@setOnClickListener
                swipe.closeAll()
                onDelete(e)
            }

            // 类型 + 时间（第一行，13sp text_2）
            b.typeLabel.text = EventText.typeName(ctx, e.type)
            b.timeLabel.text = HealixDate.timeLabel(e.ts)

            // 6px 圆点按类型着色
            b.dot.background.setTint(EventText.typeColor(ctx, e.type))

            // 正文：raw_text（15sp text_1，最多 2 行）
            b.bodyText.text = e.rawText

            // 摘要：按类型口径，无信息则隐藏（不留空行）
            val summary = EventText.summary(ctx, e, hideKcal)
            b.summaryText.text = summary
            b.summaryText.visibility = if (summary.isNullOrEmpty()) View.GONE else View.VISIBLE

            // 三态：pending 显示"识别中"，failed 显示「未识别 · 点此补充」
            when (e.parseStatus) {
                PARSE_PENDING -> {
                    // 「识别中」13sp text_3、**不可点**、整行仍可点进编辑（规范 §3.3）
                    b.pendingText.visibility = View.VISIBLE
                    b.errorText.visibility = View.GONE
                }
                PARSE_FAILED -> {
                    b.pendingText.visibility = View.GONE
                    b.errorText.visibility = View.VISIBLE

                    // ⚠️ 失败 ≠ 错误（PRD §15.5 / 规范 §3.10）：
                    //   原文已落库、day_key 已算对 → "这条还没算完"，不是数据丢了。
                    //   negative **只留给"数据真的可能丢"**：DB 写入 / 更新失败。
                    val dataLoss = e.lastError?.let {
                        it.startsWith("db_insert_failed") || it.startsWith("db_update_failed")
                    } == true

                    if (dataLoss) {
                        b.errorText.setText(R.string.state_save_failed)
                        b.errorText.setTextColor(ContextCompat.getColor(ctx, R.color.negative))
                        b.errorText.setOnClickListener { onRetry(e) }
                    } else {
                        b.errorText.setText(R.string.state_unrecognized)
                        b.errorText.setTextColor(ContextCompat.getColor(ctx, R.color.text_2))
                        // 「补充」= 进编辑弹窗，交给整行的 onEdit 处理
                        b.errorText.setOnClickListener(null)
                        b.errorText.isClickable = false
                    }
                }
                else -> {
                    b.pendingText.visibility = View.GONE
                    b.errorText.visibility = View.GONE
                }
            }

            // 整行可点 → 编辑（复用 ConfirmSheet）。刚拖完的 300ms 内不触发（V4）。
            b.swipeItem.setOnClickListener {
                if (!swipe.clickAllowed()) return@setOnClickListener
                onEdit(e)
            }

            // 最后一行不画分隔线
            b.divider.visibility =
                if (bindingAdapterPosition == items.size - 1) View.GONE else View.VISIBLE
        }
    }

    private companion object {
        const val PARSE_PENDING = "pending"
        const val PARSE_FAILED = "failed"

        /** 按压缩放幅度（规范 11.4，原型 scale .985）。 */
        const val PRESS_SCALE = 0.985f
    }
}
