package com.healix.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
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
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.agent.AgentProposal
import com.healix.app.agent.LogProposal
import com.healix.app.databinding.FragmentAssistantBinding
import com.healix.app.db.ChatMessageEntity
import com.healix.app.ui.widget.InputBarHeightAnimator
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 对话页（设计规范系统 4.2）。v8 需求 1/2：由 [ChatActivity] 迁为
 * **常驻 Fragment**（`add` 一次 + `show/hide` 切换），底部 3 Tab 从此
 * 不再有任何 Activity 窗口转场 —— 这是「Tab 点击即时响应」的根因修复。
 *
 * 迁移等价性说明（相对旧 ChatActivity 的逐点对照）：
 * - `supportFragmentManager` → `childFragmentManager`（[ActionConfirmSheet]
 *   等 BottomSheetDialogFragment 挂在本 Fragment 的子栈上）；
 * - `TabBar.bind(this, TAB_ASSISTANT)` 删除 —— tabbar 唯一挂在宿主
 *   [MainActivity] 布局里，由宿主统一切换与高亮；
 * - `onResume` 的跨零点 / 跨时段刷新在 `show/hide` 下不会被触发
 *   （Fragment 常驻 RESUMED），改由 [onHiddenChanged] 驱动 —— 见 [onVisible]；
 * - `ChatViewModel` 从手动构造改为 `ViewModelProvider`（Activity 作用域），
 *   旋转重建后不再丢会话与在途请求（旧实现在 Activity 里重建即丢）。
 *
 * **交互决策：左右气泡**（用户靠右 text_1 实底白字，助理靠左 surface 底描边），
 * 只用既有中性色、无阴影、圆角仍为全局唯一 8dp —— 设计规范 v3 未被破坏。
 */
class AssistantFragment : Fragment() {

    private var _binding: FragmentAssistantBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ChatAdapter

    /**
     * Activity 作用域 VM：`ChatViewModel(app: Application)` 单参构造可被
     * 默认 SavedStateViewModelFactory 解析，旋转重建不丢状态。
     */
    private val vm: ChatViewModel by lazy {
        ViewModelProvider(requireActivity())[ChatViewModel::class.java]
    }

    /** §5.3：快捷问答第 2 行的当前时段词。点按回调读本字段，刷新后即用新文案发送。 */
    private var quickMealText: String = ""

    /**
     * 拟稿确认在 Tab 隐藏期间到达时先暂存，切回可见再弹（否则会盖在别的 Tab 上）。
     * v0.3 B6：类型由 `LogProposal` 泛化为 [AgentProposal]（记录 / 计划 / 目标 / 删除四类草案；
     * 2026-10-07 P1 再扩画像 / 设置两类，P2 再扩提醒一类，共七类）。
     */
    private var pendingProposal: AgentProposal? = null

    /**
     * 本次消息发射是否来自「我自己刚发送」—— 置位后下一次 [observe] 的 messages
     * 发射会把那条 user 消息顶到可视区顶部（真机问题 2(a)）。
     *
     * 为什么用标志而不是比较 id：`vm.send()` 返回时新行还没落库（persist-first 是
     * 异步的），拿不到 id；而 messages 的首次发射可能**不止**含这一条（Room Flow
     * 批量发射），用「最后一次发射里最后一条 user 消息」作为锚点更稳。
     * 一次性消费：用掉即复位，后续 AI 回复的发射走常规「贴底跟随」。
     */
    private var pendingSelfAnchor: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAssistantBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ChatAdapter()

        val lm = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.messageList.layoutManager = lm
        binding.messageList.adapter = adapter

        binding.sessionLabel.text = HealixDate.sessionLabel(LocalDate.now())
        binding.btnSend.setOnClickListener { send() }
        binding.sessionLabel.setOnClickListener { anchor -> showSessionMenu(anchor) }

        // v6（11.4）：快捷入口按压缩放反馈
        binding.quickToday.bindPressScale()
        binding.quickDinner.bindPressScale()
        binding.quickWeek.bindPressScale()

        // 快捷入口三行：仅在当日会话为空时出现，一旦有消息即隐藏，不再恢复。
        // 第 2 行按时段动态（微扩展）：早[5,11) 早餐 / 午[11,14) 午餐 /
        // 下午[14,18) 加餐 / 晚[18,23) 晚餐 / 深夜[23,5) 夜间饮食。
        // 纯本地换文案，不调 AI、不占配额。
        val quickMeal = getString(timeBucketMealRes())
        quickMealText = quickMeal
        binding.quickDinner.text = quickMeal
        binding.quickToday.setOnClickListener { send(getString(R.string.quick_today_ok)) }
        binding.quickDinner.setOnClickListener { send(quickMealText) }
        binding.quickWeek.setOnClickListener { send(getString(R.string.quick_week)) }

        // 微扩展 D：模型调用失败时，提示条变成重试入口
        binding.simplifiedBar.setOnClickListener { vm.retryLast() }

