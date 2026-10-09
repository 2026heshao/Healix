package com.healix.app.net

/**
 * Provider 抽象层（对齐 `docs/archive/功能补充与套壳选型.md` 9.1 与 Python 侧 `pipeline/provider.py`）。
 *
 * 设计要点：
 * - 一个接口 + 一个实现（OpenAiCompatProvider），覆盖智谱 / DeepSeek / OpenRouter / SiliconFlow。
 *   差异只在 baseUrl / model / apiKey 三个字符串 —— 三者都进设置页，代码里零硬编码。
 * - 失败**绝不抛异常**，一律返回 [ChatResult.Err]。调用方永远只需 when 分支。
 * - 所有超时显式传数值（C4），不依赖 OkHttp 默认值。
 *
 * Kotlin 与 Python 字段对照（contract v1，两边改动必须同步）：
 * | Python (provider.py)   | Kotlin (本文件)              |
 * |------------------------|------------------------------|
 * | `ChatMessage`          | [ChatMessage]                |
 * | `Usage`                | [Usage]                      |
 * | `ChatRequest`          | [ChatRequest]                |
 * | `Ok` / `Err` / union   | [ChatResult.Ok] / [.Err]     |
 * | `ErrKind`              | [ErrKind]                    |
 * | `ProviderConfig`       | [ProviderConfig]             |
 * | `PROVIDER_PRESETS`     | [PROVIDER_PRESETS]           |
 */

/** 单条对话消息。role ∈ system | user | assistant | tool。 */
data class ChatMessage(
    val role: String,
    val content: String,
    /** assistant 决定调用工具时非空。元素形如 OpenAI 的 tool_calls 原始结构 */
    val toolCalls: List<ToolCall>? = null,
    /** role = tool 时，对应 assistant 那次的 tool call id */
    val toolCallId: String? = null,
)

/**
 * 工具调用。字段名与 OpenAI 兼容端点一致，**但内部字段名不直接当 wire 用** ——
 * 序列化时由 OpenAiCompatProvider 显式拼 JSON，见 messageToWire。
 *
 * 注意：`function.arguments` 是**字符串**（模型返回的 JSON 文本），不是对象。
 * 这是 OpenAI 协议的既有设计，不要"优化"成对象。
 */
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolFunction,
)

data class ToolFunction(
    val name: String,
    val arguments: String,
)

/** 工具定义（交给模型的 schema）。 */
data class ToolDef(
    val type: String = "function",
    val function: ToolFunctionDef,
)

data class ToolFunctionDef(
    val name: String,
    val description: String,
    /** JSON Schema 对象 */
    val parameters: Map<String, Any?>,
)

/** token 用量。字段名对齐 OpenAI 的 prompt_tokens / completion_tokens。 */
data class Usage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
)

/** 单次对话请求。 */
data class ChatRequest(
    val messages: List<ChatMessage>,
    val tools: List<ToolDef>? = null,
    val temperature: Double = 0.3,
    /** 显式超时，毫秒。硬要求：不允许用 OkHttp 默认值 */
    val timeoutMs: Long = 15_000,
    /** 最大重试次数（不含首次）。默认 5，见 contract.md 第二节 */
    val maxRetries: Int = 5,
    /** 初始退避间隔，秒。默认 1.5 */
    val retryBaseSeconds: Double = 1.5,
    /** true = 1.5 × 2^(n-1) 指数退避；false = 每次固定 retryBaseSeconds */
    val exponentialBackoff: Boolean = true,
)

/** 错误分类（C4：可重试 / 需人工 / 终止）。 */
enum class ErrKind {
    /** 含 429 / 5xx，可重试 */
    HTTP,
    /** 可重试 */
    TIMEOUT,
    /** 模型返回非法 JSON，可重试（重试价值低） */
    PARSE,
    /** key 错 / 欠费，重试无意义 → 终止 */
    AUTH,
    /** 明确限流，退避后重试 */
    RATE_LIMIT,
    /** DNS / 连接失败，可重试 */
    NETWORK,
}

/** 可重试的错误类型。 */
val RETRYABLE_ERR_KINDS: Set<ErrKind> = setOf(
    ErrKind.HTTP,
    ErrKind.TIMEOUT,
    ErrKind.PARSE,
    ErrKind.RATE_LIMIT,
    ErrKind.NETWORK,
)

/** 命中即**不重试**的 HTTP 码：重试也是白等。 */
val FATAL_HTTP_CODES: Set<Int> = setOf(400, 401, 403, 404, 422)

/** 对话结果。sealed interface：穷举 when，编译器强制处理失败分支。 */
sealed interface ChatResult {

    data class Ok(
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val usage: Usage = Usage(),
    ) : ChatResult

    data class Err(
        val kind: ErrKind,
        val message: String,
        val httpCode: Int? = null,
        /** 已尝试次数（含首次）。首次即失败 = 1 */
        val attempts: Int = 0,
    ) : ChatResult
}

