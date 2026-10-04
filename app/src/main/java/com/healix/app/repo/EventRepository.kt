package com.healix.app.repo

import android.content.Context
import com.healix.app.HealixApp
import com.healix.app.db.EventEntity
import com.healix.app.db.SettingsKeys
import com.healix.app.security.SecretStore
import com.healix.app.net.ChatRequest
import com.healix.app.net.ChatResult
import com.healix.app.net.ErrKind
import com.healix.app.net.LlmProvider
import com.healix.app.net.NetworkStatus
import com.healix.app.net.OpenAiCompatProvider
import com.healix.app.net.ProviderConfig
import com.healix.app.parse.PROMPT_VER
import com.healix.app.parse.ParsedEvent
import com.healix.app.parse.buildExtractMessages
import com.healix.app.parse.dayKeyOf
import com.healix.app.parse.dayStartHourOf
import com.healix.app.parse.extractEvents
import com.healix.app.parse.normalizeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

/**
 * 事件仓库：串联 net → parse → db。
 *
 * 与 Python 侧 `pipeline/run_regression.py` 的 `process_one` **逐行对应**：
 * ```
 * 1. 生成 clientEventId = UUID
 * 2. 立即以 pending 入库（0ms 可见，离线不丢）—— raw_text 原文必落库
 * 3. 调 provider
 * 4. 成功 → extractEvents → 第一条 fillParsed 覆盖 pending 行、其余 insertIgnoreAll
 * 5. 失败 → markFailed
 * 6. 无论成败都写 LlmCallEntity 埋点
 * ```
 *
 * 关键设计：**先入库再调网**。用户输入瞬间就有一条可见记录（parse_status=pending），
 * 断网/杀进程都不丢原文。AI 回来只是"回填"这一行。
 */
class EventRepository(private val context: Context) {

    private val db = HealixApp.from(context).database
    private val eventDao = db.eventDao()
    private val llmCallDao = db.llmCallDao()
    private val settingsDao = db.settingsDao()
    private val secretStore: SecretStore? = HealixApp.from(context).secretStore

    /**
     * Provider 工厂。
     *
     * 当前只有一个 OpenAI 兼容实现（覆盖智谱/DeepSeek/OpenRouter/SiliconFlow），
     * 所以工厂只是「用 config 造一个 OpenAiCompatProvider」。
     * 之所以仍抽出这一层：调用点只写 `providerFactory.create(config)`，
     * 将来接入非 OpenAI 兼容协议的厂商时，只需改工厂内部，调用点无感。
     *
     * 共享同一个 OkHttpClient（连接池复用）—— 每次新建 client 会浪费连接池
     * 并泄漏线程，是 OkHttp 的经典误用。
     */
    private val sharedHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val providerFactory: ProviderFactory = ProviderFactory(sharedHttpClient)

    /** 极简工厂。见 providerFactory 的注释说明它为何存在。 */
    class ProviderFactory(private val client: okhttp3.OkHttpClient?) {
        fun create(config: ProviderConfig): LlmProvider = OpenAiCompatProvider(config, client)
    }

    companion object {
        /**
         * settings 表的键名（非敏感项）。apiKey **不在**这里（C6）。
         *
         * ⚠️ 2026-10-03 修复：这里过去用 `provider_base_url` / `provider_model` /
         * `retry_max_retries`，与设置页写入的 `base_url` / `model` / `retry_max`
         * **对不上** —— 导致 `loadProviderConfig()` 永远返回 null，
         * 「记一笔」一次请求都发不出去（表现为"配置无法生效"）。
         *
         * 现在一律引用 [SettingsKeys]，与写端共用同一份常量。
         */
        const val KEY_BASE_URL = SettingsKeys.BASE_URL
        const val KEY_MODEL = SettingsKeys.MODEL
        const val KEY_PROVIDER_NAME = SettingsKeys.PROVIDER
        const val KEY_MAX_RETRIES = SettingsKeys.RETRY
        const val KEY_RETRY_BASE = SettingsKeys.RETRY_DELAY
        const val KEY_EXP_BACKOFF = SettingsKeys.RETRY_EXP_BACKOFF
        const val KEY_DAY_START_HOUR = SettingsKeys.DAY_START

        /** 抽取链默认重试参数（对齐 contract.md 第二节） */
        const val DEFAULT_MAX_RETRIES = 5
        const val DEFAULT_RETRY_BASE_SECONDS = 1.5
        const val DEFAULT_EXTRACT_TIMEOUT_MS = 15_000L

        /** 埋点 status 取值（对齐 LlmCallEntity 注释与 Python 侧） */
        const val STATUS_OK = "ok"
        const val STATUS_RETRY_EXHAUSTED = "retry_exhausted"
        const val STATUS_SCHEMA_INVALID = "schema_invalid"
        const val STATUS_HTTP_ERROR = "http_error"
        const val STATUS_TIMEOUT = "timeout"

        /** parse_status 三态 */
        const val PARSE_PENDING = "pending"
        const val PARSE_DONE = "done"
        const val PARSE_FAILED = "failed"
    }

