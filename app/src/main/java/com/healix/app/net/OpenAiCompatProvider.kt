package com.healix.app.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * OpenAI 兼容端点客户端（唯一实现类）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么只写一个实现类
 * ══════════════════════════════════════════════════════════════════════════
 * 智谱、DeepSeek、月之暗面、OpenRouter、SiliconFlow 等主流平台都提供
 * **OpenAI 兼容端点**：请求体 `POST {baseUrl}/chat/completions`，Bearer 鉴权，
 * 响应体 `choices[0].message.content`。三者的差异只有 baseUrl / model / apiKey
 * 三个字符串 —— 而这三个恰好都进设置页。
 *
 * 为每家平台写一个 Provider 子类，是把"配置问题"当成"代码问题"，
 * 每加一家都要改代码 + 重新打包 + 重跑回归。写一个实现 + 一张预设表，
 * 加平台只需往设置页填三个字符串。
 *
 * 若将来遇到真正不兼容的平台（例如只支持自家协议），再在 [LlmProvider] 下
 * 加第二个实现类，接口已经预留好了。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 硬约束实现清单
 * ══════════════════════════════════════════════════════════════════════════
 * - 显式超时：connect/read/write 全部从 request.timeoutMs 派生，不用 OkHttp 默认值
 * - 退避重试：1.5 × 2^(n-1) 秒，可关指数退避，最多 maxRetries 次
 * - 不重试：400 / 401 / 403 / 404 / 422，以及 ErrKind.AUTH
 * - 429 → RATE_LIMIT（可重试）；5xx → HTTP（可重试）
 * - 200 里带业务错误码 `code`（1305 = 限流）也要识别
 * - **绝不抛异常**，一切失败返回 ChatResult.Err
 * - 错误信息脱敏（抹 sk-xxx / Bearer xxx / api_key: xxx）后截断 200 字
 */