/** Provider 配置。三个字符串定义一切，全部来自设置页。 */
data class ProviderConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val providerName: String = "custom",
) {
    /** 端点拼接：去掉尾部多余斜杠，避免出现 `//chat/completions`。 */
    fun endpoint(): String = baseUrl.trimEnd('/') + "/chat/completions"

    /** 配置是否可用（预设里的 `[待核实: ...]` 占位符不算可用）。 */
    fun isUsable(): Boolean =
        baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank() &&
            !baseUrl.startsWith("[待核实") && !model.startsWith("[待核实")

    /**
     * 模型不被端点支持时的**兜底模型**（N-8，2026-10-08）。空串 = 无兜底。
     *
     * 需求：DeepSeek 优先用 `deepseek-flash`，API 不支持（400 Model Not Exist）
     * 则自动回落 `deepseek-chat`；模型名在设置页始终可编辑。判定挂在
     * [baseUrl] 上（含 `api.deepseek.com` 即 DeepSeek 官方端点），不新增
     * settings 键 —— 兜底是**端点的属性**，不是用户配置；主模型是什么
     * （flash / chat / 将来新名）由用户在设置页自由改，兜底只在
     * 「主模型 ≠ 兜底模型」时才存在。
     */
    val effectiveFallbackModel: String
        get() {
            if (!baseUrl.contains(DEEPSEEK_HOST)) return ""
            if (model.isBlank() || model == DEEPSEEK_FALLBACK_MODEL) return ""
            return DEEPSEEK_FALLBACK_MODEL
        }

    companion object {
        /** DeepSeek 官方 API 域名（预设 baseUrl = https://api.deepseek.com）。 */
        const val DEEPSEEK_HOST = "api.deepseek.com"

        /** DeepSeek 长期稳定的模型别名（官方文档核实，见 [ProviderPresets] 头注释）。 */
        const val DEEPSEEK_FALLBACK_MODEL = "deepseek-chat"
    }
}

/**
 * 流式增量的接收端（2026-10-08）。
 *
 * 为什么不直接把增量拼进返回值：流式的全部意义就是「边生成边显示」，增量必须
 * 在**调用进行中**推出去，而不是等 `chat()` 返回 —— 所以走回调，不走返回值。
 *
 * 回调在**网络 IO 线程**触发（不在主线程），实现方需自行保证线程安全。
 */
interface StreamSink {

    /**
     * 新的一轮模型调用开始：此前累积的增量一律作废。
     *
     * 为什么需要它：工具循环（[com.healix.app.agent.HealthAgent]）一轮里可能调
     * **多次**模型 —— 只有产出最终答复的那一轮正文该给用户看，上一轮的中间文本
     * 必须丢掉。`net` 层自身从不发这个事件（它只看得到一次调用），
     * 由 agent 在每轮开始前发出。
     *
     * 默认空实现：单轮路径不关心轮次边界。
     */
    fun onReset() {}

    /** 收到一段增量正文（已解码）。可能为空串，实现方需容忍。 */
    fun onDelta(text: String)

    /**
     * 一轮模型调用**结束**时的裁决（2026-10-09，真机问题 2 的中间轮泄漏）。
     *
     * [onReset] 只在「下一轮开始前」清场，而**终止轮没有下一轮** —— 于是中间轮
     * （查数据那轮）模型顺手写的正文，会一直显示到它被下一轮抹掉为止，用户先看到
     * 再看着它消失（实测「写着写着突然换掉 / 新旧文字叠在一起」）。
     *
     * 本回调把裁决点**前移**到轮末：由 agent（唯一知道本轮产没产工具调用的地方）
     * 在每轮结束时明确告知，实现方据此决定本轮累积是保留还是**整轮丢弃**。
     * `hasToolCalls = true` 表示这是中间轮，其正文不属于最终答复。
     *
     * 默认空实现：单轮路径（[com.healix.app.ui.ChatEngine.replyStream]）本就没有
     * 多轮概念，行为与改前逐字相同。
     */
    fun onRoundEnd(hasToolCalls: Boolean) {}
}

/** Provider 接口。无状态实现，可安全复用。 */
interface LlmProvider {

    /** 持有的配置，便于埋点取 model 名。 */
    val config: ProviderConfig

    /**
     * 单次对话。**绝不抛异常** —— 一切失败返回 [ChatResult.Err]。
     *
     * 实现必须保证：
     * - 显式超时（request.timeoutMs）
     * - 退避重试（request.maxRetries / retryBaseSeconds / exponentialBackoff）
     * - FATAL_HTTP_CODES 与 AUTH 不重试
     */
    suspend fun chat(request: ChatRequest): ChatResult

