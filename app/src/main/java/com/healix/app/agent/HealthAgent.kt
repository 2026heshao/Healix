package com.healix.app.agent

import android.content.Context
import com.healix.app.db.ChatMessageEntity
import com.healix.app.net.ChatMessage
import com.healix.app.net.ChatRequest
import com.healix.app.net.ChatResult
import com.healix.app.net.ErrKind
import com.healix.app.net.OpenAiCompatProvider
import com.healix.app.net.ProviderConfig
import com.healix.app.net.ToolCall
import com.healix.app.net.ToolDef
import com.healix.app.net.ToolFunctionDef
import com.healix.app.repo.EventRepository
import com.healix.app.ui.ChatEngine
import com.healix.app.ui.TodaySummary
import org.json.JSONObject

/**
 * propose_log 拟好的记录草稿。
 *
 * ⚠️ 这**不是**已入库的事件 —— 只是一段待确认的原文。用户点确认后由
 * ChatViewModel 调 [EventRepository.submit] 走完整抽取链（pending → done），
 * 与「记一笔」共用同一条数据管道；点取消则什么都不发生。
 * Agent 自身**无权直接写入** events 表。
 *
 * public（非 internal）：ChatViewModel 的公开流 [ChatViewModel.proposal]
 * 要暴露它 —— Kotlin 禁止 public 成员暴露 internal 类型。
 */
data class LogProposal(val rawText: String)

/** Agent 循环的结果（调用方 ChatViewModel 按分支落库 / 回退）。 */
internal sealed interface AgentOutcome {

    /** 模型给出最终文本回答（未调工具，或工具答完后收尾）。 */
    data class Done(val text: String) : AgentOutcome

    /** propose_log 已拟稿，等用户确认。text 是随附说明（落库为 assistant 消息）。 */
    data class ProposalPending(val text: String, val proposal: LogProposal) : AgentOutcome

    /** 明确限流：UI 显示"排队中"，不进降级链（等待本身是预期行为）。 */
    data class RateLimited(val message: String) : AgentOutcome

    /** 其它失败 → 调用方回退单轮 ChatEngine（降级链第二级）。 */
    data object Failed : AgentOutcome
}

/**
 * 工具注册表（S3）：三个只读/拟稿工具，全部针对本地 Room。
 *
 * 设计取舍：
 * - **只读为主**：query_events / query_stats 只查不写；唯一的"写"入口
 *   propose_log 也只产草稿（见 [LogProposal]）—— 有界自主的边界在这里划死。
 * - 工具结果一律转成**中文紧凑文本**回给模型：模型读文本比读 JSON 省 token，
 *   且「来源标注」规则天然成立（数字来自工具返回）。
 * - 参数解析失败 / 未知工具名**不抛异常**，返回一句错误说明 —— 模型能看到
 *   错误就有机会自纠，循环也不至于断。
 */
internal object ToolRegistry {

    const val NAME_QUERY_EVENTS = "query_events"
    const val NAME_QUERY_STATS = "query_stats"
    const val NAME_PROPOSE_LOG = "propose_log"

