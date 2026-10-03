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
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSend.setOnClickListener { send() }

        // 快捷入口三行：仅在当日会话为空时出现，一旦有消息即隐藏，不再恢复
        binding.quickToday.setOnClickListener { send("今天达标了吗") }
        binding.quickDinner.setOnClickListener { send("推荐晚餐") }
        binding.quickWeek.setOnClickListener { send("本周复盘") }

        observe()
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
                            binding.quickGroup.visibility = View.GONE
                            binding.emptyHint.visibility = View.GONE
                            binding.messageList.scrollToPosition(list.size - 1)
                        } else {
                            binding.quickGroup.visibility = View.VISIBLE
                            binding.emptyHint.visibility = View.VISIBLE
                        }
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
            }
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

        holder.text.text = m.content

        // ── 气泡外观 ────────────────────────────────────────────────
        if (isUser) {
            holder.text.setBackgroundResource(R.drawable.bg_bubble_user)
            holder.text.setTextColor(
                androidx.core.content.ContextCompat.getColor(ctx, R.color.btn_primary_text)
            )
        } else {
            holder.text.setBackgroundResource(R.drawable.bg_bubble_assistant)
            holder.text.setTextColor(
                androidx.core.content.ContextCompat.getColor(ctx, R.color.text_1)
            )
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

    private companion object {
        const val TYPE_USER = 0
        const val TYPE_ASSISTANT = 1

        fun dp(context: android.content.Context, value: Float): Float =
            value * context.resources.displayMetrics.density
    }
}