        // ── P0-1 发送键双态：空文本 → text_3，非空 → accent（与记录页同一 selector）──
        binding.input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                binding.btnSend.isSelected = !s.isNullOrEmpty()
            }
        })

        // ── P0-2 输入条高度自适应：增高时消息列表跟到底部（有消息才滚）──
        InputBarHeightAnimator.bind(binding.input, binding.inputBar) {
            val adapter = binding.messageList.adapter
            val last = (adapter?.itemCount ?: 0) - 1
            if (last >= 0) binding.messageList.smoothScrollToPosition(last)
        }

        observe()
    }

    /**
     * 首次进入（Activity onResume 链）与从其它 Tab 切回共用这一个入口。
     * `show/hide` 不会触发 onResume（Fragment 常驻 RESUMED），所以跨零点
     * 顺延与时段词刷新必须挂在 [onHiddenChanged] 上。
     */
    fun onVisible() {
        vm.refreshToday()
        if (vm.isToday.value) {
            binding.sessionLabel.text = HealixDate.sessionLabel(LocalDate.now())
        }
        // §5.3：跨时段停留后重算快捷问答第 2 行的时段词（不做定时器）
        refreshQuickMeal()
        // Tab 隐藏期间到达的拟稿确认，在这里补弹
        pendingProposal?.let { p ->
            pendingProposal = null
            showProposalSheet(p)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) onVisible()
    }

    /**
     * §5.3：快捷问答第 2 行时段词在进入时取一次，跨时段停留会过期。
     * 回前台/切回时重算并刷新文案；点按回调读 [quickMealText]。
     */
    private fun refreshQuickMeal() {
        val text = getString(timeBucketMealRes())
        if (text == quickMealText) return
        quickMealText = text
        binding.quickDinner.text = text
    }

    /**
     * 微扩展 B：会话日期菜单。列出所有有消息的日期（倒序），点选切换只读查看。
     * 历史会话禁用输入与发送（发送永远写今天的会话）。
     *
     * ⚠️ 数据源 [ChatViewModel.sessionDates] 是 `Eagerly` 共享的（见该处注释）——
     *    本方法读 `.value` 即为真值。**不得**给它换回 `WhileSubscribed`：
     *    那种共享模式下无订阅者时上游不启动，`.value` 恒为初始 `emptyList()`，
     *    而全仓只有本方法读它 → 菜单永远提前 return（真机表现：「点『今天』毫无反应」，
     *    且无任何异常日志，最容易被误判为"功能没做"）。
     */
    private fun showSessionMenu(anchor: View) {
        val dates = vm.sessionDates.value
        if (dates.isEmpty()) return
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        dates.forEach { date ->
            popup.menu.add(HealixDate.sessionLabel(java.time.LocalDate.parse(date)))
                .setOnMenuItemClickListener {
                    vm.selectSession(date)
                    true
                }
        }
        popup.show()
    }

    private fun send(text: String? = null) {
        val content = (text ?: binding.input.text?.toString()?.trim().orEmpty()).trim()
        // 空文本：仅聚焦（发送键灰态 = 可点但暂不可用）
        if (content.isEmpty()) {
            focusInput()
            return
        }
        // 先判断接受与否，再决定是否清空 —— 本项目 persist-first：
        // 走到 persist 的路径返回 true，原文一定入库（回填会造成重复行）；
        // 被守卫拦下的返回 false，原文必须留在输入框。
        val accepted = vm.send(content)
        if (accepted) {
            // 2(a)：这条消息落库后要被顶到可视区顶部（一次性标志，见字段 KDoc）
            pendingSelfAnchor = true
            // P2-9 发送点击轻触觉
            binding.btnSend.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            binding.input.setText("")
            // 清空后**只** requestFocus：键盘已在，重复 showSoftInput 会闪，焦点保持即可连输
            binding.input.requestFocus()
            binding.quickGroup.visibility = View.GONE
        } else {
            // 被守卫拦下（历史会话只读）：不发送、原文留在输入框，只聚焦 + 一行 negative 提示
            focusInput()
            binding.btnSend.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            binding.stateLabel.visibility = View.VISIBLE
            binding.stateLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.negative))
            binding.stateLabel.text = getString(R.string.state_send_rejected)
            binding.stateLabel.announceForAccessibility(getString(R.string.state_send_rejected))
        }
    }

    /** 聚焦输入框并弹键盘。与 [RecordFragment.focusInput] 同构。 */
    fun focusInput() {
        if (_binding == null) return
        binding.input.requestFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as InputMethodManager
        imm.showSoftInput(binding.input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    vm.messages.collect { list ->
                        val wasAtBottom = isListAtBottom()
                        adapter.submit(list)
                        if (list.isNotEmpty()) {
                            binding.emptyHint.visibility = View.GONE
                            // A1（2026-10-09）：落库路径与流式路径**口径统一** ——
                            // 旧实现这里无条件 `scrollToPosition(itemCount - 1)`，
                            // 而流式路径有 [isListAtBottom] 守卫。于是用户上翻回看历史
                            // 时，任何一条新消息（含 AI 回复落库）都会把他硬拽回底部。
                            // 现在只在**原本就贴底**（或本次发的是自己刚发的消息）时跟随。
                            // 2(a)：自己刚发的消息 → 顶到可视区顶部，让 AI 回复
                            // 从上往下长，而不是把气泡顶在屏幕中间。
                            if (pendingSelfAnchor) {
                                pendingSelfAnchor = false
                                scrollLastUserToTop()
                            } else if (wasAtBottom) {
                                binding.messageList.scrollToPosition(adapter.itemCount - 1)
                            }
                        } else {
                            binding.emptyHint.visibility = View.VISIBLE
                        }
                        // 快捷问答三行：只在"今天的空会话"出现；历史会话一律不出现
                        updateQuickGroup(list.isNotEmpty())
                    }
                }

                // 流式正文（2026-10-08）：增量到达 → 刷新末尾临时气泡并跟随到底。
                // ⚠️ 只在「原本就贴着底部」时自动滚动 —— 用户上翻回看时不该被拽回去。
                launch {
                    vm.streaming.collect { text ->
                        val atBottom = isListAtBottom()
                        adapter.setStreaming(text)
                        if (atBottom && !text.isNullOrEmpty() && adapter.itemCount > 0) {
                            binding.messageList.scrollToPosition(adapter.itemCount - 1)
                        }
                    }
                }

                // 中间轮丢弃（2026-10-09，问题 2(b)(c)）：工具轮顺手写的正文要**立刻**
                // 撤下。不能指望 streaming 的收口语义 —— 那条路径在等落库消息接住，
                // 而中间轮根本没有落库消息会来，气泡会一直挂着。
                launch {
                    vm.streamDiscard.collect {
                        adapter.discardStreaming()
                    }
                }

                // 微扩展 B：查看历史会话 = 只读。输入条/发送/预设条全部禁用
                launch {
                    vm.isToday.collect { today ->
                        binding.input.isEnabled = today
                        binding.btnSend.isEnabled = today
                        binding.btnSend.alpha = if (today) 1f else 0.4f
                        binding.presetScroll.visibility =
                            if (today && binding.presetRow.childCount > 0) View.VISIBLE else View.GONE
                        if (!today) {
                            val date = java.time.LocalDate.parse(vm.selectedDate.value)
                            binding.sessionLabel.text =
                                getString(R.string.chat_viewing_past, HealixDate.sessionLabel(date))
                        } else {
                            binding.sessionLabel.text = HealixDate.sessionLabel(java.time.LocalDate.now())
                        }
                    }
                }

                // 微扩展 C：预设快捷条渲染（与记录页同一 item_preset 样式）
                launch {
                    vm.presets.collect { presets ->
                        binding.presetRow.removeAllViews()
                        for (preset in presets) {
                            val tv = layoutInflater.inflate(
                                R.layout.item_preset, binding.presetRow, false,
                            ) as android.widget.TextView
                            tv.text = preset.name
                            tv.bindPressScale()
                            tv.setOnClickListener { vm.logPreset(preset) }
                            binding.presetRow.addView(tv)
                        }
                        val show = presets.isNotEmpty() && vm.isToday.value
                        binding.presetScroll.visibility = if (show) View.VISIBLE else View.GONE
                    }
                }

                // 微扩展 C：预设落库结果反馈
                launch {
                    vm.presetToast.collect { text ->
                        android.widget.Toast.makeText(requireContext(), text,
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }

                // S3–S4：propose_log 拟稿确认。⚠️ Tab 隐藏期间到达要先暂存 ——
                // 隐藏时弹窗会盖在别的 Tab 上；切回可见时由 [onVisible] 补弹。
                launch {
                    vm.proposal.collect { proposal ->
                        if (isHidden) {
                            pendingProposal = proposal
                        } else {
                            showProposalSheet(proposal)
                        }
                    }
                }

                // v0.3 B6 / 2026-10-07 P2：可撤销的写入确认后弹内联撤销条（5 秒可退回）。
                // 文案由载荷自带（[UndoAction.label]）—— 这里不再按写入类型取字符串，
                // 新增一种可撤销写入时不需要再回来改这个 Fragment。
                launch {
                    vm.undo.collect { action ->
                        UndoBar.bind(
                            binding.undoBar,
                            binding.undoLeft,
                            binding.undoAction,
                            action.label,
                            action.label,
                        ) { vm.revert(action) }
                    }
                }

                // S3–S4：拟稿落库结果反馈
                launch {
                    vm.proposalToast.collect { text ->
                        android.widget.Toast.makeText(requireContext(), text,
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }

                launch {
                    vm.uiState.collect { state ->
                        when (state) {
                            ChatUiState.Idle -> {
                                binding.spinner.visibility = View.GONE
                                binding.stateLabel.visibility = View.GONE
                                binding.simplifiedBar.visibility = View.GONE
                            }
                            ChatUiState.Thinking -> {
                                binding.spinner.visibility = View.VISIBLE
                                binding.stateLabel.visibility = View.VISIBLE
                                // 复位颜色：上一次「没发出去」的 negative 不能带到正常状态行上
                                binding.stateLabel.setTextColor(
                                    ContextCompat.getColor(requireContext(), R.color.text_2),
                                )
                                binding.stateLabel.text = getString(R.string.state_thinking)
                            }
                            is ChatUiState.Queued -> {
                                binding.spinner.visibility = View.VISIBLE
                                binding.stateLabel.visibility = View.VISIBLE
                                binding.stateLabel.setTextColor(
                                    ContextCompat.getColor(requireContext(), R.color.text_2),
                                )
                                // 限流必须显示预估秒数（规范 3.10）
                                binding.stateLabel.text =
                                    getString(R.string.state_queued, state.seconds)
                            }
                            ChatUiState.QuotaExhausted -> {
                                binding.spinner.visibility = View.GONE
                                binding.stateLabel.visibility = View.GONE
                            }
                            ChatUiState.Degraded -> {
                                binding.spinner.visibility = View.GONE
                                binding.stateLabel.visibility = View.GONE
                                // 降级用 text_2 提示，不用 negative —— 这是预期行为不是错误
                                binding.simplifiedBar.visibility = View.VISIBLE
                            }
                        }
                    }
                }

                // 微扩展 D：模型失败 → 提示条换成可点的重试文案
                launch {
                    vm.retryAvailable.collect { retryable ->
                        binding.simplifiedBar.text = if (retryable) {
                            getString(R.string.chat_retry_hint)
                        } else {
                            getString(R.string.state_simplified)
                        }
                    }
                }
            }
        }
    }

    /**
     * 拟稿确认（v0.3 B6 泛化；2026-10-07 P1 扩到六类、P2 扩到七类）：记录 / 计划 / 目标 /
     * 删除 / 画像 / 设置 / 提醒七类草案共用同一个 [ActionConfirmSheet]（§1.3.3）。记录草稿沿用原
     * 「确认记录」文案（**行为不变**）；其余各类以各自的 [AgentProposal.summary] 作正文
     * （message = draft 的 summary）。
     * 确认 → [ChatViewModel.confirmProposal]；取消 / 下拖 → [ChatViewModel.cancelProposal]
     * （只回填 `tool_calls.approved = 0`，不写任何业务数据）。
     */
    private fun showProposalSheet(proposal: AgentProposal) {
        val title: String
        val message: String
        if (proposal is LogProposal) {
            title = getString(R.string.proposal_sheet_title)
            message = getString(R.string.proposal_confirm, proposal.rawText)
        } else {
            title = getString(R.string.proposal_sheet_title_action)
            message = proposal.summary
        }
        val sheet = ActionConfirmSheet.newInstance(title, message)
        sheet.onConfirm = { vm.confirmProposal(proposal) }
        sheet.onCancel = { vm.cancelProposal(proposal) }
        sheet.show(childFragmentManager, ActionConfirmSheet.TAG)
    }

    /** 快捷问答三行：仅查看今天且当前会话为空时出现。 */
    private fun updateQuickGroup(hasMessages: Boolean) {
        val show = !hasMessages && vm.isToday.value
        binding.quickGroup.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * 消息列表是否贴着底部（末尾两条之内即算贴底）。
     *
     * 流式增量只在贴底时自动跟随滚动 —— 用户上翻回看历史消息时，不该被每一段增量
     * 拽回底部（那会让"边生成边读旧消息"变得不可能）。
     */
    private fun isListAtBottom(): Boolean {
        if (_binding == null) return false
        val lm = binding.messageList.layoutManager as? LinearLayoutManager ?: return true
        return lm.findLastVisibleItemPosition() >= adapter.itemCount - 2
    }

    /**
     * 把自己刚发出的那条 user 消息顶到**可视区顶部**（真机问题 2(a)）。
     *
     * 为什么不能只写 `scrollToPositionWithOffset(row, 0)`：本列表是
     * `LinearLayoutManager(stackFromEnd = true)`，**内容不足一屏时整体贴底** ——
     * 此时可滚动距离为 0，任何 offset 都会被夹回去，用户看到气泡仍停在屏幕中间
     * （实测约 y=770 / 1600，上方整片空白）。
     *
     * 所以按「内容是否够一屏」分流：
     * - **够一屏** → `scrollToPositionWithOffset(row, 0)` 真能把它顶到顶；
     * - **不够一屏** → 关掉 `stackFromEnd`（内容改为顶部对齐），此时该行天然就是
     *   顶部第一行，随后 AI 回复向下生长，正好是期望的阅读方向。
     *
     * 行号必须由 [ChatAdapter.lastUserRowIndex] 提供：W1 之后列表混着时间分隔头，
     * 消息下标 ≠ adapter position（直接用消息数会滚错行）。
     */
    private fun scrollLastUserToTop() {
        if (_binding == null) return
        val lm = binding.messageList.layoutManager as? LinearLayoutManager ?: return
        val row = adapter.lastUserRowIndex()
        if (row < 0) return
        binding.messageList.post {
            if (_binding == null) return@post
            // ⚠️ 每次**按当前内容重新裁决**，而不是一次性关掉就完事：
            //    `stackFromEnd` 一旦永久置 false，长对话下次进入会停在**最旧**一条，
            //    破坏「打开即看最新消息」的既有行为。
            //    内容 ≤ 一屏 → 贴底布局无从上滚，改顶部对齐（问题 2(a) 主因）；
            //    内容够一屏 → 恢复贴底布局，再用 offset 把该行顶到顶。
            val contentShort = binding.messageList.computeVerticalScrollRange() <=
                binding.messageList.height
            lm.stackFromEnd = !contentShort
            lm.scrollToPositionWithOffset(row, 0)
        }
    }

    /**
     * 时段 → 第 2 行快捷问句资源（左闭右开：早[5,11) 午[11,14)
     * 下午[14,18) 晚[18,23) 深夜[23,5)）。小时取本机 24h 制时间。
     */
    private fun timeBucketMealRes(): Int {
        val hour = java.time.LocalTime.now().hour
        return when (hour) {
            in 5..10 -> R.string.quick_breakfast
            in 11..13 -> R.string.quick_lunch
            in 14..17 -> R.string.quick_afternoon
            in 18..22 -> R.string.quick_dinner
            else -> R.string.quick_late_night
        }
    }

    /**
     * 复点当前 Tab 时由宿主 [MainActivity] 调用（v6 §5.1：再次点当前 Tab → 列表滚回顶部）。
     * 助理页的"列表"= 消息流，滚回顶部即回看本会话最早的消息。
     * 视图可能已销毁（`_binding == null`）→ 空值守卫，不崩。
     */
    fun scrollToTop() {
        _binding?.messageList?.smoothScrollToPosition(0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * 对话消息适配器：左右气泡。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 设计变更记录（2026-10-03）
 * ══════════════════════════════════════════════════════════════════════════
 * 原实现是「无气泡，只靠对齐 + 灰度区分身份」（见旧版类注释的"核心决策"）。
 * 用户反馈要**明确的左右气泡**，遂改为气泡形态。
 *
 * 变更**没有**破坏设计规范 v3，理由是气泡只用了既有中性色：
 *   - 用户气泡：text_1 (#1A1918) 实底 + 白字
 *   - 助理气泡：surface (#FFFFFF) 底 + 1dp line 描边 + text_1 字
 * 没有引入任何新颜色、没有阴影、圆角仍是全局唯一值 8dp、
 * accent 仍只出现在按钮/进度条上（不被气泡稀释）。
 *
 * v6（2026-10-07）：气泡语义化 —— 用户气泡改 `primary_container` 衬底 +
 * `on_primary_container` 字（不对称圆角 16/4/16/16），助理气泡 `surface` 实底 +
 * `card_elev_1`（圆角 4/16/16/16），最大宽 82%，正文 15sp/行高 1.6；
 * 并新增工具反馈条（`role=tool`：`surface_variant` 底 + 8dp 圆角 + 左侧 2dp
 * `primary` 竖线，状态后缀「完成 / 待确认」）。背景件由 F 层统一绘制。
 *
 * 结构：外层 LinearLayout 负责"靠哪边 + 最大宽度"，内层 TextView 带气泡背景。
 * 之所以套一层而不是直接给 TextView 设背景：
 *   TextView 用 wrap_content + background 时，padding 会被算进宽度，
 *   导致同一条消息在"短文本"和"长文本"下视觉边距不一致。
 *   外层控制对齐、内层控制留白，职责分开更稳。
 *
 * W1（2026-10-07 用户反馈「布局机械呆板」）：新增时间分隔行 —— 列表按
 * [TIME_GAP_MS]（30 分钟）分节，节首插一条居中弱时间头（12sp text_3、无气泡），
 * 打破"所有消息平铺直叙"的时间感缺失。数据侧用 sealed 展示项（[Row]），
 * `submit(list)` 一次性预计算；拟稿确认语逐字重复问题在 HealthAgent 侧轮换。
 */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    /**
     * 展示项（W1 时间节奏，2026-10-07 用户反馈「平铺直叙显机械」）：
     * 首条消息前必有时间头；此后与上一条消息相隔 > [TIME_GAP_MS] 再插一条。
     * 时间头 = 弱分隔（12sp、text_3、居中、无气泡），给列表"时间在流动"的节奏。
     */
    private sealed class Row {
        /** 时间分隔头（`at` = 该组首条消息的 `createdAt`，仅用于格式化 `HH:mm`）。 */
        data class Time(val at: Long) : Row()

        /** 一条消息。 */
        data class Msg(val m: ChatMessageEntity) : Row()
    }

    private var rows: List<Row> = emptyList()

    /**
     * 流式临时气泡（2026-10-08）：尚未落库的助理正文。
     *
     * `null` / 空串 = 不显示。列表末尾会额外挂一条 [TYPE_ASSISTANT] 行渲染它，
     * [itemCount] = [rows]`.size + 1`（仅当它可见）。
     */
    private var streamText: String? = null

    /** 流式是否已收口（VM 把 streaming 置回 `null`）。见 [submit] 的交接口径。 */
    private var streamDone: Boolean = false

    /** 临时气泡当前是否挂在末尾。所有索引计算都以它为准。 */
    private var streamVisible: Boolean = false

    /** `HH:mm`（同日会话内不需日期；仅主线程绑定使用，SimpleDateFormat 无并发问题）。 */
    private val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

    /** 消息列表 → 展示项列表（首条前必有时间头；间隔 > 30 分钟再插一条）。 */
    private fun buildRows(list: List<ChatMessageEntity>): List<Row> {
        if (list.isEmpty()) return emptyList()
        val out = ArrayList<Row>(list.size + 4)
        var lastAt = 0L
        for (m in list) {
            if (out.isEmpty() || m.createdAt - lastAt > TIME_GAP_MS) {
                out.add(Row.Time(m.createdAt))
            }
            out.add(Row.Msg(m))
            lastAt = m.createdAt
        }
        return out
    }

    /**
     * 最后一条 **user** 消息在展示项里的下标，没有返回 -1。
     *
     * 2(a) 的定位锚点：发出自己的消息后要把它顶到可视区顶部，而 W1 之后列表里
     * 混着时间分隔头，消息下标与 adapter position 不再相等 —— 必须拿到**真实行号**，
     * 否则会滚错位置。
     */
    fun lastUserRowIndex(): Int =
        rows.indexOfLast { it is Row.Msg && it.m.role == ROLE_USER }

    /**
     * 落库消息更新。
     *
     * ⚠️ 流式交接（2026-10-08）：临时的流式气泡**不靠调用方掐时机撤下** —— 落库消息
     * 与「清流式态」是两条异步流（Room Flow 发射 vs StateFlow 置 `null`），谁先到
     * 不确定。若流式态先清、消息后到，中间会有一两帧**两边都没有** → 气泡闪一下。
     * 故：只有 [streamDone] 为真**且**本次 [submit] 到达时才撤临时气泡
     * （成功时正文被落库消息「接住」，降级时落的是本地模板、接不住也一并撤）。
     */
    @Suppress("NotifyDataSetChanged")
    fun submit(list: List<ChatMessageEntity>) {
        rows = buildRows(list)
        if (streamDone) {
            streamText = null
            streamDone = false
        }
        streamVisible = streamRowShouldShow()
        notifyDataSetChanged()
    }

    /**
     * 流式正文更新（`null` = 本轮流式收口）。
     *
     * 收口时**不立刻**撤气泡：落库消息可能还在路上（见 [submit]）。真正的撤下时机
     * 是下一次 [submit]。
     */
    fun setStreaming(text: String?) {
        if (text == null) {
            streamDone = true
            // 没有正文（例如未配置 / 断网这类在调用前就返回的路径）→ 无气泡可留，直接撤。
            if (streamText.isNullOrEmpty()) {
                streamText = null
                streamDone = false
            }
        } else {
            streamText = text
            streamDone = false
        }
        refreshStreamRow()
    }

    /** 临时气泡**是否该显示**：有正文，且落库消息尚未接住它。 */
    private fun streamRowShouldShow(): Boolean {
        val t = streamText
        if (t.isNullOrEmpty()) return false
        return !persistedCatchesUp(t)
    }

    /**
     * 立刻撤下临时气泡（中间轮丢弃，问题 2(b)(c)）。
     *
     * 与 [setStreaming]`(null)` 的区别：那条路径会把 [streamDone] 置位、**等**下一次
     * [submit] 来接住正文（防正常落库时闪烁）；本方法用于「这段正文永远不会落库」
     * 的场合，故直接清干净并立即移除该行。
     */
    fun discardStreaming() {
        if (!streamVisible && streamText == null) return
        streamText = null
        streamDone = false
        if (!streamVisible) return
        streamVisible = false
        // 气泡恒为末行：此刻 itemCount 已因 streamVisible 转 false 而少一，
        // 故被移除的下标就是 `rows.size`（= 当前 itemCount）。
        val idx = rows.size
        if (idx in 0..itemCount) notifyItemRemoved(idx) else notifyDataSetChanged()
    }

    /** 落库列表里最后一条**助理**消息是否已经以这段流式正文开头（含来源 / 查阅后缀行）。 */
    private fun persistedCatchesUp(streamed: String): Boolean {
        val last = rows.asReversed().firstOrNull { it is Row.Msg } as? Row.Msg ?: return false
        val m = last.m
        return m.role != ROLE_USER && m.role != ROLE_TOOL && m.content.startsWith(streamed)
    }

    /**
     * 按最新状态同步临时气泡的挂载（只做最小通知，不整表刷新）。
     *
     * ⚠️ 下标说明（问题 2 的 P2）：临时气泡**恒为最后一行**，故其位置就是
     * `rows.size`；但 [submit] 会整体重建 [rows] 并 `notifyDataSetChanged()`，
     * 若两条路径交错，裸下标可能与 RecyclerView 当下的条目数不一致 ——
     * 那会让通知越界（RecyclerView 直接抛 IndexOutOfBounds，或把末行二次绑定）。
     * 这里先按 `rows.size` 与当前 itemCount 交叉校验，越界则退化为整表刷新
     * （安全兜底，且此路径在正常时序下不会走到）。
     */
    private fun refreshStreamRow() {
        val should = streamRowShouldShow()
        if (should == streamVisible) {
            // 挂载态没变：仍在显示则只重绑末尾那一行（正文变了），否则无需通知。
            if (should) {
                val idx = rows.size
                if (idx in 0 until itemCount) notifyItemChanged(idx) else notifyDataSetChanged()
            }
            return
        }
        val idx = rows.size
        // 期望插入位 = 当前条目数（气泡追加在末尾）；期望移除位 = 末位。
        val expected = if (should) itemCount else itemCount - 1
        if (idx != expected) {
            // 与 RecyclerView 的条目数不一致（[rows] 刚被 [submit] 换过）→
            // 不做增量通知，交给整表刷新重建，避免越界 / 二次绑定。
            streamVisible = should
            notifyDataSetChanged()
            return
        }
        streamVisible = should
        if (should) notifyItemInserted(idx) else notifyItemRemoved(idx)
    }

    override fun getItemCount(): Int = rows.size + if (streamVisible) 1 else 0

    override fun getItemViewType(position: Int): Int {
        if (streamVisible && position == rows.size) return TYPE_ASSISTANT
        return when (val r = rows[position]) {
            is Row.Time -> TYPE_TIME
            is Row.Msg -> when (r.m.role) {
                ROLE_USER -> TYPE_USER
                ROLE_TOOL -> TYPE_TOOL
                else -> TYPE_ASSISTANT
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context

        // ── 时间分隔头（W1）：居中 12sp text_3、上下 10dp、无气泡背景 ──
        if (viewType == TYPE_TIME) {
            val tv = android.widget.TextView(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                gravity = android.view.Gravity.CENTER
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_3))
            }
            return VH(tv, tv)
        }

        // ── 工具反馈条（v6）：surface_variant 底 + 8dp 圆角 + 左侧 2dp primary 竖线 ──
        if (viewType == TYPE_TOOL) {
            val bar = android.view.View(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    dp(ctx, 2f).toInt(),
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundResource(R.drawable.bg_chat_toolline_bar)
            }
            val tv = android.widget.TextView(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_2))
                setPadding(
                    dp(ctx, 12f).toInt(), dp(ctx, 10f).toInt(),
                    dp(ctx, 14f).toInt(), dp(ctx, 10f).toInt(),
                )
            }
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setBackgroundResource(R.drawable.bg_toolline)
                addView(bar)
                addView(tv)
            }
            return VH(row, tv)
        }

        val text = android.widget.TextView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(dp(ctx, 6f), 1f)
            setSingleLine(false)
            // 气泡内的留白：水平 14dp、垂直 10dp
            setPadding(dp(ctx, 14f).toInt(), dp(ctx, 10f).toInt(), dp(ctx, 14f).toInt(), dp(ctx, 10f).toInt())
        }

        // 外层：限制最大宽度 82%，靠 gravity 决定左右
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(text)
        }

        return VH(row, text)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        // 末尾的流式临时气泡（未落库）走独立渲染 —— 它没有 ChatMessageEntity，
        // 也不该走「末行元数据剥离 / 长按复制 / 无障碍朗读」那套落库消息才有的逻辑。
        if (streamVisible && position == rows.size) {
            bindStreaming(holder)
            return
        }
        when (val r = rows[position]) {
            is Row.Time -> {
                holder.text.text = timeFormat.format(java.util.Date(r.at))
                // 上下各 10dp（spec W1-1）；先比较再写，避免无谓 requestLayout
                val lp = holder.row.layoutParams as RecyclerView.LayoutParams
                val pad = dp(holder.text.context, 10f).toInt()
                if (lp.topMargin != pad || lp.bottomMargin != pad) {
                    lp.topMargin = pad
                    lp.bottomMargin = pad
                    holder.row.layoutParams = lp
                }
            }

            is Row.Msg -> if (r.m.role == ROLE_TOOL) {
                bindToolLine(holder, position, r.m)
            } else {
                bindMessage(holder, position, r.m)
            }
        }
    }

    /**
     * 流式临时气泡（2026-10-08）：外观与**助理气泡逐项一致**（`surface` 实底 +
     * `card_elev_1` + 靠左 + 同最大宽度 / 留白 / 间距），差别只有三点 —— 它没有
     * 落库消息的元数据行、不提供长按复制（正文未定稿，复制半截没意义）、
     * 不做无障碍朗读（每次增量变动都读一遍是灾难）。
     */
    private fun bindStreaming(holder: VH) {
        val ctx = holder.text.context
        holder.text.text = markdownBoldSpan(streamText.orEmpty())
        holder.text.setBackgroundResource(R.drawable.bg_bubble_assistant)
        holder.text.setTextColor(ContextCompat.getColor(ctx, R.color.text_1))
        holder.text.elevation = ctx.resources.getDimension(R.dimen.card_elev_1)

        holder.text.isLongClickable = false
        holder.text.setOnLongClickListener(null)
        holder.text.movementMethod = null
        holder.text.maxWidth =
            (ctx.resources.displayMetrics.widthPixels * TOOL_MAX_WIDTH).toInt()

        val textLp = holder.text.layoutParams as android.widget.LinearLayout.LayoutParams
        textLp.width = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        textLp.weight = 0f
        holder.text.layoutParams = textLp

        val row = holder.row as android.widget.LinearLayout
        val rowLp = holder.row.layoutParams as RecyclerView.LayoutParams
        row.gravity = android.view.Gravity.START
        val edgeMargin = dp(ctx, 20f).toInt()
        rowLp.marginStart = edgeMargin
        rowLp.marginEnd = edgeMargin
        // 与 bindMessage 同口径：上一行同角色 6dp、不同角色 16dp。
        val prevRole = (rows.lastOrNull() as? Row.Msg)?.m?.role
        rowLp.topMargin =
            if (prevRole == null || prevRole == ROLE_ASSISTANT) {
                dp(ctx, 6f).toInt()
            } else {
                dp(ctx, 16f).toInt()
            }
        holder.row.layoutParams = rowLp
    }

    private fun bindMessage(holder: VH, position: Int, m: ChatMessageEntity) {
        val ctx = holder.text.context
        val isUser = m.role == ROLE_USER

        // ── 来源 / 工具角标标注（F12 10.4 ① + §4.5）────────────────
        // 消息体用「末行承载机器可读元数据」的既有约定：`来源：标题 · 第 N 页`
        // （可点跳知识库页）恒为最后一行，`查阅：本次问了 N 项数据` 紧随其前。
        // 渲染时从末尾最多剥两行做 span，正文不含这些元数据行（content 仍是纯文本）。
        val lines = m.content.split("\n")
        var sourceLine: String? = null
        var badgeLine: String? = null
        var bodyEnd = lines.size
        if (!isUser && lines.size >= 2) {
            if (lines[lines.size - 1].startsWith("来源：")) {
                sourceLine = lines[lines.size - 1]
                bodyEnd--
            }
            if (bodyEnd - 1 >= 1 && lines[bodyEnd - 1].startsWith("查阅：")) {
                badgeLine = lines[bodyEnd - 1]
                bodyEnd--
            }
        }
        val hasMeta = sourceLine != null || badgeLine != null
        val body = if (hasMeta) lines.subList(0, bodyEnd).joinToString("\n") else m.content
        holder.text.text = if (hasMeta) {
            metaSpannable(ctx, holder.text, body, badgeLine, sourceLine)
        } else if (isUser) {
            body
        } else {
            // 2026-10-09：助理正文含 `**粗体**` 时转 StyleSpan（模型普遍输出 Markdown
            // 粗体；此前星号原样显示，`**700 / 2500 kcal**` 一类的原文直接糊在气泡里）。
            // 只处理成对 `**`，不成对的保持原样；用户气泡不转（用户可能就在打星号）。
            markdownBoldSpan(body)
        }

        // ── 气泡外观（v6）────────────────────────────────────────────
        // 背景件由 F 层绘制：用户 = primary_container 衬底（圆角 16/4/16/16），
        // 助理 = surface 实底 + card_elev_1（圆角 4/16/16/16）。
        // 用户气泡文字改用 on_primary_container（浅/深两侧均为与衬底成对的可读色）。
        if (isUser) {
            holder.text.setBackgroundResource(R.drawable.bg_bubble_user)
            holder.text.setTextColor(
                androidx.core.content.ContextCompat.getColor(ctx, R.color.on_primary_container)
            )
            holder.text.elevation = 0f
        } else {
            holder.text.setBackgroundResource(R.drawable.bg_bubble_assistant)
            holder.text.setTextColor(
                androidx.core.content.ContextCompat.getColor(ctx, R.color.text_1)
            )
            holder.text.elevation = ctx.resources.getDimension(R.dimen.card_elev_1)
        }

        // ── 长按复制（微扩展 A）：复制正文（不含来源行）───────────────
        holder.text.isLongClickable = true
        holder.text.setOnLongClickListener { v ->
            val cm = v.context.getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("healix_chat", body))
            android.widget.Toast.makeText(
                v.context, R.string.chat_copied, android.widget.Toast.LENGTH_SHORT,
            ).show()
            true
        }

        // ── 外层对齐 + 最大宽度 82%（v6，原型 .msg max-width:82%）────────
        val screenW = ctx.resources.displayMetrics.widthPixels
        val maxBubble = (screenW * 0.82f).toInt()

        val textLp = holder.text.layoutParams as android.widget.LinearLayout.LayoutParams
        textLp.width = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        textLp.weight = 0f
        holder.text.maxWidth = maxBubble
        holder.text.layoutParams = textLp

        val row = holder.row as android.widget.LinearLayout
        val rowLp = holder.row.layoutParams as RecyclerView.LayoutParams
        row.gravity = if (isUser) android.view.Gravity.END else android.view.Gravity.START
        val edgeMargin = dp(ctx, 20f).toInt()
        rowLp.marginStart = edgeMargin
        rowLp.marginEnd = edgeMargin

        // 同角色 6dp（气泡本就分块，间距小些更连贯），不同角色 16dp；
        // 时间头后的首条（前一行是 Time → prevRole=null）也用 6dp —— 头部已自带 10dp 间距
        val prevRole = (rows.getOrNull(position - 1) as? Row.Msg)?.m?.role
        rowLp.topMargin =
            if (prevRole == null || prevRole == m.role) dp(ctx, 6f).toInt() else dp(ctx, 16f).toInt()
        holder.row.layoutParams = rowLp

        // 无障碍：助理消息出现时朗读（保持原有行为；展示列表末行恒为消息）
        if (!isUser && position == rows.size - 1) {
            holder.text.announceForAccessibility(m.content)
        }
    }

    /**
     * 工具反馈条（v6，原型 .toolline）：`role=tool` 的消息渲染为 `surface_variant`
     * 底 + 8dp 圆角 + 左侧 2dp `primary` 竖线的中性反馈条，正文 13sp `text_2`，
     * 状态后缀「完成 / 待确认」用 `text_3` 弱化。
     *
     * 「待确认」= 该工具属于拟稿确认路径（[TOOL_PROPOSE]），与首页直写路径分离；
     * 其余工具一律「完成」。
     */
    private fun bindToolLine(holder: VH, position: Int, m: ChatMessageEntity) {
        val ctx = holder.text.context
        val status = ctx.getString(
            if (m.toolName == TOOL_PROPOSE) R.string.chatnav_tool_pending
            else R.string.chatnav_tool_done,
        )
        val sb = android.text.SpannableString("${m.content}  $status")
        sb.setSpan(
            android.text.style.ForegroundColorSpan(
                androidx.core.content.ContextCompat.getColor(ctx, R.color.text_3),
            ),
            m.content.length + 2, sb.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        holder.text.text = sb
        holder.text.maxWidth =
            (ctx.resources.displayMetrics.widthPixels * TOOL_MAX_WIDTH).toInt()

        val rowLp = holder.row.layoutParams as RecyclerView.LayoutParams
        val edgeMargin = dp(ctx, 20f).toInt()
        rowLp.marginStart = edgeMargin
        rowLp.marginEnd = edgeMargin
        val prevRole = (rows.getOrNull(position - 1) as? Row.Msg)?.m?.role
        rowLp.topMargin =
            if (prevRole == null || prevRole == m.role) dp(ctx, 6f).toInt() else dp(ctx, 16f).toInt()
        holder.row.layoutParams = rowLp
    }

    class VH(
        val row: View,
        val text: android.widget.TextView,
    ) : RecyclerView.ViewHolder(row)

    /**
     * 轻量 Markdown 粗体（2026-10-09）：成对 `**…**` → 去星号 + StyleSpan(BOLD)。
     *
     * 为什么只做粗体不做别的：实测模型输出里 99% 的标记就是 `**强调**`（数字 / 结论），
     * 标题 / 列表 / 代码块极少且气泡排版对它们无增益 —— 一个正则就够，不引第三方库。
     * 不成对（奇数个 `**`）整体保持原文：宁可显示星号也不吃掉用户的半个标记。
     */
    private fun markdownBoldSpan(body: String): CharSequence {
        if (!body.contains("**")) return body
        val sb = StringBuilder(body.length)
        val spans = mutableListOf<Pair<IntRange, android.text.style.StyleSpan>>()
        val regex = Regex("\\*\\*(.+?)\\*\\*", RegexOption.DOT_MATCHES_ALL)
        var last = 0
        for (m in regex.findAll(body)) {
            sb.append(body, last, m.range.first)
            val start = sb.length
            sb.append(m.groupValues[1])
            spans += start until sb.length to android.text.style.StyleSpan(android.graphics.Typeface.BOLD)
            last = m.range.last + 1
        }
        if (spans.isEmpty()) return body
        sb.append(body, last, body.length)
        val out = android.text.SpannableString(sb.toString())
        for ((range, span) in spans) {
            out.setSpan(span, range.first, range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    /**
     * 正文末行元数据 span（从末尾最多两行）：
     * - `查阅：本次问了 N 项数据`（§4.5 工具使用可见性）：12sp、`text_3`，无点击；
     * - `来源：标题 · 第 N 页`（F12 10.4 ①）：13sp、`text_2`，可点跳知识库页。
     * `来源：` 恒在 `查阅：` 之后（末行）。与正文隔一个换行（气泡内无法对 span
     * 加 4dp 间距，取 10.4 ① 的近似实现）。
     */
    private fun metaSpannable(
        ctx: android.content.Context,
        text: android.widget.TextView,
        body: String,
        badgeLine: String?,
        sourceLine: String?,
    ): CharSequence {
        // 2026-10-09 补丁：body 先过 markdownBoldSpan —— 修 v0.2.1 验证发现的漏网：
        // 带 `查阅：`/`来源：` 元数据的助理消息走本函数，之前 body 直接拼接，
        // `**粗体**` 星号照样裸显（无元数据路径才有 markdown 处理，两路不一致）。
        // 返回值可能是 SpannableString（含粗体 span），下游按 CharSequence 追加元数据行。
        val rendered = markdownBoldSpan(body)
        val sb = StringBuilder(rendered.toString())
        if (badgeLine != null) sb.append("\n").append(badgeLine)
        var sourceStart = -1
        if (sourceLine != null) {
            sourceStart = sb.length + 1
            sb.append("\n").append(sourceLine)
        }
        val sp = android.text.SpannableString(sb.toString())

        // 2026-10-09：若 markdownBoldSpan 已产生 span（返回类型为 Spanned），
        // 把正文区间内的粗体 span 原样搬到合并后的 SpannableString 上。
        if (rendered is android.text.Spanned) {
            val spans = rendered.getSpans(
                0, rendered.length, android.text.style.StyleSpan::class.java,
            )
            for (s in spans) {
                sp.setSpan(
                    s, rendered.getSpanStart(s), rendered.getSpanEnd(s),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        // 查阅角标：12sp（相对正文 15sp 缩放）、text_3
        // ⚠️ 起点必须用 rendered 的长度（去 `**` 后比原 body 短）：
        //    v0.2.1 真机崩溃（IndexOutOfBoundsException setSpan 460..473 beyond 457）
        //    就是这里用了 body.length —— 星号剥掉后 sb 比旧口径短，
        //    badge/source 的 start 落到串外。collapse 前先记录 bodyLen。
        val bodyLen = rendered.length
        if (badgeLine != null) {
            val start = bodyLen + 1
            val end = start + badgeLine.length
            sp.setSpan(
                android.text.style.RelativeSizeSpan(12f / 15f), start, end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sp.setSpan(
                android.text.style.ForegroundColorSpan(
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.text_3),
                ),
                start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        // 来源行：13sp、text_2、可点跳知识库页
        if (sourceLine != null) {
            sp.setSpan(
                android.text.style.RelativeSizeSpan(13f / 15f), sourceStart, sp.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sp.setSpan(
                android.text.style.ForegroundColorSpan(
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.text_2),
                ),
                sourceStart, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sp.setSpan(
                object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        // 知识库也是宿主内的二级页（v8 T03）—— 走同一个容器与转场，
                        // 不再 startActivity（那会另起窗口，正是"切页卡顿"的来源之一）
                        NavHost.open(widget.context, KnowledgeBaseFragment(), NavHost.PAGE_KNOWLEDGE)
                    }

                    override fun updateDrawState(ds: android.text.TextPaint) {
                        // 保持 text_2 色与常规字重，不加下划线（可点但不花哨）
                        ds.isUnderlineText = false
                    }
                },
                sourceStart, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        // 仅在存在可点来源行时启用 MovementMethod（避免影响普通气泡的手势）。
        // 复用时 item 可能没有来源行，所以每次绑定都按当前状态重设。
        text.movementMethod = if (sourceLine != null) {
            android.text.method.LinkMovementMethod.getInstance()
        } else {
            null
        }
        return sp
    }

    private companion object {
        const val TYPE_USER = 0
        const val TYPE_ASSISTANT = 1

        /** 时间分隔头 viewType（W1）。 */
        const val TYPE_TIME = 2

        /** 工具反馈条 viewType（v6，`role=tool`）。 */
        const val TYPE_TOOL = 3

        /** 消息角色（`ChatMessageEntity.role`）。 */
        const val ROLE_USER = "user"
        const val ROLE_TOOL = "tool"

        /** 助理消息角色（流式临时气泡的间距口径要与它对齐）。 */
        const val ROLE_ASSISTANT = "assistant"

        /** 拟稿确认工具名 —— 工具反馈条据此显示「待确认」后缀。 */
        const val TOOL_PROPOSE = "propose_log"

        /** 气泡 / 工具条最大宽度占比（v6，原型 .msg max-width:82%）。 */
        const val TOOL_MAX_WIDTH = 0.82f

        /** 时间头插入阈值：相邻两条消息相隔 > 30 分钟。 */
        const val TIME_GAP_MS = 30L * 60 * 1000

        fun dp(context: android.content.Context, value: Float): Float =
            value * context.resources.displayMetrics.density
    }
}
