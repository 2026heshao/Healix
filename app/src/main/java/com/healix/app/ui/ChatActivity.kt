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
 * **核心决策：不用彩色气泡。** 靠对齐方向 + 灰度区分身份，这是全 App 唯一允许
 * "两侧布局"的界面。工具调用状态用自然语言，禁止暴露工具名 / 技术名词。
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

/** 对话消息适配器。无头像、无气泡、无背景，只有对齐方向不同。 */
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
        val tv = android.widget.TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // 15sp body，行高由 lineSpacingExtra 提供
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_1))
            setLineSpacing(dp(context, 6f), 1f)
            // 保留换行
            setSingleLine(false)
        }
        return VH(tv)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val ctx = holder.text.context
        val margin = dp(ctx, 20f).toInt()
        val lp = holder.text.layoutParams as RecyclerView.LayoutParams

        holder.text.text = m.content

        if (m.role == "user") {
            // 用户消息：右对齐，最宽 80%，右侧距边 20dp
            holder.text.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_END)
            holder.text.gravity = android.view.Gravity.END
            lp.marginStart = dp(ctx, 64f).toInt()
            lp.marginEnd = margin
        } else {
            // 助理消息：左对齐，左右边距 20dp
            holder.text.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START)
            holder.text.gravity = android.view.Gravity.START
            lp.marginStart = margin
            lp.marginEnd = dp(ctx, 64f).toInt()
        }

        // 同角色 8dp，不同角色 20dp
        val prevRole = if (position > 0) items[position - 1].role else null
        lp.topMargin = if (prevRole == null || prevRole == m.role) dp(ctx, 8f).toInt() else dp(ctx, 20f).toInt()
        holder.text.layoutParams = lp

        // 动态内容：助理消息出现时朗读（无障碍规范）
        if (m.role != "user" && position == items.size - 1) {
            holder.text.announceForAccessibility(m.content)
        }
    }

    class VH(val text: android.widget.TextView) : RecyclerView.ViewHolder(text)

    private companion object {
        const val TYPE_USER = 0
        const val TYPE_ASSISTANT = 1

        fun dp(context: android.content.Context, value: Float): Float =
            value * context.resources.displayMetrics.density
    }
}
