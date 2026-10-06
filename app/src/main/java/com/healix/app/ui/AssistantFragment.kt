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

    /** 拟稿确认在 Tab 隐藏期间到达时先暂存，切回可见再弹（否则会盖在别的 Tab 上）。 */
    private var pendingProposal: com.healix.app.agent.LogProposal? = null

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
     */
    private fun showSessionMenu(anchor: View) {
        val dates = vm.sessionDates.value
        if (dates.isEmpty()) return
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        dates.forEach { date ->
            popup.menu.add(HealixDate.sessionLabel(java.time.LocalDate.parse(date))).setOnMenuItemClickListener {
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
                        adapter.submit(list)
                        if (list.isNotEmpty()) {
                            binding.emptyHint.visibility = View.GONE
                            binding.messageList.scrollToPosition(list.size - 1)
                        } else {
                            binding.emptyHint.visibility = View.VISIBLE
                        }
                        // 快捷问答三行：只在"今天的空会话"出现；历史会话一律不出现
                        updateQuickGroup(list.isNotEmpty())
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

    /** S3–S4：弹拟稿确认（底色容器 ActionConfirmSheet，挂子FragmentManager）。 */
    private fun showProposalSheet(proposal: com.healix.app.agent.LogProposal) {
        val sheet = ActionConfirmSheet.newInstance(
            getString(R.string.proposal_sheet_title),
            getString(R.string.proposal_confirm, proposal.rawText),
        )
        sheet.onConfirm = { vm.confirmProposal(proposal) }
        sheet.show(childFragmentManager, ActionConfirmSheet.TAG)
    }

    /** 快捷问答三行：仅查看今天且当前会话为空时出现。 */
    private fun updateQuickGroup(hasMessages: Boolean) {
        val show = !hasMessages && vm.isToday.value
        binding.quickGroup.visibility = if (show) View.VISIBLE else View.GONE
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
 * 结构：外层 LinearLayout 负责"靠哪边 + 最大宽度"，内层 TextView 带气泡背景。
 * 之所以套一层而不是直接给 TextView 设背景：
 *   TextView 用 wrap_content + background 时，padding 会被算进宽度，
 *   导致同一条消息在"短文本"和"长文本"下视觉边距不一致。
 *   外层控制对齐、内层控制留白，职责分开更稳。
 */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    private var items: List<ChatMessageEntity> = emptyList()

    @Suppress("NotifyDataSetChanged")
    fun submit(list: List<ChatMessageEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position].role == "user") TYPE_USER else TYPE_ASSISTANT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context

        val text = android.widget.TextView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(dp(ctx, 5f), 1f)
            setSingleLine(false)
            // 气泡内的留白：水平 14dp、垂直 10dp
            setPadding(dp(ctx, 14f).toInt(), dp(ctx, 10f).toInt(), dp(ctx, 14f).toInt(), dp(ctx, 10f).toInt())
        }

        // 外层：限制最大宽度 78%，靠 gravity 决定左右
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

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val ctx = holder.text.context
        val isUser = m.role == "user"

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
        } else {
            body
        }

        // ── 气泡外观 ────────────────────────────────────────────────
        if (isUser) {
            holder.text.setBackgroundResource(R.drawable.bg_bubble_user)
            // 气泡底色 = text_1（深色模式下是浅底），文字必须与底反相：
            // bubble_user_text 浅色=白 / 深色=深，不能用恒为白的 btn_primary_text
            holder.text.setTextColor(
                androidx.core.content.ContextCompat.getColor(ctx, R.color.bubble_user_text)
            )
        } else {
            holder.text.setBackgroundResource(R.drawable.bg_bubble_assistant)
            holder.text.setTextColor(
                androidx.core.content.ContextCompat.getColor(ctx, R.color.text_1)
            )
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

        // ── 外层对齐 + 最大宽度 78% ──────────────────────────────────
        val screenW = ctx.resources.displayMetrics.widthPixels
        val maxBubble = (screenW * 0.78f).toInt()

        val textLp = holder.text.layoutParams as android.widget.LinearLayout.LayoutParams
        textLp.width = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        textLp.weight = 0f
        holder.text.maxWidth = maxBubble
        holder.text.layoutParams = textLp

        val rowLp = holder.row.layoutParams as RecyclerView.LayoutParams
        holder.row.gravity = if (isUser) android.view.Gravity.END else android.view.Gravity.START
        val edgeMargin = dp(ctx, 20f).toInt()
        rowLp.marginStart = edgeMargin
        rowLp.marginEnd = edgeMargin

        // 同角色 6dp（气泡本就分块，间距小些更连贯），不同角色 16dp
        val prevRole = if (position > 0) items[position - 1].role else null
        rowLp.topMargin =
            if (prevRole == null || prevRole == m.role) dp(ctx, 6f).toInt() else dp(ctx, 16f).toInt()
        holder.row.layoutParams = rowLp

        // 无障碍：助理消息出现时朗读（保持原有行为）
        if (!isUser && position == items.size - 1) {
            holder.text.announceForAccessibility(m.content)
        }
    }

    class VH(
        val row: android.widget.LinearLayout,
        val text: android.widget.TextView,
    ) : RecyclerView.ViewHolder(row)

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
        val sb = StringBuilder(body)
        if (badgeLine != null) sb.append("\n").append(badgeLine)
        var sourceStart = -1
        if (sourceLine != null) {
            sourceStart = sb.length + 1
            sb.append("\n").append(sourceLine)
        }
        val sp = android.text.SpannableString(sb.toString())

        // 查阅角标：12sp（相对正文 15sp 缩放）、text_3
        if (badgeLine != null) {
            val start = body.length + 1
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

        fun dp(context: android.content.Context, value: Float): Float =
            value * context.resources.displayMetrics.density
    }
}