    /**
     * 提交一条原始输入，走完整抽取链。
     *
     * @param rawText 用户口语原文
     * @param source  app | notification | preset | ai_suggestion
     * @param clientEventId 可选，通知栏重投时复用同一个 id 实现幂等
     * @param ts 事件时间戳，默认 now
     * @return [SubmitResult]
     *
     * **本方法不抛异常** —— provider 层已保证返回结构化 Err，
     * 这里再包一层 try/catch 兜住 DB 层异常，保证调用方（Service/Activity）永远拿到结果。
     */
    suspend fun submit(
        rawText: String,
        source: String = SOURCE_APP,
        clientEventId: String? = null,
        ts: Long = System.currentTimeMillis(),
    ): SubmitResult = withContext(Dispatchers.IO) {
        val cid = clientEventId ?: UUID.randomUUID().toString()
        val dayStartHour = loadDayStartHour()

        // ── 第 1 步：先落 pending（原文永不丢）────────────────────────
        val placeholder = normalizeEvent(null) // 全部兜底值：type=other, kcal=0 ...
        val pendingEntity = EventEntity(
            clientEventId = cid,
            ts = ts,
            dayKey = dayKeyOf(ts, dayStartHour),
            rawText = rawText,
            type = placeholder.type,
            timeHint = placeholder.timeHint,
            foods = placeholder.foodsJson(),
            exercise = placeholder.exercise,
            amount = placeholder.amount,
            kcal = placeholder.kcal,
            symptom = placeholder.symptom,
            weightKg = placeholder.weightKg,
            sleepH = placeholder.sleepH,
            source = source,
            parseStatus = PARSE_PENDING,
            retryCount = 0,
            lastError = null,
            origin = ORIGIN_USER,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )

        try {
            eventDao.insertIgnore(pendingEntity)
        } catch (e: Exception) {
            // DB 都写不进去（磁盘满 / 文件损坏），无法继续
            return@withContext SubmitResult(
                ok = false,
                clientEventId = cid,
                error = "db_insert_failed: ${e.javaClass.simpleName}",
                extraCount = 0,
                latencyMs = 0,
            )
        }

        // 通知栏路径跑在 BroadcastReceiver 的 goAsync() 里，有硬时限预算（前台≈10s / 后台≈60s）。
        // 离线时不把 15s×(N+1) 的退避链白跑完 —— 直接置 failed，交由 retryFailedPending() 补抽。
        // ⚠️ 只对通知栏来源生效：主输入路径「先落库再判网络」是刻意取舍（见 MainViewModel.submit）。
        if (source == SOURCE_NOTIFICATION && !NetworkStatus.isOnline(context)) {
            eventDao.markFailed(cid, "offline: 离线，待联网后重试", System.currentTimeMillis())
            return@withContext SubmitResult(
                ok = false,
                clientEventId = cid,
                error = "offline",
                extraCount = 0,
                latencyMs = 0,
            )
        }

        // ── 第 2 步：取 provider 配置 ─────────────────────────────────
        val config = loadProviderConfig()
        if (config == null || !config.isUsable()) {
            // 未配置 provider：原文已入库，标记 failed 并给出明确原因。
            // 这是**预期内的降级路径**，不是异常 —— 用户还没填 key 而已。
            eventDao.markFailed(cid, "provider_not_configured: 未配置模型服务", System.currentTimeMillis())
            return@withContext SubmitResult(
                ok = false,
                clientEventId = cid,
                error = "provider_not_configured",
                extraCount = 0,
                latencyMs = 0,
                needsConfiguration = true,
            )
        }

        // ── 第 3 步：调云端（含退避重试）──────────────────────────────
        val provider = providerFactory.create(config)
        val request = ChatRequest(
            messages = buildExtractMessages(rawText),
            temperature = 0.3,
            timeoutMs = DEFAULT_EXTRACT_TIMEOUT_MS,
            // 通知栏路径跑在 goAsync() 的硬时限预算内：不重试（0），一次失败即交 retryFailedPending 补抽。
            maxRetries = if (source == SOURCE_NOTIFICATION) 0 else loadMaxRetries(),
            retryBaseSeconds = loadRetryBaseSeconds(),
            exponentialBackoff = loadExponentialBackoff(),
        )

        val startedAt = System.currentTimeMillis()
        val result = provider.chat(request)
        val latencyMs = System.currentTimeMillis() - startedAt

        // ── 第 4 步：失败分类处理 ─────────────────────────────────────
        if (result is ChatResult.Err) {
            val reason = "${result.kind.name.lowercase()}: ${result.message}"
            eventDao.markFailed(cid, reason.take(200), System.currentTimeMillis())

            recordCall(
                purpose = PURPOSE_EXTRACT,
                eventId = null,
                model = config.model,
                attempts = if (result.attempts > 0) result.attempts else request.maxRetries + 1,
                latencyMs = latencyMs,
                status = when (result.kind) {
                    com.healix.app.net.ErrKind.AUTH -> STATUS_HTTP_ERROR
                    com.healix.app.net.ErrKind.TIMEOUT -> STATUS_TIMEOUT
                    else -> STATUS_RETRY_EXHAUSTED
                },
                httpCode = result.httpCode,
                inputTokens = null,
                outputTokens = null,
                errorHead = result.message,
            )

            return@withContext SubmitResult(
                ok = false,
                clientEventId = cid,
                error = reason,
                extraCount = 0,
                latencyMs = latencyMs,
                attempts = result.attempts,
            )
        }

        val ok = result as ChatResult.Ok

        // ── 第 5 步：后处理 ──────────────────────────────────────────
        val events = extractEvents(ok.content)
        if (events.isEmpty()) {
            eventDao.markFailed(cid, "schema_invalid: 无法从响应中解析出任何事件", System.currentTimeMillis())
            recordCall(
                purpose = PURPOSE_EXTRACT,
                eventId = null,
                model = config.model,
                attempts = 1,
                latencyMs = latencyMs,
                status = STATUS_SCHEMA_INVALID,
                httpCode = 200,
                inputTokens = ok.usage.inputTokens,
                outputTokens = ok.usage.outputTokens,
                errorHead = ok.content.take(200),
            )
            // 原文仍在库里（pending → failed），数据不丢
            return@withContext SubmitResult(
                ok = false,
                clientEventId = cid,
                error = STATUS_SCHEMA_INVALID,
                extraCount = 0,
                latencyMs = latencyMs,
                attempts = 1,
            )
        }

        // ── 第 6 步：入库。第一条复用 pending 行（覆盖），其余新增 ──────
        try {
            val first = events[0]
            eventDao.fillParsed(
                clientEventId = cid,
                type = first.type,
                timeHint = first.timeHint,
                foods = first.foodsJson(),
                exercise = first.exercise,
                amount = first.amount,
                kcal = first.kcal,
                symptom = first.symptom,
                weightKg = first.weightKg,
                sleepH = first.sleepH,
                parseStatus = PARSE_DONE,
                lastError = null,
                updatedAt = System.currentTimeMillis(),
            )

            val extras = events.drop(1).map { e ->
                e.toEntity(
                    clientEventId = UUID.randomUUID().toString(),
                    ts = ts,
                    rawText = rawText,
                    source = source,
                    dayStartHour = dayStartHour,
                )
            }
            if (extras.isNotEmpty()) {
                eventDao.insertIgnoreAll(extras)
            }

            recordCall(
                purpose = PURPOSE_EXTRACT,
                eventId = null,
                model = config.model,
                attempts = 1,
                latencyMs = latencyMs,
                status = STATUS_OK,
                httpCode = 200,
                inputTokens = ok.usage.inputTokens,
                outputTokens = ok.usage.outputTokens,
                errorHead = null,
            )

            // 小工具推送：抽取结果可能改变本周运动计数（app-pushes-updates 模型）
            com.healix.app.widget.HealixWidgetProvider.push(context)

            SubmitResult(
                ok = true,
                clientEventId = cid,
                error = null,
                extraCount = extras.size,
                latencyMs = latencyMs,
                attempts = 1,
                firstEvent = first,
            )
        } catch (e: Exception) {
            eventDao.markFailed(cid, "db_update_failed: ${e.javaClass.simpleName}", System.currentTimeMillis())
            SubmitResult(
                ok = false,
                clientEventId = cid,
                error = "db_update_failed",
                extraCount = 0,
                latencyMs = latencyMs,
                attempts = 1,
            )
        }
    }

