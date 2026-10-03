package com.healix.app.net

/**
 * Provider 抽象层（对齐 `功能补充与套壳选型.md` 9.1 与 Python 侧 `pipeline/provider.py`）。
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
}

/**
 * 设置页预设表。
 *
 * ⚠️ **baseUrl 与 model 一律是占位符，禁止凭记忆硬编码真实值（C1 反幻觉）。**
 * 核实时机：实现设置页 "服务商" 下拉的填充逻辑之前，
 * 逐条打开官方文档/控制台确认后替换，并把日期写进 docs/待核实清单.md。
 */
object ProviderPresets {

    /** 预设 key → (展示名, baseUrl 占位, model 占位) */
    val PRESETS: Map<String, PresetEntry> = linkedMapOf(
        "zhipu" to PresetEntry(
            name = "智谱 GLM",
            baseUrl = "[待核实: 智谱开放平台 > API 文档 > base_url]",
            model = "[待核实: 智谱开放平台 > 模型列表 > 当前可用 flash 模型名]",
        ),
        "deepseek" to PresetEntry(
            name = "DeepSeek",
            baseUrl = "[待核实: DeepSeek 开放平台 > API 文档 > base_url]",
            model = "[待核实: DeepSeek 开放平台 > 模型列表]",
        ),
        "openrouter" to PresetEntry(
            name = "OpenRouter",
            baseUrl = "[待核实: OpenRouter > Docs > API Reference > base URL]",
            model = "[待核实: OpenRouter > Models]",
        ),
        "siliconflow" to PresetEntry(
            name = "SiliconFlow",
            baseUrl = "[待核实: 硅基流动 > 文档 > API 参考]",
            model = "[待核实: 硅基流动 > 模型广场]",
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
