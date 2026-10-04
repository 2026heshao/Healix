package com.healix.app.ui

import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.ActivityMainBinding
import com.healix.app.databinding.ItemEventBinding
import com.healix.app.db.EventEntity
import com.healix.app.db.PresetEntity
import com.healix.app.notify.AppEvent
import com.healix.app.notify.AppEventBus
import com.healix.app.notify.EventText
import kotlinx.coroutines.launch

/**
 * 主界面（设计规范系统 4.1 + 11.1 v6）。
 *
 * 核心原则：进入 300ms 后自动弹键盘并聚焦输入区 —— 这是"打开即记"的摩擦下限。
 *
 * v6：本 Activity 现在承载两个 Tab 页 ——
 * 「记录」（pageHome，原主界面）与「我的」（minePage，管理类功能归宿）。
 * Tab 互切走 in_tab 转场（同 Activity 内 View 动画，可靠重播）；
 * 「助理」为独立 ChatActivity，经全局 TabBar 切换；二级页统一 in_fwd/in_back。
 */
class MainActivity : AppCompatActivity() {

    // lateinit 绑定与 vm 视图绑定均由 MainViewModel 持有状态
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: EventAdapter
    private lateinit var vm: MainViewModel

    /** 当前 Tab（TabBar.TAB_RECORD / TAB_MINE）。 */
    internal var currentTab: Int = TabBar.TAB_RECORD

    /** 左滑手势控制器：全局单开（所有行共享 1 个实例）。 */
    private lateinit var swipe: SwipeController

    private lateinit var minePage: MinePage

    /**
     * 最近一次的 UI 状态。
     *
     * 用于让 offlineBar 的点击行为与当前语义匹配 —— 同一条提示条
     * 承载"离线"和"未配置"两种语义，必须知道现在是哪一种才能决定
     * 点了之后是"重试"还是"去设置页"。
     */
    private var lastUiState: MainUiState = MainUiState.Idle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = MainViewModel(HealixApp.from(this))

        swipe = SwipeController(this)

        adapter = EventAdapter(
            onEdit = { entity ->
                EventEditSheet.newInstance(entity.clientEventId)
                    .show(supportFragmentManager, EventEditSheet.TAG)
            },
            onRetry = { entity -> vm.retry(entity) },
            onDelete = { entity -> deleteWithUndo(entity) },
            swipe = swipe,
        )

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.setHasFixedSize(false)

        binding.dateLabel.text = HealixDate.todayLabel(this)

        binding.btnSend.setOnClickListener { submit() }
        binding.btnSettings.setOnClickListener {
            TabBar.openSecondary(this, Intent(this, SettingsActivity::class.java))
        }
        binding.planBar.setOnClickListener {
            TabBar.openSecondary(this, Intent(this, PlanReviewActivity::class.java))
        }
        binding.nudgeBar.setOnClickListener { focusInput() }

        // 状态行：整行进入状态详情页（规范 §9.2）。有信号时默认落在「身体」段，
        // 否则落在「运动」段 —— 入口决定默认段，用户不用再猜。
        binding.statusRow.setOnClickListener {
            val tab = if (binding.statusRow.tag == TAB_BODY) TAB_BODY else TAB_EXERCISE
            TabBar.openSecondary(
                this,
                Intent(this, StatusDetailActivity::class.java)
                    .putExtra(StatusDetailActivity.EXTRA_DEFAULT_TAB, tab),
            )
            // 进了状态页就算看过了 → 已读后必须切回摘要态（规范 §9.2）
            vm.acknowledgeSignals()
        }

        // 状态提示条点击：按当前语义分流（离线 → 重试；未配置 → 去设置）
        binding.offlineBar.setOnClickListener {
            if (lastUiState == MainUiState.NotConfigured) {
                TabBar.openSecondary(this, Intent(this, SettingsActivity::class.java))
            } else {
                vm.retryFailedPending()
            }
        }

        // ── v6：全局 3 Tab（记录 / 助理 / 我的）──
        minePage = MinePage(this, binding.minePage.root)
        minePage.bind(
            onOpenStatus = { vm.acknowledgeSignals() },
        )
        currentTab = intent.getIntExtra(TabBar.EXTRA_TAB, TabBar.TAB_RECORD)
        TabBar.bind(
            this,
            currentTab,
            onRecord = { showTab(TabBar.TAB_RECORD) },
            onMine = { showTab(TabBar.TAB_MINE) },
        )
        showTabImmediate(currentTab)

