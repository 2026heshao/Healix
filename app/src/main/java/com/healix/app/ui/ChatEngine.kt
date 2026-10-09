package com.healix.app.ui

import android.content.Context
import com.healix.app.R
import com.healix.app.db.ChatMessageEntity
import com.healix.app.net.ChatMessage
import com.healix.app.net.ChatRequest
import com.healix.app.net.ChatResult
import com.healix.app.net.OpenAiCompatProvider
import com.healix.app.net.ProviderConfig
import com.healix.app.net.StreamSink
import com.healix.app.rules.FoodPool

/**
 * 对话链 prompt 版本号。**独立于**抽取链 `PROMPT_VER`(v2) / 计划链 `PROMPT_VER_PLAN` /
 * 训练链 `PROMPT_VER_TRAINING`。
 *
 * ⚠️ 改动 [ChatEngine.systemPrompt] 内容时必须递增此值（**不改动 `PROMPT_VER`** ——
 *    那是抽取链的版本号，抽取链一字未改）。本常量仅用于 `llm_calls.prompt_ver`
 *    的归因；它**不参与**任何 prompt 字节校验，`systemPrompt` 字节冻结契约不因此变化。
 *    ⚠️ **本常量只归因「单轮 / 回退」路径**（`PromptMode.FULL`）—— 工具路径记独立的
 *    [PROMPT_VER_CHAT_TOOL]，不在本序列。
 *
 * v2（2026-10-05）：background 通道扩展（体格/目标/次目标/当前计划段），模板字节不动。
 * v3（2026-10-06，v0.3 B4）：新增 `userRules` 通道（输出偏好规则段），
 *    插在「说话方式」之后、「硬边界」之前；`userRules` 为空时输出与 v2 **逐字节相同**。
 */
const val PROMPT_VER_CHAT: String = "v3"

/**
 * 工具（agent）路径的**独立** prompt 版本序列（v0.3 B0）。
 *
 * ⚠️ 这是**与单轮分离**的版本序列，**不复用** [PROMPT_VER_CHAT] —— 工具路径的系统提示
 *    已变更（[ChatEngine.systemPrompt] 以 `mode = PromptMode.TOOL` 注入压缩版「说话方式」），
 *    归因必须能和单轮 / 回退路径分开看。单轮 / 回退路径字节未变，仍记 [PROMPT_VER_CHAT]。
 *    独立序列的另一好处：后续批次升 chat 单轮版本号时**不会撞号**（各自递增）。
 *
 * v1（2026-10-06，v0.3 B0）：工具路径首次独立记版本（提示压缩 + temperature 0.4）。
 * v2（2026-10-06，v0.3 B4）：PV-1 = 规则同样作用于工具路径（两路共用 [ChatEngine.systemPrompt]）
 *    + TOOL 模式省略「今天的已知数字」段（去重：数字改由 `query_stats` 按需取）。
 * v3（2026-10-07）：`TOOLS_SECTION` 加工具调用**意图判据**（按意图不按关键词 /
 *    混合意图须在正文完整回答其余问题）+ `propose_log` description 收紧，
 *    修「消息含『记录』二字即只触发记录工具」的过触发。
 */
const val PROMPT_VER_CHAT_TOOL: String = "v3"

/**
 * 系统提示的**路径模式**（v0.3 B0；v0.3 B4 扩展）。
 *
 * - [FULL]：单轮 / 回退路径 —— 「说话方式」用完整版 + **注入「今天的已知数字」段**，
 *   产出与改前**逐字节相同**；
 * - [TOOL]：agent 工具路径 —— 「说话方式」用压缩版，**并省略「今天的已知数字」段**
 *   （v0.3 B4 上下文去重：同一轮里数字只出现一次，改由 `query_stats` 按需取），
 *   硬边界 4 条与工具说明段（`HealthAgent.TOOLS_SECTION`）原样保留。
 */