    /**
     * 撤销一笔：软删除（不物理删除，30 天后由清理任务物理清除）。
     *
     * 用 clientEventId 定位而不是 rowid —— 通知栏 UndoReceiver 手里只有这个 id。
     */
    suspend fun undo(clientEventId: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val entity = eventDao.findByClientId(clientEventId) ?: return@withContext false
            val now = System.currentTimeMillis()
            eventDao.softDelete(entity.id, now)
            // 软删会改变本周运动计数（countByTypeInRange 排除 deleted_at）→ 推小工具
            com.healix.app.widget.HealixWidgetProvider.push(context)

            // 若该条是多事件拆分出来的，把同批（同 raw_text 同秒）的兄弟条也删掉
            // 注意：只删同 raw_text 且未删除的，避免误伤用户真实的重复记录
            // 保守起见这里只删主条 —— 撤销语义为"撤销这一笔"，多事件属于同一次输入，
            // 但兄弟条用的是各自独立的 clientEventId，无法从主条反查，
            // 因此 undo 只保证主条被删，兄弟条需用户在列表中逐条删除。
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 取单条（供 UI 确认页 / 调试页用）。 */
    suspend fun find(clientEventId: String): EventEntity? = withContext(Dispatchers.IO) {
        try {
            eventDao.findByClientId(clientEventId)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 软删除恢复（规范 11.3 左滑删除的「撤销」，V3 断言）：
     * 清掉 deleted_at，记录按原 ts 回插到列表原位。
     */
    suspend fun restore(clientEventId: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val entity = eventDao.findByClientId(clientEventId) ?: return@withContext false
            if (entity.deletedAt == null) return@withContext true // 未删除，幂等
            eventDao.restore(entity.id, System.currentTimeMillis())
            // 恢复同理 → 推小工具
            com.healix.app.widget.HealixWidgetProvider.push(context)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 用户在 ConfirmSheet 里手工改过之后保存。
     *
     * 与 AI 回填的区别：`origin` 保持不变（仍可追溯是 AI 抽的还是用户改的），
     * 但 `parse_status` 强制置 done —— 用户确认过的记录绝不能再显示"识别中"。
     *
     * **不调 AI**：这是纯本地写入（也保证断网可用）。
     */
    suspend fun applyUserEdit(
        clientEventId: String,
        type: String,
        timeHint: String,
        foods: String,
        exercise: String,
        amount: String,
        kcal: Int,
        symptom: String,
        weightKg: Double,
        sleepH: Double,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val existing = eventDao.findByClientId(clientEventId) ?: return@withContext false

            // 用户在编辑框里填的是"牛肉面、鸡蛋"这类顿号串 —— 走同一套清洗转成 JSON 数组，
            // 保证与 AI 抽取的存储形态一致（列表渲染 / 导出都依赖这个约定）
            val foodsJson = toFoodsJson(foods)

            eventDao.fillParsed(
                clientEventId = clientEventId,
                // TYPE_VALID 是 Set，没有 getOrDefault（那是 Map 的方法）。
                // 用 if/else 显式表达「非法 type 统一回落 other」。
                type = if (type in TYPE_VALID) type else "other",
                timeHint = timeHint,
                foods = foodsJson,
                exercise = exercise,
                amount = amount,
                kcal = if (kcal < 0) 0 else kcal,
                symptom = symptom,
                weightKg = if (weightKg < 0) 0.0 else weightKg,
                sleepH = if (sleepH < 0) 0.0 else sleepH,
                parseStatus = PARSE_DONE,
                lastError = null,
                updatedAt = System.currentTimeMillis(),
            )
            // 用户改 type 可能改成 exercise → 推小工具
            com.healix.app.widget.HealixWidgetProvider.push(context)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 顿号/逗号串 → JSON 数组。与 Python 侧 to_str_list 的行为对齐。 */
    private fun toFoodsJson(raw: String): String {
        val items = raw.split("、", ",", "，", ";", "；", "/", "|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        return arr.toString()
    }

    private val TYPE_VALID = setOf("meal", "exercise", "body", "sleep", "illness", "other")

    /**
     * 重试一条 pending / failed 记录。
     *
     * 语义：**沿用原 clientEventId** 重新走一次抽取链 —— 幂等键保证不会新增行，
     * 成功时覆盖原行（功能补充 1.3）。这是"重试 5 次仍失败后用户手动点重试"的入口
     * （功能补充 1.1 第 4 步）。
     *
     * 与 [submit] 的区别：submit 负责"先落 pending 再抽取"，retry 时行已存在，
     * 直接抽 + 回填即可。若记录已被删除则返回失败结果，不复活。
     */
    suspend fun retry(entity: EventEntity): SubmitResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()

        // 行已被软删除 → 不复活
        if (entity.deletedAt != null) {
            return@withContext SubmitResult(
                ok = false,
                clientEventId = entity.clientEventId,
                error = "event_deleted",
                extraCount = 0,
                latencyMs = 0L,
            )
        }

        val config = loadProviderConfig()
        if (config == null) {
            return@withContext SubmitResult(
                ok = false,
                clientEventId = entity.clientEventId,
                error = "no_provider_config",
                extraCount = 0,
                latencyMs = 0L,
                needsConfiguration = true,
            )
        }

        val provider = providerFactory.create(config)
        val request = ChatRequest(messages = buildExtractMessages(entity.rawText))

        // 重试重新计数：把 attempts 归位，让 llm_calls 的 attempts 反映本次尝试次数
        eventDao.fillParsed(
            clientEventId = entity.clientEventId,
            type = entity.type,
            timeHint = entity.timeHint,
            foods = entity.foods,
            exercise = entity.exercise,
            amount = entity.amount,
            kcal = entity.kcal,
            symptom = entity.symptom,
            weightKg = entity.weightKg,
            sleepH = entity.sleepH,
            parseStatus = PARSE_PENDING,
            lastError = null,
            updatedAt = System.currentTimeMillis(),
        )

        val result = provider.chat(request)
        val latencyMs = System.currentTimeMillis() - started

        when (result) {
            is ChatResult.Err -> {
                eventDao.markFailed(entity.clientEventId, "${result.kind.name.lowercase()}: ${result.message}", System.currentTimeMillis())
                recordCall(
                    purpose = PURPOSE_EXTRACT,
                    eventId = entity.id,
                    model = config.model,
                    attempts = if (result.attempts > 0) result.attempts else 1,
                    latencyMs = latencyMs,
                    status = if (result.kind == ErrKind.AUTH) STATUS_HTTP_ERROR else STATUS_RETRY_EXHAUSTED,
                    httpCode = result.httpCode,
                    errorHead = result.message,
                )
                SubmitResult(
                    ok = false,
                    clientEventId = entity.clientEventId,
                    error = "${result.kind.name.lowercase()}: ${result.message}",
                    extraCount = 0,
                    latencyMs = latencyMs,
                    attempts = result.attempts,
                )
            }

            is ChatResult.Ok -> {
                val parsed = extractEvents(result.content)
                if (parsed.isEmpty()) {
                    eventDao.markFailed(entity.clientEventId, "schema_invalid: 无法解析出事件", System.currentTimeMillis())
                    recordCall(
                        purpose = PURPOSE_EXTRACT,
                        eventId = entity.id,
                        model = config.model,
                        attempts = 1,
                        latencyMs = latencyMs,
                        status = STATUS_SCHEMA_INVALID,
                        httpCode = 200,
                        inputTokens = result.usage.inputTokens,
                        outputTokens = result.usage.outputTokens,
                        errorHead = result.content.take(200),
                    )
                    return@withContext SubmitResult(
                        ok = false,
                        clientEventId = entity.clientEventId,
                        error = "schema_invalid",
                        extraCount = 0,
                        latencyMs = latencyMs,
                    )
                }

                val first = parsed.first()
                eventDao.fillParsed(
                    clientEventId = entity.clientEventId,
                    type = first.type,
                    timeHint = first.timeHint,
                    foods = first.foodsJson(),
                    exercise = first.exercise,
                    amount = first.amount,
                    kcal = first.kcal,
                    symptom = first.symptom,
                    weightKg = first.weightKg,
                    sleepH = first.sleepH,
                    parseStatus = PARSE_DONE,
                    lastError = null,
                    updatedAt = System.currentTimeMillis(),
                )

                // ⚠️ toEntity 需要 dayStartHour 才能算对 day_key（4:00 日界线）。
                //    这里必须显式传入，否则多事件拆分出的副事件会落到错误的「天」，
                //    导致当日汇总对不上。
                val extras = parsed.drop(1).map {
                    it.toEntity(
                        clientEventId = UUID.randomUUID().toString(),
                        ts = entity.ts,
                        rawText = entity.rawText,
                        source = entity.source,
                        dayStartHour = loadDayStartHour(),
                    )
                }
                if (extras.isNotEmpty()) eventDao.insertIgnoreAll(extras)

                recordCall(
                    purpose = PURPOSE_EXTRACT,
                    eventId = entity.id,
                    model = config.model,
                    attempts = 1,
                    latencyMs = latencyMs,
                    status = STATUS_OK,
                    httpCode = 200,
                    inputTokens = result.usage.inputTokens,
                    outputTokens = result.usage.outputTokens,
                )

                SubmitResult(
                    ok = true,
                    clientEventId = entity.clientEventId,
                    error = null,
                    extraCount = extras.size,
                    latencyMs = latencyMs,
                    attempts = 1,
                    firstEvent = first,
                )
            }
        }
    }

    /**
     * 今日是否已有记录（通知栏副标题"被动监督"用）。
     *
     * 用 listByDay().isNotEmpty() 而非新增 DAO 计数方法 —— 避免改动已定稿的 DB 层。
     * 单日记录量在几十条量级，全量取回的代价可忽略。
     * 异常时返回 true（视为"已有记录"），避免因查询失败而误报"今天还没记录"。
     */
    suspend fun hasAnyEventToday(dayKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            eventDao.listByDay(dayKey).any { it.parseStatus != PARSE_FAILED }
        } catch (e: Exception) {
            true
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** [ParsedEvent] → [EventEntity]（非首条事件的入库形态）。 */
    private fun ParsedEvent.toEntity(
        clientEventId: String,
        ts: Long,
        rawText: String,
        source: String,
        dayStartHour: Int,
    ): EventEntity {
        val now = System.currentTimeMillis()
        return EventEntity(
            clientEventId = clientEventId,
            ts = ts,
            dayKey = dayKeyOf(ts, dayStartHour),
            rawText = rawText,
            type = this.type,
            timeHint = this.timeHint,
            foods = this.foodsJson(),
            exercise = this.exercise,
            amount = this.amount,
            kcal = this.kcal,
            symptom = this.symptom,
            weightKg = this.weightKg,
            sleepH = this.sleepH,
            source = source,
            parseStatus = PARSE_DONE,
            retryCount = 0,
            lastError = null,
            origin = ORIGIN_USER,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * 写埋点。**失败不能影响主流程** —— 埋点是观测手段，不是业务。
     * errorHead 已在 provider 层脱敏，这里只做长度保护。
     *
     * 参数名与 [com.healix.app.db.LlmCallEntity] 的列名逐一对应，
     * 调用点全部用命名参数，避免顺序错位。
     *
     * @param promptVer 本次调用实际使用的 prompt 版本号。**由调用方传入** ——
     *   抽取 = `PROMPT_VER`(v2) / 计划 = `PROMPT_VER_PLAN`(v1) / 训练 =
     *   `PROMPT_VER_TRAINING`(v1) / 对话 = `PROMPT_VER_CHAT` / 测试 = `PROMPT_VER_NONE`。
     *   恒定写 `PROMPT_VER` 会让 `llm_calls.prompt_ver` 列语义失真（"哪版 prompt 效果更好"
     *   的归因会得出错误结论）。四个公开 wrapper 各自默认 `PROMPT_VER` 保持向后兼容。
     *   注：本类**不 import `ui` 包**（避免 repo → ui 反向依赖），故版本值一律由调用方透传。
     */
    private suspend fun recordCall(
        purpose: String,
        eventId: Long?,
        model: String,
        /** 默认 = 抽取链版本；抽取链内部调用直接走默认，不显式传（见下方调用点）。 */
        promptVer: String = PROMPT_VER,
        attempts: Int,
        latencyMs: Long,
        status: String,
        httpCode: Int? = null,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        errorHead: String? = null,
    ) {
        try {
            llmCallDao.insert(
                com.healix.app.db.LlmCallEntity(
                    ts = System.currentTimeMillis(),
                    purpose = purpose,
                    eventId = eventId,
                    model = model,
                    promptVer = promptVer,
                    attempts = attempts,
                    latencyMs = latencyMs,
                    status = status,
                    httpCode = httpCode,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    errorHead = errorHead?.take(200),
                )
            )
        } catch (e: Exception) {
            // 吞掉：埋点写入失败不影响用户记录
        }
    }

    /**
     * 对话链路（purpose=ask）的调用埋点 —— 配额计数（QuotaGuard.canChat）与
     * 设置页「今日对话调用」的数据来源。
     *
     * ⚠️ 2026-10-05 修复：此前聊天链路从未写 llm_calls，配额判空恒为未消耗、
     * 调用量恒显示 0。现由 HealthAgent 每次模型往返调用本方法（成功与失败都记）。
     * 抽取链仍走私有 [recordCall]（purpose 语义不同，勿混用）。
     */
    suspend fun recordChatCall(
        model: String,
        attempts: Int,
        latencyMs: Long,
        status: String,
        httpCode: Int? = null,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        errorHead: String? = null,
        /** 对话链 prompt 版本（调用方传 `PROMPT_VER_CHAT`）。默认 = 抽取链，向后兼容。 */
        promptVer: String = PROMPT_VER,
    ) {
        recordCall(
            purpose = PURPOSE_ASK,
            eventId = null,
            model = model,
            promptVer = promptVer,
            attempts = attempts,
            latencyMs = latencyMs,
            status = status,
            httpCode = httpCode,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            errorHead = errorHead,
        )
    }

    /**
     * 计划链（purpose=plan）的调用埋点。计划页「更新」每次 provider 往返都记一条
     * （成功与失败都记），是配额计数（`QuotaGuard.canExtract`，plan 归入抽取桶）
     * 与调试页的数据来源。
     *
     * ⚠️ 与 [recordChatCall] 同管道（都转发私有 [recordCall]），只是 purpose 固定为
     *    [PURPOSE_PLAN]；块体转发（转发 suspend 调用禁止 `= call()` 表达式体）。
     */
    suspend fun recordPlanCall(
        model: String,
        attempts: Int,
        latencyMs: Long,
        status: String,
        httpCode: Int? = null,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        errorHead: String? = null,
        /** 计划链 prompt 版本（调用方传 `ui.PROMPT_VER_PLAN`）。默认 = 抽取链，向后兼容。 */
        promptVer: String = PROMPT_VER,
    ) {
        recordCall(
            purpose = PURPOSE_PLAN,
            eventId = null,
            model = model,
            promptVer = promptVer,
            attempts = attempts,
            latencyMs = latencyMs,
            status = status,
            httpCode = httpCode,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            errorHead = errorHead,
        )
    }

    /**
     * 训练链（purpose=training）的调用埋点。训练页「生成本周计划」每次 provider
     * 往返都记一条（成功与失败都记），是配额计数（`QuotaGuard.canExtract`，
     * training 归入抽取桶）与调试页 / token 统计 / 失败诊断的数据来源。
     *
     * ⚠️ 与 [recordPlanCall] 形状**完全一致**（同管道转发私有 [recordCall]），
     *    唯一区别是 purpose 固定为 [PURPOSE_TRAINING]；块体转发（转发 suspend 调用
     *    禁止 `= call()` 表达式体 —— CI #31 的教训）。
     */
    suspend fun recordTrainingCall(
        model: String,
        attempts: Int,
        latencyMs: Long,
        status: String,
        httpCode: Int? = null,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        errorHead: String? = null,
        /** 训练链 prompt 版本（调用方传 `ui.PROMPT_VER_TRAINING`）。默认 = 抽取链，向后兼容。 */
        promptVer: String = PROMPT_VER,
    ) {
        recordCall(
            purpose = PURPOSE_TRAINING,
            eventId = null,
            model = model,
            promptVer = promptVer,
            attempts = attempts,
            latencyMs = latencyMs,
            status = status,
            httpCode = httpCode,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            errorHead = errorHead,
        )
    }

    /**
     * 设置页"真实请求"埋点（purpose = [PURPOSE_TEST]）。
     *
     * 场景：「测试连通性」与「接入并启用」都会发一次真实请求（消耗免费额度），
     * 此前**完全不计数** → `llm_calls` 缺行、调试页数据不完整。
     *
     * ⚠️ **不占配额**：`PURPOSE_TEST` **不并入** `QuotaGuard.CALL_PURPOSES` /
     *    `CHAT_PURPOSES`（那两处显式枚举，见 `QuotaGuard.kt`）—— 它只落库供统计
     *    与调试，不参与"今日调用次数"限额判定。这是拍板口径（落库但不占配额）。
     *
     * 形状与 [recordPlanCall] **完全同构**；块体转发（转发 suspend 调用禁止
     * `= call()` 表达式体 —— CI #31 的教训）。无 system prompt → 调用方传
     * [PROMPT_VER_NONE]。
     */
    suspend fun recordTestCall(
        model: String,
        attempts: Int,
        latencyMs: Long,
        status: String,
        httpCode: Int? = null,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        errorHead: String? = null,
        /** 测试链无 system prompt，调用方传 [PROMPT_VER_NONE]。默认 = 抽取链，向后兼容。 */
        promptVer: String = PROMPT_VER,
    ) {
        recordCall(
            purpose = PURPOSE_TEST,
            eventId = null,
            model = model,
            promptVer = promptVer,
            attempts = attempts,
            latencyMs = latencyMs,
            status = status,
            httpCode = httpCode,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            errorHead = errorHead,
        )
    }

    /**
     * 组装 provider 配置。
     *
     * baseUrl / model 来自 settings 表（非敏感，可导出迁移）；
     * apiKey 来自 [SecretStore]（加密存储，**绝不落 SQLite**，C6）。
     *
     * 任一缺失返回 null，调用方走"未配置"降级路径。
     */
    suspend fun loadProviderConfig(): ProviderConfig? {
        val key = secretStore?.apiKey() ?: return null
        val baseUrl = settingsDao.get(KEY_BASE_URL)?.trim().orEmpty()
        val model = settingsDao.get(KEY_MODEL)?.trim().orEmpty()
        if (baseUrl.isEmpty() || model.isEmpty()) return null

        val name = settingsDao.get(KEY_PROVIDER_NAME)?.trim().orEmpty().ifEmpty { "custom" }
        return ProviderConfig(baseUrl = baseUrl, model = model, apiKey = key, providerName = name)
    }

    private suspend fun loadMaxRetries(): Int =
        settingsDao.get(KEY_MAX_RETRIES)?.toIntOrNull()
            ?.coerceIn(0, 20) ?: DEFAULT_MAX_RETRIES

    private suspend fun loadRetryBaseSeconds(): Double =
        settingsDao.get(KEY_RETRY_BASE)?.toDoubleOrNull()
            ?.coerceIn(0.1, 30.0) ?: DEFAULT_RETRY_BASE_SECONDS

    private suspend fun loadExponentialBackoff(): Boolean =
        settingsDao.get(KEY_EXP_BACKOFF)?.toBooleanStrictOrNull() ?: true

    /** 日界线小时。**唯一夹取入口** = [dayStartHourOf]（禁止在别处再写 coerceIn）。 */
    private suspend fun loadDayStartHour(): Int =
        dayStartHourOf(settingsDao.get(KEY_DAY_START_HOUR))
}

/** submit 的结果。用数据类而非 Result，是为了带上埋点所需的全部信息。 */
data class SubmitResult(
    val ok: Boolean,
    val clientEventId: String,
    val error: String?,
    /** 除了首条之外新拆出的条数 */
    val extraCount: Int,
    val latencyMs: Long,
    val attempts: Int = 0,
    /** 仅 ok=true 时非空，供通知栏显示"已记录 X · 约 N kcal" */
    val firstEvent: ParsedEvent? = null,
    /** true = 用户还没配置 provider（预期行为，不是错误） */
    val needsConfiguration: Boolean = false,
)

/** 事件来源常量。 */
const val SOURCE_APP = "app"
const val SOURCE_NOTIFICATION = "notification"
const val SOURCE_PRESET = "preset"
const val SOURCE_AI_SUGGESTION = "ai_suggestion"

/** 来源归属 */
const val ORIGIN_USER = "user"
const val ORIGIN_AI = "ai_suggestion"

/** 埋点 purpose。 */
const val PURPOSE_EXTRACT = "extract"
const val PURPOSE_PLAN = "plan"
const val PURPOSE_REVIEW = "review"
const val PURPOSE_ASK = "ask"

/**
 * 无 system prompt 的链路（设置页测试 / 复盘）的 **prompt 版本占位**。
 *
 * 这几条链路没有 prompt 版本概念，与其恒定伪造 `PROMPT_VER`(v2) 污染埋点，
 * 不如显式记 `"none"` —— 归因时一眼可辨"该行无 prompt 版本"。
 */
const val PROMPT_VER_NONE = "none"

/**
 * 训练链（周计划生成）埋点 purpose。
 *
 * ⚠️ 2026-10-05 修复：训练链此前**从未埋点**（TrainingPlanner 内 recordCall/llmCall
 *    零命中），导致「生成本周计划」连点可无限烧免费额度，且调试页 / token 统计 /
 *    失败诊断全盲。现归入抽取配额桶（[QuotaGuard] 的 CALL_PURPOSES）。
 */
const val PURPOSE_TRAINING = "training"

/**
 * 设置页"真实请求"埋点 purpose（测试连通性 / 接入并启用）。
 *
 * ⚠️ **不占配额** —— 刻意**不并入** `QuotaGuard.CALL_PURPOSES` / `CHAT_PURPOSES`
 *    （那两处显式枚举）。低频 + 用户显式触发，风险可控；本 purpose 只落库供
 *    统计与调试页，不参与"今日调用次数"限额。拍板口径：落库但不占配额。
 */
const val PURPOSE_TEST = "test"

/** 供通知栏副标题用的占位：把 JSON 数组字符串读回列表（防御性，失败给空）。 */
fun parseFoodsJson(foodsJson: String): List<String> = try {
    val arr = JSONArray(foodsJson)
    (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }
} catch (e: Exception) {
    emptyList()
}