        // 系统返回键（Tab 页返回栈）：「我的」页按返回 = 切回记录 tab（原型 go()
        // 语义：tab 平级、返回不退出）；已是记录 tab 才退出 App。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTab == TabBar.TAB_MINE) {
                    showTab(TabBar.TAB_RECORD)
                } else {
                    finish()
                }
            }
        })

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

        // 进入 300ms 后自动弹键盘（规范硬要求：打开即弹键盘、光标在输入框）
        binding.input.postDelayed({ focusInput() }, 300)
    }

    /**
     * 从助理页切「记录 / 我的」时，TabBar.chatTo 用 CLEAR_TOP|SINGLE_TOP 重开本页。
     * 若本页已在栈顶（聊天前就是从主界面进的），系统走 onNewIntent 而非 onCreate ——
     * 不在这里读 EXTRA_TAB，extra 就永远没人接，页面停在旧 Tab（实测 bug）。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = intent.getIntExtra(TabBar.EXTRA_TAB, -1)
        if (tab == TabBar.TAB_RECORD || tab == TabBar.TAB_MINE) {
            showTab(tab)
        }
        // 桌面小工具「记一笔」：本页已在栈顶时走这里 —— 记录 tab 下补聚焦速记框
        if (intent.getBooleanExtra(EXTRA_FOCUS_INPUT, false) &&
            currentTab == TabBar.TAB_RECORD
        ) {
            binding.input.postDelayed({ focusInput() }, 200)
        }
    }

    /**
     * SAF 回传（v6 迁移）：导出备份入口已从设置页迁到「我的」页（11.1），
     * 发起方变成 MainActivity —— 必须在这里转发给 ExportWriter，
     * 否则用户选完路径后 pendingPayload 永远挂着、文件不会写入（静默失败）。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (ExportWriter.onActivityResult(this, requestCode, resultCode, data?.data)) {
            val ok = resultCode == RESULT_OK
            android.widget.Toast.makeText(
                this,
                getString(if (ok) R.string.export_success else R.string.export_failed),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /**
     * Tab 平级切换（11.2 in_tab）：透明度 0→1 + translateY(8dp)→0。
     * 原型 reflow 的 Android 等价：先取消旧动画、重置起始值再重播，
     * 同页重复切换直接跳过（不重播）。
     */
    private fun showTab(target: Int) {
        if (currentTab == target) return
        currentTab = target
        // 同 Activity 内互切后 tabbar 高亮必须跟着走（bind 只在 onCreate 高亮一次）
        TabBar.select(this, target)
        playInTab(if (target == TabBar.TAB_MINE) binding.minePage.root else binding.pageHome)
        val outgoing = if (target == TabBar.TAB_MINE) binding.pageHome else binding.minePage.root
        outgoing.visibility = View.GONE
    }

    private fun showTabImmediate(target: Int) {
        binding.pageHome.visibility = if (target == TabBar.TAB_RECORD) View.VISIBLE else View.GONE
        binding.minePage.root.visibility =
            if (target == TabBar.TAB_MINE) View.VISIBLE else View.GONE
    }

    private fun playInTab(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = resources.getDimensionPixelSize(R.dimen.tab_shift).toFloat()
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(TAB_ANIM_MS)
            .withEndAction {
                view.alpha = 1f
                view.translationY = 0f
            }
            .start()
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

    override fun onResume() {
        super.onResume()
        // 通知栏录入可能在本页不可见时发生；Room Flow 会自动推新数据，
        // 这里只需在回到前台时确认常驻通知副标题与今日状态一致。
        vm.refreshNudgeSubtitle()
        // 规则扫描走"打开时计算"，不依赖后台定时器（PRD §7.4）。
        // 整个流程 0 次 AI 调用，纯本地。
        vm.scanSignals()
        // 桌面小工具：回前台推一次（覆盖跨天 / 跨周后本周口径变化，app-pushes-updates）
        com.healix.app.widget.HealixWidgetProvider.push(this)
    }

    private fun focusInput() {
        binding.input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.input, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * 状态行右侧 chevron 取色：摘要态 `text_3`、信号态 `accent`（规范 §9.2）。
     *
     * 每次都用同一个 drawable 实例 `setTint` —— 这里只有一个 ImageView 用它，
     * 不存在共享可变状态被串改的问题。
     */
    private fun tintChevron(colorRes: Int) {
        binding.statusChevron.drawable?.setTint(ContextCompat.getColor(this, colorRes))
    }

    private fun submit() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.input.setText("")
        vm.submit(text)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // 进程内事件（通知栏录入 → 前台反馈，AppEventBus）：
                // 数据刷新由 Room Flow 承担，这里只接「即时提示」职责 ——
                // RequestFocusInput（点通知兜底聚焦速记框）、Failed（失败原因轻提示）。
                launch {
                    AppEventBus.events.collect { event ->
                        when (event) {
                            is AppEvent.RequestFocusInput -> focusInput()
                            is AppEvent.Failed -> android.widget.Toast.makeText(
                                this@MainActivity, event.reason,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            // Recorded / PendingQueued / Undone：列表已由 Room Flow 自动刷新
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
                                // 未配置 ≠ 离线。用同一条提示条，但文案与动作不同：
                                // 点一下直接去设置页（而不是让用户自己找）
                                binding.spinner.visibility = View.GONE
                                binding.stateLabel.visibility = View.GONE
                                binding.offlineBar.text = getString(R.string.no_provider_config)
                                binding.offlineBar.visibility = View.VISIBLE
                            }
                        }
                    }
                }

                launch { vm.presets.collect { renderPresets(it) } }

                // 状态行：两态互斥渲染（规范 §9.2）；「我的」页副行同源同步
                launch {
                    vm.homeStatus.collect { status ->
                        minePage.bindStatus(this@MainActivity, status)
                        when (status) {
                            is HomeStatus.Signal -> {
                                binding.statusRow.tag = TAB_BODY
                                binding.statusText.text = status.text
                                binding.statusText.setTextColor(
                                    ContextCompat.getColor(this@MainActivity, R.color.accent),
                                )
                                tintChevron(R.color.accent)
                            }
                            is HomeStatus.Summary -> {
                                binding.statusRow.tag = TAB_EXERCISE
                                binding.statusText.text = status.text
                                binding.statusText.setTextColor(
                                    ContextCompat.getColor(this@MainActivity, R.color.text_2),
                                )
                                tintChevron(R.color.text_3)
                            }
                            HomeStatus.Empty -> {
                                binding.statusRow.tag = TAB_EXERCISE
                                binding.statusText.setText(R.string.status_none)
                                binding.statusText.setTextColor(
                                    ContextCompat.getColor(this@MainActivity, R.color.text_3),
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
                            // 一句话拆成多条时，"列表自己多长出来两行"必须有交代（PRD §15.4）
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
            }
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

    companion object {
        /**
         * 桌面小工具「记一笔」：打开本页并聚焦速记框（小工具侧 extra，
         * 见 HealixWidgetProvider.logIntent；冷启动由 onCreate 300ms 自动聚焦兜底）。
         */
        const val EXTRA_FOCUS_INPUT = "healix.extra.FOCUS_INPUT"

        /**
         * 状态行进入状态详情页时携带的默认段（规范 §9.4）：
         * 信号态进来默认「身体」段（用户要看的就是那条信号），摘要态默认「运动」段。
         */
        private const val TAB_EXERCISE = "exercise"
        private const val TAB_BODY = "body"

        /** Tab 平级切换时长（规范 11.2，对应原型 --dur_normal）。 */
        private const val TAB_ANIM_MS = 240L
    }
}

/**
 * 记录列表适配器。
 * 无卡片、无阴影、无彩色徽章 —— 靠 6px 圆点 + 1dp 分隔线组织信息。
 *
 * v6：每行包 swipewrap（item_event.xml），左滑露「编辑/删除」（11.3）；
 * 按压缩放双反馈（11.4）。手势由共享的 [SwipeController] 统一裁决
 * （全局单开 + 300ms click 屏蔽）。
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

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val b = ItemEventBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false,
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
            //    并解除 SwipeController 对这个视图的滑开跟踪（出屏滑开行滚回来
            //    = 已回弹的干净行）。──
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
                    //   染红会让用户以为 App 坏了或记录没了，而它好端端躺在列表里。
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

/** 日期 / 时间格式化。集中一处，避免各 Activity 各写一遍。 */
internal object HealixDate {

    private val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun todayLabel(context: android.content.Context): String {
        val d = java.time.LocalDate.now()
        return "${d.monthValue}月${d.dayOfMonth}日 ${WEEKDAYS[d.dayOfWeek.value - 1]}"
    }

    fun timeLabel(ts: Long): String {
        val t = java.time.Instant.ofEpochMilli(ts)
            .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        return "%02d:%02d".format(t.hour, t.minute)
    }

    /** 会话日期标签：今天 / 昨天 / M月d日 */
    fun sessionLabel(date: java.time.LocalDate): String {
        val today = java.time.LocalDate.now()
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> "${date.monthValue}月${date.dayOfMonth}日"
        }
    }
}