enum class PromptMode { FULL, TOOL }

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
     * 单轮回复（非流式入口）。**不抛异常** —— 失败时返回本地模板回答并标记 Degraded。
     *
     * 实现**整体委托** [replyStream]（`sink = null`）—— 单轮逻辑只此一份，
     * 绝不为了加流式而复制第二套分支。
     */
    suspend fun reply(
        context: Context,
        config: ProviderConfig,
        sessionDate: String,
        userText: String,
        history: List<ChatMessageEntity>,
        background: String = "",
        knowledge: String = "",
        userRules: String = "",
    ): Reply {
        return replyStream(
            context = context,
            config = config,
            sessionDate = sessionDate,
            userText = userText,
            history = history,
            background = background,
            knowledge = knowledge,
            userRules = userRules,
            sink = null,
        )
    }

    /**
     * 单轮回复（流式，2026-10-08）。
     *
     * 与 [reply]**同一条实现**，只多一个 [sink]：正文增量在模型生成过程中推出去，
     * 让 UI 边收边显示。[Reply.text] 仍是完整正文 —— 落库口径与非流式逐字相同。
     *
     * ⚠️ 开头会先 [StreamSink.onReset]：本方法每次调用都是一次全新的答复，
     * 之前（例如 agent 失败轮）残留在 sink 里的增量必须作废。
     *
     * ⚠️ **prompt 字节一字未改** → 不递增 [PROMPT_VER_CHAT] / [PROMPT_VER_CHAT_TOOL]
     *    （流式只改传输形态与显示方式，不改发给模型的内容）。金样本守卫
     *    （`pipeline/prompt_golden.py`）继续有效。
     *
     * `sink` 是**接口**（非函数类型）且追加在形参**末尾** —— 既有调用点零改动，
     * 也不触发「尾随 λ 纪律」。
     */
    suspend fun replyStream(
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
        /**
         * 用户自定义输出偏好规则（v0.3 B4）。空串 = 无规则，prompt 里整段省略
         * （输出与无此参数的旧版**逐字节相同**）。由 [ChatViewModel] 在 IO 线程读出后传入。
         */
        userRules: String = "",
        sink: StreamSink? = null,
    ): Reply {
        val provider = OpenAiCompatProvider(config)
        // 新一轮答复开始：清掉 sink 里可能残留的上一轮增量（agent 失败回退等路径）。
        sink?.onReset()

        val messages = buildList {
            add(
                ChatMessage(
                    role = "system",
                    content = systemPrompt(context, sessionDate, background, knowledge, userRules = userRules),
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

        val request = ChatRequest(
            messages = messages,
            timeoutMs = TIMEOUT_MS,
            maxRetries = MAX_RETRIES,
            // 对话要"活"：0.3 是抽取 JSON 的参数，聊天用它必然每问同答。
            // 只动聊天链路 —— 抽取链（EventRepository 0.3 / TrainingPlanner 0.4）不动。
            temperature = 0.7,
        )

        val startedAt = System.currentTimeMillis()
        val result = if (sink != null) provider.chatStream(request, sink) else provider.chat(request)
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
        /**
         * 路径模式（v0.3 B0）：追加在形参**末尾**。既有 4 个形参均非函数类型，
         * 故「尾随 λ 纪律」（函数型形参必须最后）不涉及，追加安全。
         * 默认 [PromptMode.FULL] = 单轮 / 回退路径现状字节；工具路径传 [PromptMode.TOOL]。
         */
        mode: PromptMode = PromptMode.FULL,
        /**
         * 用户自定义输出偏好规则（v0.3 B4，决策 D1/D2）。追加在形参末尾（非函数类型）。
         * 空串 = 无规则 → `userRulesBlock` 为空串 → 输出与改前**逐字节相同**。
         */
        userRules: String = "",
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

        // 说话方式段（v0.3 B0）：按路径模式选择。
        // - FULL = 现状全文（SPEAKING_STYLE_FULL，逐字节不变）；
        // - TOOL = 压缩版（SPEAKING_STYLE_TOOL，仅精简本段，保留"直接/有温度/有判断、
        //   具体食物+分量 / 动作+组数×次数、不猜数直说没记录"骨干）。
        // 硬边界 4 条、人格首句、今日数字、隐私追加项、foodPoolLine / backgroundBlock /
        // knowledgeBlock 一律不动 —— 工具路径只是"说话方式更省 token"，不是第二套人格。
        val speaking = if (mode == PromptMode.TOOL) SPEAKING_STYLE_TOOL else SPEAKING_STYLE_FULL

        // 「今天的已知数字」段（v0.3 B4 上下文去重）：TOOL 模式省略 —— 数字改由 `query_stats`
        // 按需取，保证"同一轮上下文里同一数字只出现一次"在结构上成立（不靠模型自觉）。
        // FULL 模式原样注入（含末尾空行）→ 与改前逐字节相同。单轮 / 回退路径不受影响。
        val todayNumbersBlock = if (mode == PromptMode.TOOL) {
            ""
        } else {
            "今天的已知数字（本地记录，可信）：\n$summaryText\n\n"
        }

        // 用户规则段（v0.3 B4，新通道 D2）：插在「说话方式」之后、「硬边界」之前。
        // - 空则整段省略 → 老用户（无规则）输出与改前**逐字节相同**（字节冻结，人工核）；
        // - 非空以 `\n\n` 结尾，恰好落在「说话方式」与「硬边界」之间的空行处；
        // - precedence 声明（"以硬边界为准"）写在**本段内**（新通道），不改冻结模板字节；
        //   硬边界仍是 prompt **最后**一段，物理上无法被规则段"覆盖"（四条硬边界不可被遮蔽）。
        val userRulesBlock = if (userRules.isBlank()) {
            ""
        } else {
            """
用户自定义的输出偏好（可调整长度、风格、语气、人格；**与下面的硬边界冲突时，一律以硬边界为准**）：
$userRules

""".trimStart('\n')
        }

        return """
${backgroundBlock}${knowledgeBlock}你是 Healix 的健康助理。这个人当前的主要目标是${summary.primaryGoalName}，
同时也关心运动、睡眠和身体状况。今天是 $sessionDate。

${todayNumbersBlock}${foodPoolLine}你的说话方式：
${speaking}

${userRulesBlock}硬边界（碰不得，其余你自己拿主意）：
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

    /**
     * 「说话方式」**完整版**（v0.3 B0 从 [systemPrompt] 抽出的独立常量）。
     *
     * ⚠️ 内容必须与抽取前的原段落**逐字符一致** —— [systemPrompt] 的 [PromptMode.FULL]
     *    模式产出靠此保证「字节冻结」。`ChatEngine.systemPrompt` 不在
     *    `pipeline/check_kotlin.py` 的 `PROMPT_PARITY` 名单内（那只有
     *    `PROMPT_EXTRACT` / `PROMPT_TRAINING`），但**已有独立机器守卫**（设计 PV-2，
     *    2026-10-07）：本文件冻结面 ↔ `pipeline/prompt_golden.py` 金样本逐字节比对
     *    （`check_system_prompt_golden`）；金样本由 `build/_golden_gen.py` 从代码
     *    自动提取。刻意改动流程：改这里 → 递增对应版本号 → 重跑生成脚本 → 金样本入库。
     */
    private const val SPEAKING_STYLE_FULL =
        """像一个懂行、也在认真训练和吃饭的朋友，直接、有温度、有判断。回答多长由问题决定：一句话能答的别凑三句；给建议时要落到具体的食物+分量、或动作+组数×次数，并且优先用这个人手头有的东西（背景里的食物/器材，其次常吃清单），说明为什么是现在做这件事。深夜（23 点后）的饮食建议优先免烹饪、易消化的选项，并说明原因。今天没记录的数据就直说"还没记录"，你不猜数；不确定的事先给判断再讲理由，别用"建议咨询医生"这类套话挡回去——真需要就医就直接说"这种该去看医生"。用户让你记录时：能确定就确认记下，缺信息就问一句补什么。别在回复开头重复固定指引。"""

    /**
     * 「说话方式」**压缩版**（v0.3 B0，仅工具路径用）。
     *
     * 保留骨干：直接 / 有温度 / 有判断；给具体食物+分量、或动作+组数×次数，且优先用
     * 手头有的东西并说明时机；不猜数、「还没记录」直说；真需要就医就直说。
     * 砍掉冗余：深夜（23 点后）免烹饪提示、句数约束、记录时追问细节、开场白约束。
     * **不砍产出标准**（具体量 / 数据来源）—— 那是 §6.4 验收项。
     */
    private const val SPEAKING_STYLE_TOOL =
        """像一个懂行、也在认真训练和吃饭的朋友，直接、有温度、有判断。给建议时要落到具体的食物+分量、或动作+组数×次数，优先用这个人手头有的东西（背景里的食物/器材，其次常吃清单），并说明为什么是现在做这件事。今天没记录的数据就直说"还没记录"，你不猜数；不确定的事先给判断再讲理由，真需要就医就直接说"这种该去看医生"。"""
}