class OpenAiCompatProvider(
    override val config: ProviderConfig,
    /** 允许注入共享的 OkHttpClient（连接池复用）。不传则内部新建。 */
    private val sharedClient: OkHttpClient? = null,
) : LlmProvider {

    /** 惰性建 client。OkHttpClient 创建开销大，必须复用。 */
    private val client: OkHttpClient by lazy {
        sharedClient ?: OkHttpClient.Builder()
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(DEFAULT_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DEFAULT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false) // 重试由本类统一控制，避免双重退避
            .build()
    }

    override suspend fun chat(request: ChatRequest): ChatResult {
        val payload = buildPayload(request)
        val body = try {
            payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        } catch (e: Exception) {
            return ChatResult.Err(
                kind = ErrKind.PARSE,
                message = sanitize("请求体序列化失败: ${e.javaClass.simpleName}"),
                attempts = 0,
            )
        }

        var lastErr: ChatResult.Err? = null

        // attempt 从 0 开始；attempt > 0 时先退避再发
        for (attempt in 0..request.maxRetries) {
            if (attempt > 0) {
                delay(backoffMillis(attempt, request))
            }

            val result = singleCall(body, request, attempt)
            if (result is ChatResult.Ok) {
                return result
            }
            val err = result as ChatResult.Err
            lastErr = err.copy(attempts = attempt + 1)

            // 终止类错误不重试：AUTH，或命中 FATAL_HTTP_CODES
            if (err.kind == ErrKind.AUTH) return lastErr
            if (err.httpCode != null && err.httpCode in FATAL_HTTP_CODES) return lastErr

            // 非可重试类型直接返回
            if (err.kind !in RETRYABLE_ERR_KINDS) return lastErr
        }

        return lastErr ?: ChatResult.Err(
            kind = ErrKind.NETWORK,
            message = "未知错误",
            attempts = request.maxRetries + 1,
        )
    }

    // ------------------------------------------------------------------
    // 单次调用
    // ------------------------------------------------------------------

    private suspend fun singleCall(
        body: okhttp3.RequestBody,
        request: ChatRequest,
        attempt: Int,
    ): ChatResult = withContext(Dispatchers.IO) {
        val httpRequest = Request.Builder()
            .url(config.endpoint())
            .post(body)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "application/json")
            .build()

        // 显式超时：从 request.timeoutMs 派生。callTimeout 兜底整体耗时。
        val callClient = client.newBuilder()
            .connectTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(request.timeoutMs + CALL_TIMEOUT_GRACE_MS, TimeUnit.MILLISECONDS)
            .build()

        try {
            callClient.newCall(httpRequest).execute().use { response ->
                parseHttpResponse(response)
            }
        } catch (e: SocketTimeoutException) {
            // 读/连接/写超时统一归 TIMEOUT（可重试）
            ChatResult.Err(
                kind = ErrKind.TIMEOUT,
                message = "请求超时（${request.timeoutMs}ms）",
                attempts = attempt + 1,
            )
        } catch (e: UnknownHostException) {
            ChatResult.Err(
                kind = ErrKind.NETWORK,
                message = sanitize("域名解析失败: ${e.message ?: ""}"),
                attempts = attempt + 1,
            )
        } catch (e: SSLException) {
            // 证书/DNS 劫持在国内网络常见，归 NETWORK 可重试
            ChatResult.Err(
                kind = ErrKind.NETWORK,
                message = sanitize("TLS 握手失败: ${e.javaClass.simpleName}"),
                attempts = attempt + 1,
            )
        } catch (e: IOException) {
            ChatResult.Err(
                kind = ErrKind.NETWORK,
                message = sanitize("网络错误: ${e.javaClass.simpleName}"),
                attempts = attempt + 1,
            )
        } catch (e: Exception) {
            // 兜底：CancellationException 原样上抛（对齐协程取消语义），其余一律收敛为
            // NETWORK，绝不向上抛。⚠️ 必须显式重抛 CancellationException —— 否则协程
            // 被取消时会在此被吞成一次 NETWORK 错误（伪造网络失败 + 污染埋点）。
            // 注：kotlinx.coroutines.CancellationException 是 java.util.concurrent 的
            // typealias，**不是** IOException，不受上面 catch(IOException) 影响。
            if (e is CancellationException) throw e
            ChatResult.Err(
                kind = ErrKind.NETWORK,
                message = sanitize("${e.javaClass.simpleName}: ${e.message ?: ""}"),
                attempts = attempt + 1,
            )
        }
    }

    /** 把 HTTP 响应转成 ChatResult。 */
    private fun parseHttpResponse(response: Response): ChatResult {
        val code = response.code
        val raw = try {
            response.body?.string().orEmpty()
        } catch (e: Exception) {
            ""
        }

        if (!response.isSuccessful) {
            val kind = when {
                code == 401 || code == 403 -> ErrKind.AUTH
                code == 429 -> ErrKind.RATE_LIMIT
                else -> ErrKind.HTTP
            }
            return ChatResult.Err(
                kind = kind,
                message = sanitize(raw).ifEmpty { "HTTP $code" },
                httpCode = code,
                attempts = 1,
            )
        }

        return parseSuccessBody(raw, code)
    }

    // ------------------------------------------------------------------
    // 响应解析
    // ------------------------------------------------------------------

    /**
     * 解析 2xx 响应体。
     *
     * 兼容三种真实形态：
     * 1. 标准：`{"choices":[{"message":{"content":"...","tool_calls":[...]}}],"usage":{...}}`
     * 2. 业务错误码：部分平台在 HTTP 200 里塞 `{"code":1305,"message":"..."}`
     * 3. 只有 tool_calls 没有 content：合法（模型决定调工具）
     */
    private fun parseSuccessBody(raw: String, httpCode: Int): ChatResult {
        val data: JSONObject = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return ChatResult.Err(
                kind = ErrKind.PARSE,
                message = sanitize(raw).ifEmpty { "响应不是合法 JSON" },
                httpCode = httpCode,
                attempts = 1,
            )
        }

        // 形态 2：业务错误码（智谱历史上用 code 字段，1305 = 限流）
        val bizCode = data.opt("code")
        if (bizCode != null && !isSuccessBizCode(bizCode)) {
            val isRateLimit = bizCode.toString() == BIZ_CODE_RATE_LIMIT
            val msg = data.optString("message").ifEmpty {
                data.optString("msg").ifEmpty { bizCode.toString() }
            }
            return ChatResult.Err(
                kind = if (isRateLimit) ErrKind.RATE_LIMIT else ErrKind.HTTP,
                message = sanitize(msg),
                httpCode = httpCode,
                attempts = 1,
            )
        }

        val choices = data.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            return ChatResult.Err(
                kind = ErrKind.PARSE,
                message = "响应缺少 choices",
                httpCode = httpCode,
                attempts = 1,
            )
        }

        val firstChoice = choices.optJSONObject(0)
        val message = firstChoice?.optJSONObject("message")

        val content = message?.optString("content").orEmpty()

        val toolCalls = parseToolCalls(message?.optJSONArray("tool_calls"))

        // 有 tool_calls 但 content 为空 = 合法（模型决定调工具）
        if (content.isEmpty() && toolCalls.isEmpty()) {
            // 少数平台把内容放在 reasoning_content（思考型模型）
            val reasoning = message?.optString("reasoning_content").orEmpty()
            if (reasoning.isEmpty()) {
                return ChatResult.Err(
                    kind = ErrKind.PARSE,
                    message = "响应既无 content 也无 tool_calls",
                    httpCode = httpCode,
                    attempts = 1,
                )
            }
            return ChatResult.Ok(content = reasoning, toolCalls = emptyList(), usage = parseUsage(data))
        }

        return ChatResult.Ok(
            content = content,
            toolCalls = toolCalls,
            usage = parseUsage(data),
        )
    }

    /** 业务成功码白名单：null / 0 / "0" / 200 视为成功。 */
    private fun isSuccessBizCode(code: Any): Boolean {
        val s = code.toString()
        return s == "0" || s == "200"
    }

    private fun parseToolCalls(array: JSONArray?): List<ToolCall> {
        if (array == null || array.length() == 0) return emptyList()
        val result = ArrayList<ToolCall>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val fn = item.optJSONObject("function") ?: continue
            result.add(
                ToolCall(
                    id = item.optString("id"),
                    type = item.optString("type").ifEmpty { "function" },
                    function = ToolFunction(
                        name = fn.optString("name"),
                        arguments = fn.optString("arguments"),
                    ),
                )
            )
        }
        return result
    }

    private fun parseUsage(data: JSONObject): Usage {
        val usage = data.optJSONObject("usage") ?: return Usage()
        return Usage(
            inputTokens = usage.optInt("prompt_tokens", 0),
            outputTokens = usage.optInt("completion_tokens", 0),
        )
    }

    // ------------------------------------------------------------------
    // 请求体构造
    // ------------------------------------------------------------------

    private fun buildPayload(request: ChatRequest): JSONObject {
        val root = JSONObject()
        root.put("model", config.model)

        val messagesArr = JSONArray()
        for (m in request.messages) {
            messagesArr.put(messageToWire(m))
        }
        root.put("messages", messagesArr)
        root.put("temperature", request.temperature)

        val tools = request.tools
        if (tools != null && tools.isNotEmpty()) {
            val toolsArr = JSONArray()
            for (t in tools) {
                toolsArr.put(toolDefToWire(t))
            }
            root.put("tools", toolsArr)
            root.put("tool_choice", "auto")
        }

        return root
    }

    /**
     * 消息 → wire 格式。
     *
     * 关键点：`tool_calls[].function.arguments` 必须是**字符串**（模型产生的 JSON 文本），
     * 不能是对象，否则部分平台直接 400。
     */
    private fun messageToWire(m: ChatMessage): JSONObject {
        val obj = JSONObject()
        obj.put("role", m.role)
        obj.put("content", m.content)

        val calls = m.toolCalls
        if (!calls.isNullOrEmpty()) {
            val arr = JSONArray()
            for (c in calls) {
                val item = JSONObject()
                item.put("id", c.id)
                item.put("type", c.type)
                val fn = JSONObject()
                fn.put("name", c.function.name)
                fn.put("arguments", c.function.arguments)
                item.put("function", fn)
                arr.put(item)
            }
            obj.put("tool_calls", arr)
        }

        if (!m.toolCallId.isNullOrEmpty()) {
            obj.put("tool_call_id", m.toolCallId)
        }

        return obj
    }

    private fun toolDefToWire(t: ToolDef): JSONObject {
        val obj = JSONObject()
        obj.put("type", t.type)
        val fn = JSONObject()
        fn.put("name", t.function.name)
        fn.put("description", t.function.description)
        fn.put("parameters", JSONObject(t.function.parameters))
        obj.put("function", fn)
        return obj
    }

    // ------------------------------------------------------------------
    // 退避
    // ------------------------------------------------------------------

    /**
     * 退避：1.5 × 2^(n-1)，指数退避可关（不同平台限流策略不同，见 9.1）。
     * 返回毫秒。attempt 从 1 开始计（第 1 次重试）。
     */
    private fun backoffMillis(attempt: Int, request: ChatRequest): Long {
        val seconds = if (!request.exponentialBackoff) {
            request.retryBaseSeconds
        } else {
            request.retryBaseSeconds * Math.pow(2.0, (attempt - 1).toDouble())
        }
        // 上限保护：避免 maxRetries 配得很大时等待到天荒地老（最坏 60s 封顶）
        val capped = seconds.coerceAtMost(MAX_BACKOFF_SECONDS)
        return (capped * 1000).toLong()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 默认超时（request 未覆盖时用于 client 模板） */
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
        private const val DEFAULT_WRITE_TIMEOUT_MS = 15_000L
        private const val DEFAULT_READ_TIMEOUT_MS = 30_000L

        /** callTimeout 在 request.timeoutMs 之上加的余量（毫秒），用于覆盖连接+读写的总和 */
        private const val CALL_TIMEOUT_GRACE_MS = 5_000L

        /** 单次退避上限（秒）。5 次指数退避正常序列为 1.5/3/6/12/24，不会触顶 */
        private const val MAX_BACKOFF_SECONDS = 60.0

        /** 业务错误码：限流 */
        private const val BIZ_CODE_RATE_LIMIT = "1305"

        /** 错误信息截断长度（与 Python 侧 `_sanitize` 的 [:200] 对齐） */
        private const val ERROR_HEAD_MAX = 200

        /**
         * 脱敏。C6 硬约束：绝不把 key 写进日志 / 埋点 / 错误信息。
         *
         * 保守做法 —— 任何形如 `sk-xxx` / `Bearer xxx` / `api_key: xxx` 的片段一律抹掉，
         * 并把结果截断到 200 字。与 Python 侧 `OpenAiCompatProvider._sanitize` 行为一致。
         *
         * 注意：正则只做抹除，不做匹配失败即原样返回之外的任何处理。
         * 本项目**不存在**任何打印 key 的代码路径 —— 本函数是所有错误出口的最后一道闸。
         */
        fun sanitize(text: String): String {
            if (text.isEmpty()) return ""
            var out = text
            out = out.replace(SK_PATTERN, "sk-***")
            out = out.replace(API_KEY_PATTERN, "$1***")
            out = out.replace(BEARER_PATTERN, "$1***")
            return if (out.length > ERROR_HEAD_MAX) out.substring(0, ERROR_HEAD_MAX) else out
        }

        private val SK_PATTERN = Regex("sk-[A-Za-z0-9_\\-]{8,}")
        private val API_KEY_PATTERN =
            Regex("(?i)(api[_\\-]?key\"?\\s*[:=]\\s*\"?)[^\"\\s,}]{8,}")
        private val BEARER_PATTERN =
            Regex("(?i)(authorization\"?\\s*[:=]\\s*\"?bearer\\s+)[^\"\\s,}]{8,}")
    }
}
