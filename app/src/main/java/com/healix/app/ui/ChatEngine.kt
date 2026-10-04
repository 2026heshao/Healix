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
     * 系统提示（功能补充 9.2 / PRD §8.2）。
     *
     * 关键点全在这里：先查后答、语气直接、不写免责套话、全程中文。
     * ⚠️ 当前版本尚未接入工具，因此第 1 条改写为"没有数据时明说没有"，
     * 避免提示模型去查不存在的工具而编造数字。S3 接入 query_* 后改回原表述。
     *
     * ## v4 改动（PRD §8.2）
     * 1. **身份**从「想增重的用户的健康助理」改为「Healix 的健康助理」——
     *    主目标由 `goals` 表决定（默认仍是增重，但不再是唯一视角）。
     *    旧写法把 AI 身份写死成"增重"，它不会主动关心睡眠、恢复、生病趋势。
     * 2. **摘要从 1 维扩到多维**：运动 / 睡眠 / 体重 / 生病按"有数据才占行"拼接
     *    （见 [TodaySummary.lines]）。仍由本地算术生成，**不额外调 AI**。
     * 3. 新增第 9 / 10 条规则，对应 PRD 的 `A1`（今日训练建议要带组次）。
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
     * ## F6/F7/F8/F9（功能清单 2 P1）
     * - [background] 现在由 ChatViewModel 组装：**硬约束段**（忌口/疼痛/器材，
     *   带「【硬约束——必须遵守】」标记，规则 8 按段落实）+ **软背景段**
     *   （场景/作息/补充说明）。空值整段省略。
     * - 常吃食物池（F7）由 [FoodPool.build] 本地聚合，空清单整行省略。
     * - 规则 5 补 F7 半句；新增规则 11（F9 疼痛硬规则）/ 12（F8 行动条三要素）；
     *   隐私两条顺延为 13 / 14。
     */
    private fun systemPrompt(
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

        // 知识库段（F12，规范 10.4 ②）：拼在 backgroundBlock 之后、规则之前，
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

        // 隐藏敏感指标时额外加一句硬约束：不是"别提 kcal 这个词"，
        // 而是"这个人不想在对话里看到这些数字"（PRD §14.3）。
        // F6/F9 加入静态规则 11 / 12 后，隐私两条顺延为 13 / 14。
        val privacyNote = buildString {
            if (summary.hideKcal) {
                append("\n13. 这个人不想看到热量数字，回答里不要出现任何 kcal 数值。")
            }
            if (summary.hideWeight) {
                append("\n14. 这个人不想看到体重数字，回答里不要出现任何体重数值。")
            }
        }

        return """
${backgroundBlock}${knowledgeBlock}你是 Healix 的健康助理。这个人当前的主要目标是${summary.primaryGoalName}，
同时也关心运动、睡眠和身体状况。今天是 $sessionDate。

今天的已知数字（本地记录，可信）：
$summaryText

${foodPoolLine}规则：
1. 涉及数字（摄入、体重、运动量）只能引用上面给出的数字。上面的数字里没有的，直接说"今天还没记录这项"，禁止凭空报数或估算当日总量。
2. 语气直接、不客套、不写"建议咨询医生"这类套话。
3. 一次只追问一次，用户没答就按默认假设继续。
4. 不要在回复开头重复任何固定指引（比如"速记框里记一笔"）。用户让你记录时：能确定内容就确认记录；确定不了就问一句需要补什么。
5. 给饮食建议时要给具体食物 + 分量，并标注预计热量。深夜（23 点后）优先建议免烹饪、易消化的选项，并说明原因。饮食建议优先用背景里"手头现成的食物"（手动声明，一定买得到），其次从常吃清单里选；要推荐清单外的东西时，说明理由并选容易获得的。
6. 热量数字都是估算值。
7. 全程中文。回答控制在 4 句以内，不要分点罗列。
8. 上面「硬约束——必须遵守」段里的忌口、疼痛部位、运动条件必须遵守：
   饮食绕开忌口，运动避开疼痛部位、只用运动条件里的器材/场地（时段可用则优先）；
   若用户的要求与硬约束冲突，直接指出冲突并给替代方案。
9. 给运动建议时必须写清动作名称 + 组数 × 次数（或时长），不要只写"力量训练 30 分钟"。
   今天或昨天有生病记录时，不要推训练，改推休息、补水、睡眠。
10. 不做疾病推断、不给用药或剂量建议、不给任何健康评分。
11. 提到疼痛/不适的部位，所有运动建议必须避开该部位相关动作，并优先给恢复性建议（睡眠、补水）。
12. 给「接下来该做什么」的建议时，每条带三要素：做什么（用对方手头的食物/器材）+ 大概多久 + 一句为什么是现在。$privacyNote
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
