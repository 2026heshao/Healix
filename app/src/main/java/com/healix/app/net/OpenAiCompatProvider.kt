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
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 流式（2026-10-08）
 * ══════════════════════════════════════════════════════════════════════════
 * [chatStream] 与 [chat] 共用 [chatInternal] —— 重试 / 超时 / 错误分类**同源**，
 * 只有两处差异：请求体多一个 `"stream": true`、响应走 [readEventStream] 逐块解析。
 * 端点若忽略 `stream` 直接回整包 JSON，[singleCall] 会按 `Content-Type` 判定并
 * 自动退回整包解析（兼容兜底，不是错误）。
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

    override suspend fun chat(request: ChatRequest): ChatResult = chatInternal(request, null)

    override suspend fun chatStream(request: ChatRequest, sink: StreamSink): ChatResult =
        chatInternal(request, sink)

    /**
     * [chat] 与 [chatStream] 的共用实现 —— 两条路径的重试 / 超时 / 错误分类必须
     * **逐字同源**，各写一份必然漂移。`sink == null` 即非流式。
     *
     * N-8 模型回落（2026-10-08）：主模型被端点以 400「Model Not Exist」拒绝时
     * （确定性失败，重试同参数毫无意义），若 [ProviderConfig.effectiveFallbackModel]
     * 存在，则换兜底模型**整链重跑一次**（完整退避重试链）。
     *
     * 埋点口径（刻意）：`llm_calls.model` 全仓语义是「配置请求的模型名」，
     * 回落属传输层细节、不改变该口径 —— 全部 `recordCall` 调用点继续读
     * `config.model`，net 层不向埋点回传实际命中的模型。
     */
    private suspend fun chatInternal(request: ChatRequest, sink: StreamSink?): ChatResult {
        val first = chatWithModel(request, sink, config.model)
        if (first is ChatResult.Ok) return first
        val primaryErr = first as ChatResult.Err
        val fallback = config.effectiveFallbackModel
        if (fallback.isEmpty() || !isModelNotExists(primaryErr)) return primaryErr

        val second = chatWithModel(request, sink, fallback)
        return when (second) {
            is ChatResult.Ok -> second
            is ChatResult.Err -> second.copy(attempts = second.attempts + primaryErr.attempts)
        }
    }

    /**
     * 判定一个错误是否为「模型不被端点支持」：HTTP 400 且报错正文指向 model
     * （DeepSeek 官方文案 `Model Not Exist`；各家兼容网关常见
     * `invalid model` / `unknown model` / `model not found` 等写法，宽松命中）。
     * 刻意**只认 400**：401/403 是鉴权问题，404 是路径问题，换模型都救不了。
     */
    private fun isModelNotExists(err: ChatResult.Err): Boolean {
        if (err.httpCode != 400) return false
        val msg = err.message.lowercase()
        if (!msg.contains("model")) return false
        return msg.contains("not exist") || msg.contains("does not exist") ||
            msg.contains("invalid") || msg.contains("unknown") || msg.contains("not found") ||
            msg.contains("unsupported") || msg.contains("不可用") || msg.contains("不存在")
    }

    /**
     * 用指定 [model] 跑完整调用链（退避重试 / 超时 / 错误分类）。
     * 原 [chatInternal] 的主体 —— 抽出模型参数以支持 N-8 回落重跑。
     */
    private suspend fun chatWithModel(
        request: ChatRequest,
        sink: StreamSink?,
        model: String,
    ): ChatResult {
        // 2026-10-09 Key 健壮性前置校验：非法字符（如粘贴而来的密码圆点 `•` U+2022、
        // 换行、空格）会让 OkHttp 在 `header("Authorization", …)` 处直接抛
        // IllegalArgumentException —— 真机实测把整个进程炸掉（崩溃报告
        // "Unexpected char 0x2022 at 7 in Authorization value"）。这种输入错误
        // 必须在进入重试链之前拦下，转成普通 AUTH 错误文案给 UI，绝不崩进程。
        val badChar = config.apiKey.firstOrNull { it.code == 0x2022 || it == '\n' || it == '\r' || it == ' ' }
        if (config.apiKey.isBlank() || badChar != null) {
            return ChatResult.Err(
                kind = ErrKind.AUTH,
                message = "API Key 无效（含非法字符或为空）——请到设置页重新粘贴",
                attempts = 0,
            )
        }
        val payload = buildPayload(request, stream = sink != null, model = model)
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

            val attempt0 = singleCall(body, request, attempt, sink)
            val result = attempt0.result
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

            // 流式：已经吐出过增量则不再重试 —— 重试会把同一段正文再吐一遍
            // （增量已交给 UI，拼不回干净状态）。失败原样上抛，由调用方走降级链。
            if (attempt0.emitted) return lastErr
        }

        return lastErr ?: ChatResult.Err(
            kind = ErrKind.NETWORK,
            message = "未知错误",
            attempts = request.maxRetries + 1,
        )
    }

    /**
     * 一次调用尝试的结果。
     *
     * [emitted] 只对流式有意义：本次尝试是否**已经向 sink 推出过增量**
     * —— 决定失败后能否重试（见 [chatInternal]）。
     */
    private data class Attempt(val result: ChatResult, val emitted: Boolean = false)

    /**
     * 单次尝试内「是否已向 sink 推出过增量」的**可变**标记。
     *
     * 为什么必须是独立对象、而不能只做 [Attempt] 的字段：增量已经推到 UI 之后
     * 才抛出的异常（移动网中途卡死 → `readUtf8Line()` 抛 `SocketTimeoutException` /
     * `IOException`）会让 [readEventStream] 根本没有机会构造 `Attempt` —— 标记只能
     * 由 [singleCall] 的 catch 块读取。若 catch 里默认按 `emitted = false` 处理，
     * [chatInternal] 就会把「半段正文已经可见」的失败当成**可重试**，
     * 重试时又往**同一个** sink 追加增量 → 用户看到正文重影（前半段出现两遍）。
     * 这个标记就是把「已吐出」这件事从异常里带出来。
     */
    private class EmitFlag {
        var value: Boolean = false
    }

    // ------------------------------------------------------------------
    // 单次调用
    // ------------------------------------------------------------------

    private suspend fun singleCall(
        body: okhttp3.RequestBody,
        request: ChatRequest,
        attempt: Int,
        sink: StreamSink?,
    ): Attempt = withContext(Dispatchers.IO) {
        // 跨 try/catch 存活：流中途抛异常时，catch 块靠它知道"已经吐过了"
        // （理由见 [EmitFlag]）。
        val emitted = EmitFlag()
        val httpRequest = Request.Builder()
            .url(config.endpoint())
            .post(body)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${config.apiKey}")
            // 流式必须显式声明接受 SSE：部分网关据它决定回流的形态。
            .header("Accept", if (sink != null) "text/event-stream" else "application/json")
            .build()

        // 显式超时：从 request.timeoutMs 派生。
        // - 非流式：callTimeout 兜底整体耗时（同改前）。
        // - 流式：callTimeout 放宽到 STREAM_CALL_TIMEOUT_MS —— 长流会被一次性请求的
        //   callTimeout 腰斩；但 connect/write/read 仍取 timeoutMs，其中流式下的
        //   readTimeout 是**块间空闲超时**（断流仍能在一个 timeoutMs 内被发现）。
        val callClient = client.newBuilder()
            .connectTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(
                if (sink != null) {
                    LlmProvider.STREAM_CALL_TIMEOUT_MS
                } else {
                    request.timeoutMs + CALL_TIMEOUT_GRACE_MS
                },
                TimeUnit.MILLISECONDS,
            )
            .build()

        try {
            callClient.newCall(httpRequest).execute().use { response ->
                if (sink != null && isEventStream(response)) {
                    readEventStream(response, sink, emitted)
                } else {
                    // 非流式，或端点忽略了 `stream:true` 直接回整包 JSON（兼容兜底）
                    Attempt(parseHttpResponse(response))
                }
            }
        } catch (e: SocketTimeoutException) {
            // 读/连接/写超时统一归 TIMEOUT（可重试）。
            // ⚠️ 带出 emitted：流式下 readTimeout 是**块间空闲超时**，长流卡住会走到
            //    这里 —— 此时正文可能已经吐了半段，必须让 chatInternal 放弃重试，
            //    否则重试会往同一个 sink 追加，用户看到正文重影。
            Attempt(
                ChatResult.Err(
                    kind = ErrKind.TIMEOUT,
                    message = "请求超时（${request.timeoutMs}ms）",
                    attempts = attempt + 1,
                ),
                emitted.value,
            )
        } catch (e: UnknownHostException) {
            Attempt(
                ChatResult.Err(
                    kind = ErrKind.NETWORK,
                    message = sanitize("域名解析失败: ${e.message ?: ""}"),
                    attempts = attempt + 1,
                ),
                emitted.value,
            )
        } catch (e: SSLException) {
            // 证书/DNS 劫持在国内网络常见，归 NETWORK 可重试
            Attempt(
                ChatResult.Err(
                    kind = ErrKind.NETWORK,
                    message = sanitize("TLS 握手失败: ${e.javaClass.simpleName}"),
                    attempts = attempt + 1,
                ),
                emitted.value,
            )
        } catch (e: IOException) {
            // 建流后中途断流（连接被对端掐断 / 分块读取失败）会走到这里
            Attempt(
                ChatResult.Err(
                    kind = ErrKind.NETWORK,
                    message = sanitize("网络错误: ${e.javaClass.simpleName}"),
                    attempts = attempt + 1,
                ),
                emitted.value,
            )
        } catch (e: Exception) {
            // 兜底：CancellationException 原样上抛（对齐协程取消语义），其余一律收敛为
            // NETWORK，绝不向上抛。⚠️ 必须显式重抛 CancellationException —— 否则协程
            // 被取消时会在此被吞成一次 NETWORK 错误（伪造网络失败 + 污染埋点）。
            // 注：kotlinx.coroutines.CancellationException 是 java.util.concurrent 的
            // typealias，**不是** IOException，不受上面 catch(IOException) 影响。
            if (e is CancellationException) throw e
            Attempt(
                ChatResult.Err(
                    kind = ErrKind.NETWORK,
                    message = sanitize("${e.javaClass.simpleName}: ${e.message ?: ""}"),
                    attempts = attempt + 1,
                ),
                emitted.value,
            )
        }
    }

    // ------------------------------------------------------------------
    // 流式（SSE）解析
    // ------------------------------------------------------------------

    /** 响应是不是 SSE（`Content-Type: text/event-stream`；容忍带 charset 参数）。 */
    private fun isEventStream(response: Response): Boolean =
        response.header("Content-Type")?.contains(EVENT_STREAM, ignoreCase = true) == true

    /**
     * 读 SSE 事件流，把正文增量推给 [sink]，最后拼出**完整** [ChatResult]。
     *
     * 协议要点（OpenAI 兼容端点通用）：
     * - 一行一个字段，形如 `data: {...}`；`: xxx` 是注释/心跳；`event:` / `id:` / `retry:`
     *   对本场景无意义，一律忽略；空行是事件分隔。
     * - `data: [DONE]` 表示流正常结束。
     * - 正文在 `choices[0].delta.content`（**首个块常为空串**，跳过即可）；
     *   `delta.reasoning_content`（思考型模型）**不推给 UI** —— 那是草稿不是答复。
     * - 工具调用按 `tool_calls[].index` **分片**下发：`id` / `function.name` 通常只在
     *   首片出现，`function.arguments` 是全片**拼接**（明文 JSON 片段，可能被切成
     *   任意位置，甚至切开一个中文字符的字节 —— 所以必须按块累积后再整体解析）。
     * - `usage` 若有则出现在靠后的块里（不同平台时机不同），取最后一次见到的。
     *
     * 鲁棒性取舍：**单个块的 JSON 坏掉只跳过该块**，不让整条流失败（网关偶发截断
     * 一个心跳块不该毁掉整段答复）。
     */
    private fun readEventStream(
        response: Response,
        sink: StreamSink,
        emitted: EmitFlag,
    ): Attempt {
        if (!response.isSuccessful) {
            // 建流阶段就失败：错误体是普通 JSON，复用既有映射
            return Attempt(parseHttpResponse(response))
        }

        val source = response.body?.source()
            ?: return Attempt(
                ChatResult.Err(
                    kind = ErrKind.PARSE,
                    message = "流式响应缺少 body",
                    httpCode = response.code,
                    attempts = 1,
                )
            )

        val content = StringBuilder()
        val toolAcc = LinkedHashMap<Int, ToolCallAcc>()
        var usage = Usage()

        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty() || line.startsWith(":")) continue
            if (!line.startsWith(DATA_PREFIX)) continue
            val payload = line.substring(DATA_PREFIX.length).trim()
            if (payload.isEmpty()) continue
            if (payload == SSE_DONE) break

            val obj = try {
                JSONObject(payload)
            } catch (e: JSONException) {
                continue // 单块坏 JSON：跳过本块，不毁整条流
            }

            // 业务错误码（部分平台在流里回 {"code":1305,...} 而非 HTTP 4xx/5xx）
            val bizCode = obj.opt("code")
            if (bizCode != null && !isSuccessBizCode(bizCode)) {
                val isRateLimit = bizCode.toString() == BIZ_CODE_RATE_LIMIT
                val msg = obj.optString("message").ifEmpty {
                    obj.optString("msg").ifEmpty { bizCode.toString() }
                }
                return Attempt(
                    ChatResult.Err(
                        kind = if (isRateLimit) ErrKind.RATE_LIMIT else ErrKind.HTTP,
                        message = sanitize(msg),
                        httpCode = response.code,
                        attempts = 1,
                    ),
                    emitted.value,
                )
            }

            obj.optJSONObject("usage")?.let { usage = parseUsageObject(it) }

            val delta = obj.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
                ?: continue

            // ⚠️ 不能写 `optString("content")`：键存在而值为 JSON null 时，Android 的
            //    JSONObject 会返回**字面串 "null"**（不是空串），会被当成正文推给 UI。
            //    取原生类型再判空是唯一安全写法（同 PlanChangeWriter.sanitizePatch 的教训）。
            val piece = delta.opt("content") as? String
            if (!piece.isNullOrEmpty()) {
                content.append(piece)
                emitted.value = true
                sink.onDelta(piece)
            }

            accumulateToolCalls(delta.optJSONArray("tool_calls"), toolAcc)
        }

        val toolCalls = toolAcc.entries
            .sortedBy { it.key }
            .map { (_, acc) ->
                ToolCall(
                    id = acc.id,
                    function = ToolFunction(name = acc.name, arguments = acc.args.toString()),
                )
            }

        if (content.isEmpty() && toolCalls.isEmpty()) {
            return Attempt(
                ChatResult.Err(
                    kind = ErrKind.PARSE,
                    message = "流式响应既无 content 也无 tool_calls",
                    httpCode = response.code,
                    attempts = 1,
                ),
                emitted.value,
            )
        }

        return Attempt(
            ChatResult.Ok(content = content.toString(), toolCalls = toolCalls, usage = usage),
            emitted.value,
        )
    }

    /** 流式工具调用的分片累加器（按 `index` 归并）。 */
    private class ToolCallAcc {
        var id: String = ""
        var name: String = ""
        val args: StringBuilder = StringBuilder()
    }

    /**
     * 把一块 `delta.tool_calls` 累加进 [acc]。
     *
     * 首片带 `id` / `function.name`，后续片只带 `function.arguments` 的增量；
     * 但协议没保证这一点（个别网关每片都重发 name），故一律「非空才覆盖 / 非空才追加」。
     */
    private fun accumulateToolCalls(
        array: JSONArray?,
        acc: MutableMap<Int, ToolCallAcc>,
    ) {
        if (array == null || array.length() == 0) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val index = item.optInt("index", i)
            val slot = acc.getOrPut(index) { ToolCallAcc() }

            (item.opt("id") as? String)?.takeIf { it.isNotEmpty() }?.let { slot.id = it }

            val fn = item.optJSONObject("function") ?: continue
            (fn.opt("name") as? String)?.takeIf { it.isNotEmpty() }?.let { slot.name = it }
            (fn.opt("arguments") as? String)?.takeIf { it.isNotEmpty() }?.let { slot.args.append(it) }
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

    private fun parseUsage(data: JSONObject): Usage =
        data.optJSONObject("usage")?.let { parseUsageObject(it) } ?: Usage()

    /** 把 `usage` 对象转成 [Usage]（非流式取顶层 `usage`，流式取最后一个带 usage 的块）。 */
    private fun parseUsageObject(usage: JSONObject): Usage = Usage(
        inputTokens = usage.optInt("prompt_tokens", 0),
        outputTokens = usage.optInt("completion_tokens", 0),
    )

    // ------------------------------------------------------------------
    // 请求体构造
    // ------------------------------------------------------------------

    /**
     * 构造请求体。
     *
     * [stream] = true 时追加 `"stream": true`（仅在 `stream` 为 true 时**才**出现该键
     * —— 非流式请求体保持与改前逐字节相同，避免给不支持该参数的端点添乱）。
     * [model] = 本次请求使用的模型名（N-8 回落时与 `config.model` 不同）。
     *
     * ⚠️ 刻意**不加** `stream_options: {"include_usage": true}`：该参数并非所有
     * OpenAI 兼容端点都认，加了会 400。`usage` 有则取（见 [readEventStream]），
     * 没有就让 token 计数为 0（不猜数）。
     */
    private fun buildPayload(request: ChatRequest, stream: Boolean, model: String): JSONObject {
        val root = JSONObject()
        root.put("model", model)

        val messagesArr = JSONArray()
        for (m in request.messages) {
            messagesArr.put(messageToWire(m))
        }
        root.put("messages", messagesArr)
        root.put("temperature", request.temperature)

        if (stream) root.put("stream", true)

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

        /** SSE 判定：`Content-Type` 里出现该 token 即视为事件流。 */
        private const val EVENT_STREAM = "text/event-stream"

        /** SSE 数据行前缀（协议规定冒号后可省一个空格，取值时统一 trim）。 */
        private const val DATA_PREFIX = "data:"

        /** SSE 流正常结束标记。 */
        private const val SSE_DONE = "[DONE]"

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