    /** 交给模型的工具 schema（OpenAI function calling 格式）。 */
    val defs: List<ToolDef> = listOf(
        ToolDef(
            function = ToolFunctionDef(
                name = NAME_QUERY_EVENTS,
                description = "查询某日期区间内的健康记录（饮食/运动/睡眠/体重/身体/生病），" +
                    "返回逐条原文与热量。回答“我某天吃了什么/练了什么”必须用它查证，禁止凭对话记忆编造。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "day_from" to mapOf(
                            "type" to "string",
                            "description" to "开始日期，yyyy-MM-dd",
                        ),
                        "day_to" to mapOf(
                            "type" to "string",
                            "description" to "结束日期，yyyy-MM-dd",
                        ),
                        "type" to mapOf(
                            "type" to "string",
                            "description" to "可选，按类型过滤：meal/exercise/sleep/body/illness/other",
                        ),
                    ),
                    "required" to listOf("day_from", "day_to"),
                ),
            ),
        ),
        ToolDef(
            function = ToolFunctionDef(
                name = NAME_QUERY_STATS,
                description = "重新读取今日健康摘要（摄入/消耗/运动次数/睡眠/体重等" +
                    "本地统计数字）。记录刚发生变化、需要最新数字时用。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any?>(),
                ),
            ),
        ),
        ToolDef(
            function = ToolFunctionDef(
                name = NAME_PROPOSE_LOG,
                description = "拟一条记录草稿。用户让你记东西时调用（raw_text 用用户的原话），" +
                    "草稿要经用户确认后才会写入，你无权直接写入记录。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "raw_text" to mapOf(
                            "type" to "string",
                            "description" to "要记录的内容，用用户原话",
                        ),
                    ),
                    "required" to listOf("raw_text"),
                ),
            ),
        ),
    )

    /**
     * 执行一次工具调用。
     *
     * @return 第一元素 = 回给模型的结果文本；第二元素非空 = propose_log 拟稿
     * （调用方应终止循环转人工确认）。
     */
    suspend fun execute(context: Context, call: ToolCall): Pair<String, LogProposal?> {
        val args: JSONObject = try {
            JSONObject(call.function.arguments.ifBlank { "{}" })
        } catch (_: Exception) {
            return "工具参数不是合法 JSON，请检查后重试。" to null
        }
        return when (call.function.name) {
            NAME_QUERY_EVENTS -> queryEvents(context, args) to null
            NAME_QUERY_STATS -> queryStats(context) to null
            NAME_PROPOSE_LOG -> propose(args)
            else -> "未知工具：${call.function.name}" to null
        }
    }

    private suspend fun queryEvents(context: Context, args: JSONObject): String {
        val from = args.optString("day_from").trim()
        val to = args.optString("day_to").trim()
        val typeFilter = args.optString("type").trim()
        val datePattern = Regex("""\d{4}-\d{2}-\d{2}""")
        if (!datePattern.matches(from) || !datePattern.matches(to)) {
            return "day_from / day_to 必须是 yyyy-MM-dd 格式。"
        }
        val db = com.healix.app.HealixApp.from(context).database
        val all = try {
            db.eventDao().listInRange(from, to)
        } catch (_: Exception) {
            return "查询失败，请稍后再试。"
        }
        val filtered = if (typeFilter.isEmpty()) {
            all
        } else {
            all.filter { it.type == typeFilter }
        }
        if (filtered.isEmpty()) return "该区间没有任何记录。"
        // token 预算：最多回 50 条，多则提示截断
        val shown = filtered.takeLast(50)
        val lines = shown.map { e ->
            val kcal = if (e.kcal > 0) "（${e.kcal} kcal）" else ""
            "• ${e.dayKey} ${typeName(e.type)}：${e.rawText}$kcal"
        }
        val truncated = if (filtered.size > shown.size) {
            "\n（仅显示最近 ${shown.size} 条，共 ${filtered.size} 条）"
        } else {
            ""
        }
        return lines.joinToString("\n") + truncated
    }

    private suspend fun queryStats(context: Context): String {
        // 与系统提示同一数据源（TodaySummary）：一处口径，两处消费
        return TodaySummary.build(context).lines.joinToString("\n")
    }

    private fun propose(args: JSONObject): Pair<String, LogProposal?> {
        val raw = args.optString("raw_text").trim()
        if (raw.isEmpty()) return "raw_text 不能为空。" to null
        return "已拟好记录草稿：「$raw」。等待用户确认，确认后才会写入。" to LogProposal(raw)
    }

    private fun typeName(type: String): String = when (type) {
        "meal" -> "饮食"
        "exercise" -> "运动"
        "sleep" -> "睡眠"
        "body" -> "身体"
        "illness" -> "生病"
        else -> "其他"
    }
}

/**
 * HealthAgent（S3–S4）：**有界**工具循环。
 *
 * 三重保险（C5 有界自主，缺一不可）：
 * 1. **步数上限** [MAX_STEPS]：每执行一轮工具调用计 1 步，超过即弃局回退单轮；
 * 2. **墙钟上限** [WALL_CLOCK_MS]：从 run() 开始计时，任何一轮开始前检查，
 *    超时即回退 —— 网络慢的用户不等无底洞；
 * 3. **同参即停**：连续两轮工具调用签名（工具名+参数串）完全一致 = 模型卡死，
 *    立即回退，不浪费下一次请求。
 *
 * 降级链：agent（本类）→ 单轮 ChatEngine → 本地模板。
 * 本类只负责第一级到第二级的判定；单轮与模板由调用方（ChatViewModel / ChatEngine）承担。
 *
 * 埋点：**每次** provider 往返都经 [EventRepository.recordChatCall] 记 purpose=ask
 * —— 此前聊天链路从未落 llm_calls，配额计数与设置页「今日对话调用」都靠它。
 */