    /**
     * 单次对话（流式）。语义与 [chat]**逐条一致** —— 同样的显式超时、退避重试、
     * 错误分类、脱敏、**绝不抛异常**；差别只有两点：
     *
     * 1. 正文增量在生成过程中经 [sink] 推出（边生成边显示）；
     * 2. 返回值里的 `content` 仍是**完整正文**（与 [chat] 同口径），调用方结束时
     *    用返回值落库即可，无需自己拼接增量。
     *
     * ⚠️ **重试策略的一处收紧**：本方法在**已经吐出过增量**后不再重试 —— 重试会把
     * 同一段正文再吐一遍，用户看到的是重复内容。此前的增量不作废（拼不回干净状态），
     * 失败直接以 [ChatResult.Err] 返回，由调用方走降级链。
     *
     * ⚠️ **超时口径**：整体 `callTimeout` 放宽到 [STREAM_CALL_TIMEOUT_MS]（流式响应
     * 天然比一次性响应长），但 `connectTimeout` / `readTimeout` 仍取 `request.timeoutMs`
     * —— 后者在流式下是**块间空闲超时**，断流仍会在一个 timeoutMs 内被发现。
     */
    suspend fun chatStream(request: ChatRequest, sink: StreamSink): ChatResult

    companion object {
        /** 流式请求的整体墙钟上限（毫秒）：一次性请求的 `callTimeout` 对长流太短。 */
        const val STREAM_CALL_TIMEOUT_MS: Long = 120_000L
    }
}

/**
 * 设置页预设表。
 *
 * ⚠️ 核实记录（2026-10-03，全部取自官方文档原文，非记忆）：
 *   智谱    https://docs.bigmodel.cn/cn/guide/develop/http/introduction
 *           → https://open.bigmodel.cn/api/paas/v4/
 *   智谱模型  回归实测 `glm-4-flash-250414` 21/22 通过且无 429；
 *           `glm-4.7-flash` 存在但免费档持续 1305 限流。
 *           ⚠️ 官方文档已标注「GLM-4.5 / GLM-4.5-X 即将下线」，新旗舰为 GLM-4.7。
 *           本预设保守取回归验证过的 glm-4-flash；用户可在设置页自行改。
 *   DeepSeek  https://api-docs.deepseek.com/ → https://api.deepseek.com
 *           文档当前给出 model 为 `deepseek-flash` / `deepseek-v4-pro`；
 *           此处取长期稳定的 `deepseek-chat` 别名。
 *   OpenRouter https://openrouter.ai/docs/api-reference/overview
 *           → https://openrouter.ai/api/v1（模型 ID 需带 org 前缀）
 *   硅基流动   https://docs.siliconflow.cn/cn/api-reference/chat-completions/chat-completions
 *           → https://api.siliconflow.cn/v1
 *
 * 与 Python 侧 `pipeline/provider.py` 的 `PROVIDER_PRESETS` 必须保持一致。
 */
object ProviderPresets {

    /** 预设 key → (展示名, baseUrl, model) */
    val PRESETS: Map<String, PresetEntry> = linkedMapOf(
        "zhipu" to PresetEntry(
            name = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            model = "glm-4-flash",
        ),
        "deepseek" to PresetEntry(
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            // N-8（2026-10-08 用户拍板）：优先 deepseek-flash；若端点不支持
            // （400 Model Not Exist），OpenAiCompatProvider 经
            // ProviderConfig.effectiveFallbackModel 自动回落 deepseek-chat，
            // 无需用户改配置。模型名始终可在设置页编辑。
            model = "deepseek-flash",
        ),
        "openrouter" to PresetEntry(
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "deepseek/deepseek-chat-v3.1:free",
        ),
        "siliconflow" to PresetEntry(
            name = "SiliconFlow",
            baseUrl = "https://api.siliconflow.cn/v1",
            model = "Qwen/Qwen3-8B",
        ),
        "custom" to PresetEntry(
            name = "自定义",
            baseUrl = "",
            model = "",
        ),
    )

    data class PresetEntry(
        val name: String,
        val baseUrl: String,
        val model: String,
    )

    /** 默认选中项。自定义为空，逼迫用户先配置再使用。 */
    const val DEFAULT_KEY: String = "custom"

    fun of(key: String): PresetEntry = PRESETS[key] ?: PRESETS.getValue(DEFAULT_KEY)

    /** 顺序列表，供设置页下拉展示（保持插入顺序）。 */
    fun ordered(): List<Pair<String, PresetEntry>> = PRESETS.entries.map { it.key to it.value }
}

/**
 * 向后兼容别名：方案文档 9.1 里写的是顶层 `PROVIDER_PRESETS`。
 * Kotlin 没有顶层常量字典的优雅写法，用 object 承载，此处提供只读代理。
 */
val PROVIDER_PRESETS: Map<String, ProviderPresets.PresetEntry> get() = ProviderPresets.PRESETS
