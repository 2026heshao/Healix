package com.healix.app.ui

import android.content.Context
import com.healix.app.R
import com.healix.app.db.ChatMessageEntity
import com.healix.app.net.ChatMessage
import com.healix.app.net.ChatRequest
import com.healix.app.net.ChatResult
import com.healix.app.net.OpenAiCompatProvider
import com.healix.app.net.ProviderConfig
import com.healix.app.rules.FoodPool

/**
 * 对话链 prompt 版本号。**独立于**抽取链 `PROMPT_VER`(v2) / 计划链 `PROMPT_VER_PLAN` /
 * 训练链 `PROMPT_VER_TRAINING`。
 *
 * ⚠️ 改动 [ChatEngine.systemPrompt] 内容时必须递增此值（**不改动 `PROMPT_VER`** ——
 *    那是抽取链的版本号，抽取链一字未改）。本常量仅用于 `llm_calls.prompt_ver`
 *    的归因；它**不参与**任何 prompt 字节校验，`systemPrompt` 字节冻结契约不因此变化。
 *
 * v2（2026-10-05）：background 通道扩展（体格/目标/次目标/当前计划段），模板字节不动。
 */
const val PROMPT_VER_CHAT: String = "v2"

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

    /**
     * 单轮回复结果。
     *
     * ⚠️ `2026-10-04` 起增补 usage / latency / httpCode / attempts / errorHead ——
     * 用于**单轮回退路径补记 llm_calls**（§4.1）。全部有默认值，旧调用点
     * （只读 `text` / `state`）无需改动。
     */
    data class Reply(
        val text: String,
        val state: State,
        val latencyMs: Long = 0,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val httpCode: Int? = null,
        val attempts: Int = 0,
        val errorHead: String? = null,
    )

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
        /** 用户背景（设置页「我的情况」）。空串 = 未填写，prompt 里整段省略。 */
        background: String = "",
        /**
         * 知识库命中片段（F12，规范 10.4 ②）。空串 = 未命中，prompt 里整段省略。
         * 只进对话 prompt：`PROMPT_EXTRACT` 一字不改、`PROMPT_VER` 不递增。
         */
        knowledge: String = "",
    ): Reply {
        val provider = OpenAiCompatProvider(config)

        val messages = buildList {
            add(
                ChatMessage(
                    role = "system",
                    content = systemPrompt(context, sessionDate, background, knowledge),
                ),
            )
            // 历史窗口：最近 16 条（9.2 上下文策略）
            history.takeLast(HISTORY_WINDOW).forEach { m ->
                if (m.role == "user" || m.role == "assistant") {
                    add(ChatMessage(role = m.role, content = m.content))
                }
            }
            add(ChatMessage(role = "user", content = userText))
        }

        val startedAt = System.currentTimeMillis()
        val result = provider.chat(
            ChatRequest(
                messages = messages,
                timeoutMs = TIMEOUT_MS,
                maxRetries = MAX_RETRIES,
                // 对话要"活"：0.3 是抽取 JSON 的参数，聊天用它必然每问同答。
                // 只动聊天链路 —— 抽取链（EventRepository 0.3 / TrainingPlanner 0.4）不动。
                temperature = 0.7,
            ),
        )
        val latencyMs = System.currentTimeMillis() - startedAt

        return when (result) {
            is ChatResult.Ok -> {
                val text = result.content.trim()
                if (text.isEmpty()) {
                    Reply(
                        text = localFallback(context),
                        state = State.Degraded,
                        latencyMs = latencyMs,
                        inputTokens = result.usage.inputTokens,
                        outputTokens = result.usage.outputTokens,
                        httpCode = 200,
                        attempts = 1,
                    )
                } else {
                    Reply(
                        text = text,
                        state = State.Ok,
                        latencyMs = latencyMs,
                        inputTokens = result.usage.inputTokens,
                        outputTokens = result.usage.outputTokens,
                        httpCode = 200,
                        attempts = 1,
                    )
                }
            }

            is ChatResult.Err -> when (result.kind) {
                com.healix.app.net.ErrKind.RATE_LIMIT ->
                    // 限流是"排队中"而不是失败 —— 交给 UI 显示预估秒数
                    Reply(
                        text = result.message,
                        state = State.Queued,
                        latencyMs = latencyMs,
                        httpCode = result.httpCode,
                        attempts = result.attempts,
                        errorHead = result.message,
                    )

                else -> Reply(
                    text = localFallback(context),
                    state = State.Degraded,
                    latencyMs = latencyMs,
                    httpCode = result.httpCode,
                    attempts = result.attempts,
                    errorHead = result.message,
                )
            }
        }
    }

    /**
     * 系统提示（功能补充 9.2 / PRD §8.2）。
     *
     * ## v5 改动（2026-10-04，「活 AI」重构）
     * 旧版是 14 条禁令式规则（"禁止/不要/只能"11 处 + "4 句以内"），
     * 实测模型在避雷而不是思考：同问三遍得到逐字相同的回答，追问药物只吐一句套话。
     *
     * 新结构 = **身份 + 说话方式 + 少量硬边界**：
     * - 把"怎么措辞"的决定权还给模型（句数、分点、追问与否自己判断）；
     * - 硬边界只留真正不能碰的：数字只引用记录、硬约束段（忌口/疼痛/器材）、
     *   不推断疾病/不给用药剂量、隐私开关；
     * - 原规则 5/9/12 的增量价值（具体食物+分量、组次×次数、行动三要素）
     *   压缩进"说话方式"段，从禁令变成产出标准；
     * - 用药问题的答法从"只说多喝水"改为：不给药名剂量，但给护理方向 + 何时该就医。
     *
     * ⚠️ 本处改动**不递增 `PROMPT_VER`** —— 那是抽取链的版本号，
     * 而抽取链（`PROMPT_EXTRACT`）一字未改，回归基线 21/22 继续有效。
     * 这是刻意的边界，不是漏改。
     *
     * ## 关于 [background]（设置页「我的情况」）
     *
     * 拼在 system prompt **最前面**，理由：模型对 system 前段的内容权重更高，
     * 且它是"解释后续所有规则的前提"（比如用户写了乳糖不耐，后面所有饮食
     * 建议都应绕开乳制品）。
     *
     * **只进这一处 prompt** —— `PROMPT_EXTRACT`（抽取链）一字不改。
     * 抽取链的任务是把口语转成 JSON，用户背景对"这句话说了什么"没有信息量，
     * 塞进去反而会挤占 token 并可能诱导模型改写 foods 字段。
     *
     * S3 起改为 **internal**：HealthAgent 复用同一份系统提示（人格/边界/隐私
     * 规则不因有工具而出现第二套），只在其后追加工具说明段。
     */
    internal fun systemPrompt(
        context: Context,
        sessionDate: String,
        background: String,
        knowledge: String,
    ): String {
        val summary = TodaySummary.build(context)
        val summaryText = summary.lines.joinToString("\n")

        // 常吃食物池（F7）：近 30 天 meal foods 频次聚合，≥3 次进清单，
        // 频率降序。空 = 记录太少，整行省略（不给模型一份假清单）。
        val foodPool = FoodPool.build(context)
        val foodPoolLine = if (foodPool.isEmpty()) {
            ""
        } else {
            "这个人常吃/买得到的食物（按频率）：${foodPool.joinToString("、")}\n"
        }

        // 背景段：空则整段省略 —— 不留「我的情况：（空）」这种噪声，
        // 那会让模型去猜测一个不存在的约束。
        val backgroundBlock = if (background.isBlank()) {
            ""
        } else {
            """
关于这个人的已知情况（用户自己写的，视为可信前提）：
$background

""".trimStart('\n')
        }

        // 知识库段（F12，规范 10.4 ②）：拼在 backgroundBlock 之后、边界之前，
        // 总量 ≤300 token（约 450 汉字，由 KnowledgeRepository.search 裁剪）。
        // 空则整段省略 —— 未命中时不给模型任何关于知识库的提示（常态零噪声）。
        val knowledgeBlock = if (knowledge.isBlank()) {
            ""
        } else {
            """
知识库摘录（来自用户上传的文档，视为可信资料）：
$knowledge

""".trimStart('\n')
        }

        // 隐私硬边界（PRD §14.3）：不是"别提 kcal 这个词"，
        // 而是"这个人不想在对话里看到这些数字"。
        val privacyNote = buildString {
            if (summary.hideKcal) {
                append("\n5. 这个人不想看到热量数字，回答里不要出现任何 kcal 数值。")
            }
            if (summary.hideWeight) {
                append("\n6. 这个人不想看到体重数字，回答里不要出现任何体重数值。")
            }
        }

        return """
${backgroundBlock}${knowledgeBlock}你是 Healix 的健康助理。这个人当前的主要目标是${summary.primaryGoalName}，
同时也关心运动、睡眠和身体状况。今天是 $sessionDate。

今天的已知数字（本地记录，可信）：
$summaryText

${foodPoolLine}你的说话方式：
像一个懂行、也在认真训练和吃饭的朋友，直接、有温度、有判断。回答多长由问题决定：一句话能答的别凑三句；给建议时要落到具体的食物+分量、或动作+组数×次数，并且优先用这个人手头有的东西（背景里的食物/器材，其次常吃清单），说明为什么是现在做这件事。深夜（23 点后）的饮食建议优先免烹饪、易消化的选项，并说明原因。今天没记录的数据就直说"还没记录"，你不猜数；不确定的事先给判断再讲理由，别用"建议咨询医生"这类套话挡回去——真需要就医就直接说"这种该去看医生"。用户让你记录时：能确定就确认记下，缺信息就问一句补什么。别在回复开头重复固定指引。

硬边界（碰不得，其余你自己拿主意）：
1. 数字（摄入、体重、运动量）只能来自上面给出的记录，没有就说没有，禁止估算当日总量。
2. 「硬约束——必须遵守」段里的忌口、疼痛部位、运动条件必须遵守：饮食绕开忌口，运动避开疼痛部位相关动作、只用运动条件里的器材/场地；用户要求和硬约束冲突时，指出冲突并给替代方案。
3. 不做疾病推断、不给用药或剂量建议、不给健康评分。问"吃什么药"这类问题时，可以给护理方向（休息、补水、物理降温等）和"出现什么情况该就医"，但不点名药物和剂量。今天或昨天有生病记录时，不推训练，推休息、补水、睡眠。
4. 全程中文。$privacyNote
""".trim()
    }

    /** 降级：本地模板回答（功能补充 1.7，AI 不能是唯一热源）。 */
    private fun localFallback(context: Context): String {
        val s = TodaySummary.build(context)
        // 隐私开关打开时，连降级回答也不能出现 kcal 数字 —— 否则"隐藏"只挡住了 AI
        if (s.hideKcal) return context.getString(R.string.fallback_kcal_hidden)
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
