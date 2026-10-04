package com.healix.app.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.ActivityChatBinding
import com.healix.app.db.ChatMessageEntity
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 对话页（设计规范系统 4.2）。
 *
 * **交互决策：左右气泡。** 用户消息靠右（text_1 实底 + 白字），
 * 助理消息靠左（surface 底 + 1dp 描边 + text_1 字）。
 * 气泡只使用既有中性色，未引入新颜色 / 阴影 —— 详见 [ChatAdapter] 的变更记录。
 *
 * 工具调用状态用自然语言，禁止暴露工具名 / 技术名词。
 *
 * ⚠️ 本页当前实现的是 **对话 UI 骨架 + 消息持久化**（对应执行计划 S2）：
 * 能聊天、能出计划、消息持久化、杀进程重进历史还在。
 * 工具调用（query_events / query_stats / propose_log）属于 S3–S4，
 * 由 HealthAgent + ToolRegistry 实现后接入 —— 见 readme 的 S3/S4 条目。
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: ChatAdapter
    private lateinit var vm: ChatViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = ChatViewModel(HealixApp.from(this))
        adapter = ChatAdapter()

        val lm = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messageList.layoutManager = lm
        binding.messageList.adapter = adapter

        binding.sessionLabel.text = HealixDate.sessionLabel(LocalDate.now())
        binding.btnSend.setOnClickListener { send() }

        // 微扩展 B：会话日期切换 —— 点顶部日期弹出会话菜单（今天/昨天/更早）
        binding.sessionLabel.setOnClickListener { anchor -> showSessionMenu(anchor) }

        // v6（11.1）：助理是全局 3 Tab 的第二页 —— tabbar 高亮第二段，
        // 「记录 / 我的」点击由 TabBar 处理（in_tab 转场返回主界面）。
        TabBar.bind(this, TabBar.TAB_ASSISTANT)

        // v6（11.4）：快捷入口按压缩放反馈
        binding.quickToday.bindPressScale()
        binding.quickDinner.bindPressScale()
        binding.quickWeek.bindPressScale()

        // 快捷入口三行：仅在当日会话为空时出现，一旦有消息即隐藏，不再恢复。
        // 第 2 行按时段动态（微扩展）：早[5,11) 早餐 / 午[11,14) 午餐 /
        // 下午[14,18) 加餐 / 晚[18,23) 晚餐 / 深夜[23,5) 夜间饮食。
        // 纯本地换文案，不调 AI、不占配额。
        val quickMeal = getString(timeBucketMealRes())
        binding.quickDinner.text = quickMeal
        binding.quickToday.setOnClickListener { send(getString(R.string.quick_today_ok)) }
        binding.quickDinner.setOnClickListener { send(quickMeal) }
        binding.quickWeek.setOnClickListener { send(getString(R.string.quick_week)) }

        // 微扩展 D：模型调用失败时，提示条变成重试入口（文案见 observe() 的 retryAvailable 分支）
        binding.simplifiedBar.setOnClickListener { vm.retryLast() }

        observe()
    }

    /**
     * 微扩展 B：会话日期菜单。列出所有有消息的日期（倒序），点选切换只读查看。
     * 历史会话禁用输入与发送（发送永远写今天的会话，避免"在昨天的页面发出
     * 一条出现在今天"的错位）。
     */
    private fun showSessionMenu(anchor: View) {
        val dates = vm.sessionDates.value
        if (dates.isEmpty()) return
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
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
        if (content.isEmpty()) return
        binding.input.setText("")
        binding.quickGroup.visibility = View.GONE
        vm.send(content)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

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

                // 微扩展 B：查看历史会话 = 只读。输入条/发送/预设条全部禁用，
                // 顶部日期标签换成完整提示（点击菜单仍可切回今天）。
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
                        android.widget.Toast.makeText(this@ChatActivity, text,
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }

                // S3–S4：propose_log 拟稿确认 —— 确认后走完整抽取链（与「记一笔」同管道）
                launch {
                    vm.proposal.collect { proposal ->
                        androidx.appcompat.app.AlertDialog.Builder(this@ChatActivity)
                            .setMessage(getString(R.string.proposal_confirm, proposal.rawText))
                            .setPositiveButton(R.string.confirm) { _: android.content.DialogInterface, _: Int ->
                                vm.confirmProposal(proposal)
                            }
                            .setNegativeButton(
                                R.string.cancel,
                                null as android.content.DialogInterface.OnClickListener?,
                            )
                            .show()
                    }
                }

                // S3–S4：拟稿落库结果反馈
                launch {
                    vm.proposalToast.collect { text ->
                        android.widget.Toast.makeText(this@ChatActivity, text,
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
                                binding.stateLabel.text = getString(R.string.state_thinking)
                            }
                            is ChatUiState.Queued -> {
                                binding.spinner.visibility = View.VISIBLE
                                binding.stateLabel.visibility = View.VISIBLE
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
 * 之所以套一层而不是直接给 TextView 设 background：
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

        // ── 来源标注（F12，10.4 ①）────────────────────────────────
        // 来源信息由消息体约定分隔符承载：content 末行 `来源：标题 · 第 N 页`。
        // 渲染时识别：拆出末行做 13sp text_2 的可点 span（→ 知识库页），
        // 未命中不出现；正文不含来源行。content 本身仍是纯文本。
        val lines = m.content.split("\n")
        val sourceLine = if (!isUser && lines.size >= 2) {
            lines.last().takeIf { it.startsWith("来源：") }
        } else {
            null
        }
        val body = if (sourceLine != null) lines.dropLast(1).joinToString("\n") else m.content
        holder.text.text = if (sourceLine != null) {
            sourceSpannable(ctx, holder.text, body, sourceLine)
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
     * 来源行 span：13sp（相对正文 15sp 缩放）、text_2 色、可点跳知识库页。
     * 与正文隔一个换行（气泡内无法对 span 加 4dp 间距，取 10.4 ① 的近似实现）。
     */
    private fun sourceSpannable(
        ctx: android.content.Context,
        text: android.widget.TextView,
        body: String,
        sourceLine: String,
    ): CharSequence {
        val sp = android.text.SpannableString("$body\n$sourceLine")
        val start = body.length + 1
        sp.setSpan(
            android.text.style.RelativeSizeSpan(13f / 15f), start, sp.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sp.setSpan(
            android.text.style.ForegroundColorSpan(
                androidx.core.content.ContextCompat.getColor(ctx, R.color.text_2),
            ),
            start, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sp.setSpan(
            object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) {
                    ctx.startActivity(android.content.Intent(ctx, KnowledgeBaseActivity::class.java))
                }

                override fun updateDrawState(ds: android.text.TextPaint) {
                    // 保持 text_2 色与常规字重，不加下划线（可点但不花哨）
                    ds.isUnderlineText = false
                }
            },
            start, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
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
