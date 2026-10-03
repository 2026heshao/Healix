package com.healix.app.ui

import android.content.Context
import com.healix.app.R
import com.healix.app.db.ChatMessageEntity
import com.healix.app.net.ChatMessage
import com.healix.app.net.ChatRequest
import com.healix.app.net.ChatResult
import com.healix.app.net.OpenAiCompatProvider
import com.healix.app.net.ProviderConfig

/**
 * 对话引擎（单轮，无工具）。
 *
 * ⚠️ 范围边界（务必读）：
 * 本类**只做单轮对话** —— 组装系统提示 + 今日摘要 + 历史窗口，调一次模型，返回文本。
 * 它**不是** Agent：没有自主循环、没有工具调用、不满足 C5 有界自主的任何要素。
 *
 * 受限自主循环（max_steps=4 / 墙钟 30s / 连续同参调用即停 / propose_log 走人工确认）
 * 属于执行计划 S3–S4，将由独立的 `agent/HealthAgent.kt` 实现。
 * 现在把它并进来会引入"工具集未就位却先有循环"的不确定行为，违背"先跑通竖切片"。
 *
 * 免责：一个免费档模型在限流下会频繁失败，因此降级路径是**必备**而非可选。
 */
internal object ChatEngine {

    enum class State { Ok, Queued, Degraded }

    data class Reply(val text: String, val state: State)

    /**
     * 单轮回复。**不抛异常** —— 失败时返回本地模板回答并标记 Degraded。
     *
     * `suspend`：内部要调 `provider.chat()`（挂起函数，网络 IO）。
     * 调用方（ChatViewModel）本身已在协程里，直接调用即可。
     */
    suspend fun reply(
        context: Context,
        config: ProviderConfig,
        sessionDate: String,
        userText: String,
        history: List<ChatMessageEntity>,
    ): Reply {
        val provider = OpenAiCompatProvider(config)

        val messages = buildList {
            add(ChatMessage(role = "system", content = systemPrompt(context, sessionDate)))
            // 历史窗口：最近 16 条（9.2 上下文策略）
            history.takeLast(HISTORY_WINDOW).forEach { m ->
                if (m.role == "user" || m.role == "assistant") {
                    add(ChatMessage(role = m.role, content = m.content))
                }
            }
            add(ChatMessage(role = "user", content = userText))
        }

        val result = provider.chat(
            ChatRequest(
                messages = messages,
                timeoutMs = TIMEOUT_MS,
                maxRetries = MAX_RETRIES,
            ),
        )

        return when (result) {
            is ChatResult.Ok -> {
                val text = result.content.trim()
                if (text.isEmpty()) {
                    Reply(localFallback(context), State.Degraded)
                } else {
                    Reply(text, State.Ok)
                }
            }

            is ChatResult.Err -> when (result.kind) {
                com.healix.app.net.ErrKind.RATE_LIMIT ->
                    // 限流是"排队中"而不是失败 —— 交给 UI 显示预估秒数
                    Reply(result.message, State.Queued)

                else -> Reply(localFallback(context), State.Degraded)
            }
        }
    }

    /**
     * 系统提示（功能补充 9.2）。
     *
     * 关键点全在这里：先查后答、语气直接、不写免责套话、全程中文。
     * ⚠️ 当前版本尚未接入工具，因此第 1 条改写为"没有数据时明说没有"，
     * 避免提示模型去查不存在的工具而编造数字。S3 接入 query_* 后改回原表述。
     */
    private fun systemPrompt(context: Context, sessionDate: String): String {
        val summary = TodaySummary.build(context).lines.joinToString("\n")
        return """
你是一个想增重的用户的健康助理。今天是 $sessionDate。

今天的已知数字（本地记录，可信）：
$summary

规则：
1. 涉及数字（摄入、体重、运动量）只能引用上面给出的数字。上面的数字里没有的，直接说"今天还没记录这项"，禁止凭空报数或估算当日总量。
2. 语气直接、不客套、不写"建议咨询医生"这类套话。
3. 一次只追问一次，用户没答就按默认假设继续。
4. 用户说"记一下…"时，告诉他自己在速记框或通知栏记一笔，不要声称你已经记录了。
5. 给饮食建议时要给具体食物 + 分量，并标注预计热量。
6. 热量数字都是估算值。
7. 全程中文。回答控制在 4 句以内，不要分点罗列。
""".trim()
    }

    /** 降级：本地模板回答（功能补充 1.7，AI 不能是唯一热源）。 */
    private fun localFallback(context: Context): String {
        val s = TodaySummary.build(context)
        return if (s.gap > 0) {
            context.getString(
                R.string.fallback_gap,
                s.kcalIn, s.target, s.gap,
            )
        } else {
            context.getString(R.string.fallback_reached, s.kcalIn, s.target)
        }
    }

    private const val HISTORY_WINDOW = 16
    private const val TIMEOUT_MS = 15_000L
    private const val MAX_RETRIES = 5
}