internal class HealthAgent(
    private val context: Context,
    private val repo: EventRepository,
) {

    suspend fun run(
        config: ProviderConfig,
        sessionDate: String,
        userText: String,
        history: List<ChatMessageEntity>,
        background: String,
        knowledge: String,
    ): AgentOutcome {
        val provider = OpenAiCompatProvider(config)
        val deadline = System.currentTimeMillis() + WALL_CLOCK_MS

        val messages = mutableListOf<ChatMessage>()
        // 系统提示与单轮同源（ChatEngine），只追加工具说明段 ——
        // 人格/边界/隐私规则不因有工具而出现第二套
        messages += ChatMessage(
            role = "system",
            content = ChatEngine.systemPrompt(context, sessionDate, background, knowledge) +
                TOOLS_SECTION,
        )
        history.takeLast(HISTORY_WINDOW).forEach { m ->
            if (m.role == "user" || m.role == "assistant") {
                messages += ChatMessage(role = m.role, content = m.content)
            }
        }
        messages += ChatMessage(role = "user", content = userText)

        var lastSignature = ""
        var steps = 0

        while (steps < MAX_STEPS) {
            if (System.currentTimeMillis() >= deadline) return AgentOutcome.Failed

            val startedAt = System.currentTimeMillis()
            val result = provider.chat(
                ChatRequest(
                    messages = messages.toList(),
                    tools = ToolRegistry.defs,
                    // 与单轮聊天同为 0.7（抽取链 0.3 不动）
                    temperature = 0.7,
                    timeoutMs = TIMEOUT_MS,
                    // 循环内重试降为 2 次：墙钟预算优先给"走完多步"而不是"单步死磕"
                    maxRetries = 2,
                ),
            )
            recordRoundTrip(config, result, System.currentTimeMillis() - startedAt)

            when (result) {
                is ChatResult.Err -> return when (result.kind) {
                    ErrKind.RATE_LIMIT -> AgentOutcome.RateLimited(result.message)
                    else -> AgentOutcome.Failed
                }

                is ChatResult.Ok -> {
                    if (result.toolCalls.isEmpty()) {
                        val text = result.content.trim()
                        return if (text.isEmpty()) AgentOutcome.Failed else AgentOutcome.Done(text)
                    }

                    messages += ChatMessage(
                        role = "assistant",
                        content = result.content,
                        toolCalls = result.toolCalls,
                    )

                    var proposal: LogProposal? = null
                    for (call in result.toolCalls) {
                        val (resultText, proposed) = ToolRegistry.execute(context, call)
                        messages += ChatMessage(
                            role = "tool",
                            content = resultText,
                            toolCallId = call.id,
                        )
                        if (proposed != null) proposal = proposed
                    }

                    // propose_log 是终止性动作：草稿必须经人确认，循环到此为止
                    if (proposal != null) {
                        val note = result.content.trim().ifEmpty {
                            context.getString(com.healix.app.R.string.proposal_note_default)
                        }
                        return AgentOutcome.ProposalPending(note, proposal)
                    }

                    val signature = result.toolCalls
                        .joinToString("|") { "${it.function.name}:${it.function.arguments}" }
                    if (signature == lastSignature) return AgentOutcome.Failed
                    lastSignature = signature
                    steps++
                }
            }
        }
        return AgentOutcome.Failed
    }

    /** 每次往返落一条 llm_calls（purpose=ask）。失败也是真实消耗，同样要记。 */
    private suspend fun recordRoundTrip(
        config: ProviderConfig,
        result: ChatResult,
        latencyMs: Long,
    ) {
        when (result) {
            is ChatResult.Ok -> repo.recordChatCall(
                model = config.model,
                attempts = 1,
                latencyMs = latencyMs,
                status = EventRepository.STATUS_OK,
                httpCode = 200,
                inputTokens = result.usage.inputTokens,
                outputTokens = result.usage.outputTokens,
            )

            is ChatResult.Err -> repo.recordChatCall(
                model = config.model,
                attempts = result.attempts,
                latencyMs = latencyMs,
                status = when (result.kind) {
                    ErrKind.AUTH -> EventRepository.STATUS_HTTP_ERROR
                    ErrKind.TIMEOUT -> EventRepository.STATUS_TIMEOUT
                    else -> EventRepository.STATUS_RETRY_EXHAUSTED
                },
                httpCode = result.httpCode,
                errorHead = result.message,
            )
        }
    }

    companion object {
        /** 步数上限（C5）：一轮"模型响应 + 工具执行"算一步。 */
        private const val MAX_STEPS = 4

        /** 墙钟上限（毫秒）：整个 run() 的自然时间预算。 */
        private const val WALL_CLOCK_MS = 30_000L

        /** 单次往返超时（毫秒）。 */
        private const val TIMEOUT_MS = 12_000L

        /** 历史窗口：与单轮一致（9.2 上下文策略）。 */
        private const val HISTORY_WINDOW = 16

        /**
         * 工具说明段（追加在系统提示之后）。
         * 硬措辞只有两条：查证义务 + 无写入权；其余交给模型自己判断何时用。
         */
        private const val TOOLS_SECTION = """

你可以使用工具（不必每轮都用，已有信息足够就直接回答）：
- query_events：查某日期区间的记录原文。回答"我那天吃了什么/练了什么"必须先查证，禁止凭对话记忆编。
- query_stats：重新取今日摘要数字。刚记录完、数字可能变了就用它。
- propose_log：用户让你记东西时，用用户原话拟一条草稿。草稿经用户确认后才会写入，你无权直接写入记录。
回答里引用的数字只能来自记录原文或工具返回。
"""
    }
}
