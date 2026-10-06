package com.healix.app.ui

import android.content.Context
import com.healix.app.HealixApp
import com.healix.app.db.DailyPlanEntity
import com.healix.app.db.GoalDefaults
import com.healix.app.db.GoalMetrics
import com.healix.app.db.SettingsKeys
import com.healix.app.db.kcalTargetOf
import com.healix.app.net.ChatMessage
import com.healix.app.net.ChatRequest
import com.healix.app.net.ChatResult
import com.healix.app.net.ErrKind
import com.healix.app.net.NetworkStatus
import com.healix.app.net.OpenAiCompatProvider
import com.healix.app.parse.dayKeyOf
import com.healix.app.parse.dayStartHourOf
import com.healix.app.parse.loadsLenient
import com.healix.app.repo.EventRepository
import com.healix.app.repo.ProfileContext
import com.healix.app.repo.ResourceStore
import com.healix.app.repo.parseFoodsJson
import com.healix.app.rules.FoodPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/**
 * 今日计划 prompt 版本号（独立于抽取链 `PROMPT_VER` 与 `PROMPT_VER_TRAINING`）。
 * 此值只随计划 prompt 迭代递增。
 *
 * v1 → v2（问题 3 方案 C）：同一次调用顺带产出**明天**的 4 条粗颗粒锚点
 * （早/午/晚/训练），items 增 `day` / `slot` 两个字段。
 */
const val PROMPT_VER_PLAN: String = "v2"

/**
 * 今日计划 system prompt（设计规范 §4.3 / 增量设计 §4.4）。
 *
 * ⚠️ 本 prompt **不进** `pipeline/contract.py`：`contract.py` 的契约范围由
 *    `check_kotlin.py` 的 `PROMPT_PARITY` 显式枚举（只含抽取链与训练链），
 *    两者都有 Python 侧镜像实现、参与离线回归；计划链在 Python 侧无镜像，
 *    塞进契约文件只增加"逐字节一致"的手工维护负担而无校验价值。
 *    因此 `PROMPT_PLAN` / `PROMPT_VER_PLAN` **只存在于本文件**。
 */
const val PROMPT_PLAN: String = """你是 Healix 的计划助手。围绕用户的目标与当下真实数据，把**今天（从现在到睡前）**要做的事排成一条时间轴，并顺带给**明天**留下粗颗粒锚点。

硬规则（逐条遵守，冲突时序号小的优先）：
1. 只排「现在之后」的时段：已经过去的时刻不再安排（今天各项的 time 必须晚于"现在是 HH:mm"）。
2. 结合「热量缺口」安排饮食：缺口大就把正餐+加餐都排上并给具体数量；已达标则不再堆餐，改排训练或恢复。
3. 若今天有生病记录 → 全天改为恢复安排（清淡饮食 + 补水 + 早睡），不排任何训练。
4. 若今日睡眠不足 6 小时或近 3 日睡眠连续偏低 → 训练降强度（减量或改轻量有氧），并优先排早睡。
5. 有疼痛/不适部位 → 运动建议必须避开相关动作，优先恢复性建议（睡眠、补水）。有忌口/过敏 → 饮食建议必须绕开。
6. 训练只在热量已达标或缺口很小时安排；同一天最多一条训练项，强度参考本周训练计划里"今天"那条。
7. 禁止输出 1RM 估算、力量总分、综合评分或任何形式的打分；禁止给出药物剂量。
8. 今天的每一项都要有明确的 time（HH:mm 24 小时制）；按时间从早到晚排序，最多 6 项。
9. 明天只给 4 条**粗颗粒锚点**：早 / 午 / 晚 三餐 + 训练；这 4 条 day 一律写 2，time 一律写空字符串。
10. 明天的训练锚点参考本周训练计划里"明天"那条；若那一句给的是"无"，说明明天休息或本周计划还没生成 → 写恢复/休息，不要硬排力量训练；今天有生病记录或有疼痛/不适部位时，明天的训练锚点同样写恢复。
11. 明天是"先把位置留出来"：内容给常见分量即可，不必精确，也不要编造明天才会有的数据（明天的体重、睡眠、摄入都还没有）。

输出要求：
- 只输出 JSON，不要任何解释文字，不要 markdown 代码围栏。
- items 每项含 day / time / slot / type / title / detail / kcal / duration / why。
- day：今天写 1，明天写 2。
- slot：只对 day=2 有意义，只能取 morning（早）/ noon（午）/ evening（晚）/ train（训练）之一；day=1 的项写空字符串。
- time：day=1 必填 HH:mm；day=2 一律空字符串（锚点不显示具体时刻）。
- type ∈ {meal, exercise, sleep, habit}；sleep/habit 项 kcal 写 0，duration 可写"——"；day=2 的项 kcal 也一律写 0。
- title 简短（day=1 如"晚餐""力量训练""睡觉"；day=2 写内容本身，如"燕麦牛奶 + 鸡蛋"）；
  detail 给具体怎么做（数量/动作/时长）；duration 写"约 N 分钟"；why 用一句话说明为什么这样安排。
- note 一句话总结今天的重点。

输出格式固定为：
{"items": [{"day": 1, "time": "12:30", "slot": "", "type": "meal", "title": "午餐", "detail": "米饭 200g + 鸡胸 150g + 一份绿叶菜", "kcal": 650, "duration": "约 20 分钟", "why": "还差 1200 kcal，先补一半"}, {"day": 1, "time": "18:30", "slot": "", "type": "exercise", "title": "力量训练", "detail": "深蹲 4×8 / 卧推 4×8", "kcal": 200, "duration": "约 30 分钟", "why": "午餐后 6 小时，状态正好"}, {"day": 2, "time": "", "slot": "morning", "type": "meal", "title": "燕麦牛奶 + 鸡蛋", "detail": "燕麦 60g + 牛奶 300ml + 鸡蛋 2 个", "kcal": 0, "duration": "约 15 分钟", "why": "先占个位置，明天按当时的缺口再调"}, {"day": 2, "time": "", "slot": "noon", "type": "meal", "title": "米饭 + 鸡胸 + 绿叶菜", "detail": "米饭 200g + 鸡胸 150g + 一份绿叶菜", "kcal": 0, "duration": "约 20 分钟", "why": "先占个位置，明天按当时的缺口再调"}, {"day": 2, "time": "", "slot": "evening", "type": "meal", "title": "面食 + 牛肉", "detail": "面食一份 + 牛肉 120g + 一份蔬菜", "kcal": 0, "duration": "约 20 分钟", "why": "先占个位置，明天按当时的缺口再调"}, {"day": 2, "time": "", "slot": "train", "type": "exercise", "title": "深蹲 4×8 / 卧推 4×8", "detail": "按本周计划明天的安排执行", "kcal": 0, "duration": "约 30 分钟", "why": "明天是训练日，先留出位置"}], "note": "今天先补足蛋白，晚上安排一次力量；明天三餐与训练已占位"}
"""

/**
 * 时间轴条目（同时作为 UI 模型）。
 *
 * @property time  今天的条目为 "HH:mm" 24 小时制；旧数据可能为空 → 渲染时隐藏时间列。
 *                 **明天的锚点恒为空串**（锚点不显示具体时刻，见 [slot]）。
 * @property type  meal | exercise | sleep | habit（仅 meal/exercise 可「记一笔」）。
 * @property kcal  估算热量；sleep/habit 为 0。**明天锚点恒为 0** —— 明天的摄入还没发生，
 *                 给数字就是编数据（且锚点不可「记一笔」，见 [TimelineMerger.merge]）。
 * @property duration 空串显示「——」。
 * @property why   一句「为什么是现在」；空串不显示。
 * @property day   [PLAN_DAY_TODAY] / [PLAN_DAY_TOMORROW]（问题 3 方案 C）。
 * @property slot  明天锚点的时段键（[PlanSlot] 之一；今天条目为空串）。
 */
data class TimelineItem(
    val time: String,
    val type: String,
    val title: String,
    val detail: String,
    val kcal: Int,
    val duration: String = "",
    val why: String = "",
    val day: Int = PLAN_DAY_TODAY,
    val slot: String = "",
)

/** 生成/读取结果。`failed = true` 表示 AI 失败（缓存保留或走了本地兜底）。 */
data class PlanResult(
    val items: List<TimelineItem>,
    val note: String,
    /** ai | fallback */
    val source: String,
    val generatedAt: Long,
    val failed: Boolean = false,
    val fromCache: Boolean = false,
)

/**
 * 今日计划生成器（对齐 [TrainingPlanner] 的现成范式）。
 *
 * 职责：读缓存 / 调一次模型（用户点「更新」时）/ 防御性解析 /
 * 本地时间轴兜底 / 落 `daily_plans` / 埋点。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 成本与触发纪律（PRD §8.3）
 * ══════════════════════════════════════════════════════════════════════════
 * **只在计划页点「更新」时调模型**（`update`）；打开计划 Tab 只读缓存
 * （[loadCached]，`force=false`），绝不调 AI —— 与训练页整周缓存同一成本纪律。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 失败必须降级可用（PRD 风险表：免费档 429 密集）
 * ══════════════════════════════════════════════════════════════════════════
 * 未配置 provider / 限流 / 超时 / JSON 解析失败：**有缓存保留缓存（不清空），
 * 无缓存落本地时间轴 [localTimeline]**（`source = fallback`）。
 */
class PlanGenerator(context: Context) {

    private val app: HealixApp = HealixApp.from(context)
    private val appContext: Context = context.applicationContext
    private val db = app.database

    companion object {
        /** `plan_json` schema 版本（见增量设计 §3.4）。v3：items 增 `day` / `slot` 两字段。 */
        private const val PLAN_JSON_VERSION = 3

        /** 主目标「增重」编码（与 HealthAggregator / SettingsViewModel 口径一致）。 */
        private const val PLAN_PRIMARY_GAIN = 0

        /** item.type 缺失时的安全兜底（无「记一笔」，最不具副作用）。 */
        private const val PLAN_TYPE_HABIT = "habit"

        /** `type` 常量（本地兜底条目用；与 prompt 约定一致）。 */
        private const val PLAN_TYPE_MEAL = "meal"
        private const val PLAN_TYPE_EXERCISE = "exercise"
        private const val PLAN_TYPE_SLEEP = "sleep"

        /** 明天锚点的统一「为什么」：说清这是占位、当天会按真实数据细化（不冒充精确）。 */
        private const val TOMORROW_WHY = "明天的占位锚点，当天会按你的实际数据细化"

        /** 「今天」条目上限（prompt 约定）。 */
        private const val MAX_TODAY_ITEMS = 6

        /** 「明天」锚点上限（早 / 午 / 晚 / 训练，共 4 条）。 */
        private const val MAX_TOMORROW_ITEMS = 4

        /**
         * 数组**扫描**上限：模型输出永不可信，防啰嗦 / prompt 被注入时返回成百条拖垮
         * 主线程 inflate（渲染见 PlanReviewFragment.renderTimeline，逐项 inflate）。
         * 今天与明天**各自**另有条数上限（见 [itemsOf]），此处只封总扫描量。
         * ⚠️ 命名为 MAX_PLAN_ITEMS 而非 MAX_EVENTS，避免与 SchemaValidator 的
         *    抽取链同名常量撞车触发 check_duplicate_constants。
         */
        private const val MAX_PLAN_ITEMS = MAX_TODAY_ITEMS + MAX_TOMORROW_ITEMS + 2

        private const val MAX_TITLE_LEN = 80
        private const val MAX_DETAIL_LEN = 120
        private const val MAX_WHY_LEN = 80
        private const val MAX_NOTE_LEN = 200
    }

    // ------------------------------------------------------------------
    // 读：只读缓存（打开计划 Tab 用，force=false，绝不调 AI）
    // ------------------------------------------------------------------

    /** 今日 day_key（yyyy-MM-dd），供调用方拼接缓存键。 */
    suspend fun todayKey(): String = withContext(Dispatchers.IO) {
        val dayStart = dayStartHour()
        dayKeyOf(System.currentTimeMillis(), dayStart)
    }

    /**
     * 读今日计划缓存。命中且解析成功返回 [PlanResult]（`fromCache = true`）；
     * 无缓存或解析失败返回 null（调用方走本地兜底，**绝不触发模型调用**）。
     */
    suspend fun loadCached(todayKey: String): PlanResult? = withContext(Dispatchers.IO) {
        val stored = runCatching { db.planDao().getPlan(todayKey) }.getOrNull()
            ?: return@withContext null
        val parsed = parseTimelineJson(stored.planJson) ?: return@withContext null
        PlanResult(
            items = parsed.items,
            note = parsed.note,
            source = stored.source,
            generatedAt = stored.generatedAt,
            fromCache = true,
        )
    }

    // ------------------------------------------------------------------
    // 本地时间轴兜底（无 AI 可用时的降级；不落库由调用方决定）
    // ------------------------------------------------------------------

    /**
     * 纯本地降级时间轴：把既有规则条目（晚餐 / 加餐 / 睡眠 / 训练 / 恢复）
     * **按时段铺开**成一条时间轴，并按问题 3 方案 C 补一组**明天的粗颗粒锚点**
     * （早 / 午 / 晚 / 训练；`kcal` 全 0 —— 明天的摄入还没发生，不编数字）。
     *
     * ⚠️ **给的是估算值**（这是实话，不是"不编数据"）：受本地规则限制，
     * [buildTimeline] 会给出**估算**的数量与热量（如 400/650/450/400/200 kcal
     * 与"熟米饭 200g + 鸡胸或牛肉 150g"这类常见分量），目的是让用户"照着吃"
     * 有用；但它**不是精确数据**——kcal 是按常见分量拍的估算值。
     * 用户点「记一笔」前，来源行已标注为估算（见 [PlanReviewFragment.renderPlanHeader]
     * 的 `plan_source_estimated`）。食物建议优先取手动清单，物品条件取资源清单。
     *
     * ⚠️ internal：签名暴露 internal 类型 [TodaySummary]，不能是 public
     *    （否则编译报 "public function exposes its internal parameter type"）。
     */
    internal suspend fun localTimeline(summary: TodaySummary): PlanResult = withContext(Dispatchers.IO) {
        val manualFoods = ResourceStore.splitItems(ResourceStore.foods(db))
        val pool = runCatching { FoodPool.build(appContext) }.getOrDefault(emptyList())
        val sport = ResourceStore.sport(db)
        val pain = parseFoodsJson(db.settingsDao().get(SettingsKeys.PROFILE_PAIN).orEmpty())
        val bedTime = db.settingsDao().get(SettingsKeys.PROFILE_SLEEP_BED).orEmpty()
        val now = currentHhmm()
        // 明天的训练锚点要跟本周计划对齐：那天休息 / 没有计划 → 不发训练锚点
        //（免得把「明天休息」覆盖成一条训练行，见 TimelineMerger 的抑制约定）。
        val tomorrowTraining = trainingLineFor(1)

        PlanResult(
            items = buildTimeline(summary, manualFoods, pool, sport, pain, bedTime, now) +
                buildTomorrowAnchors(
                    manualFoods = manualFoods,
                    pool = pool,
                    sport = sport,
                    pain = pain,
                    trainSummary = tomorrowTraining,
                    hasIllness = summary.hasIllness,
                ),
            note = buildNote(summary),
            source = TrainingPlanner.SOURCE_FALLBACK,
            generatedAt = System.currentTimeMillis(),
        )
    }

    // ------------------------------------------------------------------
    // 更新：唯一会调模型的入口（用户点「更新」时，force=true）
    // ------------------------------------------------------------------

    /**
     * 重新排今日时间轴（唯一会调模型的入口）。
     *
     * 降级链（未配置/不可用 与 AI 失败**同一收口** [failWithFallbackOrCache]）：
     * 有缓存 → 保留上一版（不清空）；无缓存 → 落本地兜底。
     * AI Ok 但解析为空 → 亦视失败走该收口。
     *
     * 埋点不变式（**一次 provider 往返恰好一行 `llm_calls`**）：
     * - 解析成功 → `STATUS_OK`；HTTP 通但 JSON 没用（解析空）→ `STATUS_SCHEMA_INVALID`；
     * - 其它失败 → 按 [ErrKind] 记 `TIMEOUT` / `HTTP_ERROR` / `RETRY_EXHAUSTED`。
     * ⚠️ **绝不用"补记一条"实现** —— 那会让一次往返产生 2 行，`QuotaGuard.canExtract()`
     *    按行数计数 → 白多消耗一次配额。正确改法是把 OK 记录**移位**到解析成功之后。
     *
     * ⚠️ internal：签名暴露 internal 类型 [TodaySummary]（同 [localTimeline]）。
     */
    internal suspend fun update(todayKey: String, summary: TodaySummary): PlanResult = withContext(Dispatchers.IO) {
        val config = runCatching { app.eventRepository.loadProviderConfig() }.getOrNull()
        if (config == null || !config.isUsable() || !NetworkStatus.isOnline(app)) {
            // 未配置 / 不可用 / 断网：不调网。与 AI 失败走同一收口 ——
            // 有缓存保留上一版，无缓存才落本地兜底（不得用兜底覆盖已有缓存）。
            // 断网同样不调网：NETWORK 属于可重试 ErrKind，离线会白等整条退避链
            //（最坏 ~75-80 秒），把按钮卡在「更新中」。与对话链同一口径。
            return@withContext failWithFallbackOrCache(todayKey, summary)
        }

        val provider = OpenAiCompatProvider(config)
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(role = "system", content = PROMPT_PLAN),
                ChatMessage(role = "user", content = buildUserContext()),
            ),
            temperature = 0.5,
            timeoutMs = 20_000L,
            // 免费档 429 密集：重试 2 次即可，不把用户卡在长退避链上
            maxRetries = 2,
            retryBaseSeconds = 1.5,
            exponentialBackoff = true,
        )

        val started = System.currentTimeMillis()
        val result = provider.chat(request)
        val latencyMs = System.currentTimeMillis() - started

        when (result) {
            is ChatResult.Ok -> {
                // ⚠️ 埋点**移位**到解析判定之后（见函数 KDoc 的不变式）：
                //    一次 provider 往返恰好一行 llm_calls，绝不"先记 OK 再补记"。
                val parsed = parseTimelineJson(result.content)
                if (parsed != null) {
                    app.eventRepository.recordPlanCall(
                        model = config.model,
                        attempts = 1,
                        latencyMs = latencyMs,
                        status = EventRepository.STATUS_OK,
                        httpCode = 200,
                        inputTokens = result.usage.inputTokens,
                        outputTokens = result.usage.outputTokens,
                        promptVer = PROMPT_VER_PLAN,
                    )
                    val plan = PlanResult(
                        items = parsed.items,
                        note = parsed.note,
                        source = TrainingPlanner.SOURCE_AI,
                        generatedAt = System.currentTimeMillis(),
                    )
                    persist(todayKey, plan, summary.target)
                    return@withContext plan
                }
                // HTTP 通了但 JSON 没用（解析为空 / items 空）→ 改记 schema_invalid，
                // 再走兜底/缓存。否则埋点会显示"成功"，模型开始吐坏 JSON 这类回归
                // 在数据上看不出来。
                app.eventRepository.recordPlanCall(
                    model = config.model,
                    attempts = 1,
                    latencyMs = latencyMs,
                    status = EventRepository.STATUS_SCHEMA_INVALID,
                    httpCode = 200,
                    inputTokens = result.usage.inputTokens,
                    outputTokens = result.usage.outputTokens,
                    errorHead = result.content.take(200),
                    promptVer = PROMPT_VER_PLAN,
                )
                failWithFallbackOrCache(todayKey, summary)
            }

            is ChatResult.Err -> {
                app.eventRepository.recordPlanCall(
                    model = config.model,
                    attempts = if (result.attempts > 0) result.attempts else request.maxRetries + 1,
                    latencyMs = latencyMs,
                    status = when (result.kind) {
                        ErrKind.TIMEOUT -> EventRepository.STATUS_TIMEOUT
                        ErrKind.AUTH -> EventRepository.STATUS_HTTP_ERROR
                        else -> EventRepository.STATUS_RETRY_EXHAUSTED
                    },
                    httpCode = result.httpCode,
                    errorHead = result.message,
                    promptVer = PROMPT_VER_PLAN,
                )
                failWithFallbackOrCache(todayKey, summary)
            }
        }
    }

    /**
     * AI 失败后的收口：有缓存 → 保留上一版（`failed = true`，不清空）；无缓存 →
     * 落本地兜底（`source = fallback`，`failed = true`）。
     */
    private suspend fun failWithFallbackOrCache(todayKey: String, summary: TodaySummary): PlanResult {
        val cached = loadCached(todayKey)
        if (cached != null) {
            return cached.copy(failed = true, fromCache = true)
        }
        val local = localTimeline(summary)
        val persisted = local.copy(failed = true, generatedAt = System.currentTimeMillis())
        persist(todayKey, persisted, summary.target)
        return persisted
    }

    // ------------------------------------------------------------------
    // user 上下文（本地事实，不做推测）
    // ------------------------------------------------------------------

    /** 聚合喂给模型的本地事实：目标 + 结构化目标 + 画像 + 今日摘要 + 今日/明天训练 + 近 3 日摘要。 */
    private suspend fun buildUserContext(): String {
        val goalStatement = db.settingsDao().get(SettingsKeys.GOAL_STATEMENT).orEmpty().trim()
        val primaryIdx = db.goalDao().getByMetric(GoalMetrics.PRIMARY)
            ?.targetValue?.toInt() ?: PLAN_PRIMARY_GAIN
        val targetKcal = kcalTargetOf(db)
        val sessions = goalInt(GoalMetrics.SESSIONS_PER_WEEK, GoalDefaults.TRAIN_SESSIONS_PER_WEEK)
        val minutes = goalInt(GoalMetrics.TRAIN_MINUTES_PER_WEEK, GoalDefaults.TRAIN_MINUTES_PER_WEEK)
        val sleepH = db.goalDao().getByMetric(GoalMetrics.SLEEP_H)
            ?.targetValue?.takeIf { it > 0.0 } ?: GoalDefaults.SLEEP_H
        val waterMl = goalInt(GoalMetrics.WATER_ML, GoalDefaults.WATER_ML)

        val summary = TodaySummary.build(appContext)
        val profile = ProfileContext.build(db)
        val todayTraining = trainingLineFor(0)
        val tomorrowTraining = trainingLineFor(1)
        val recent = recentSummary()

        return buildString {
            // 目标（用户自述）行（2026-10-05）：画像类自由文本，受 AI_DATA_FULL
            // 总开关门控 —— 关闭时整行省略（不打印"未填写"）。其余行是排程
            // 操作数（裁定 F），保留 GoalDefaults 兜底，不受本开关影响。
            if (ProfileContext.aiDataFull(db)) {
                appendLine("目标（用户自述）：" + goalStatement.ifEmpty { "未填写" })
            }
            appendLine("主目标：" + goalName(primaryIdx))
            appendLine("每日目标摄入：$targetKcal kcal")
            appendLine("每周训练：$sessions 次 / $minutes 分钟")
            appendLine("睡眠目标：${trim(sleepH)} 小时   饮水目标：$waterMl ml")
            if (profile.isNotBlank()) appendLine(profile)
            summary.lines.forEach { appendLine(it) }
            appendLine("本周训练计划里\"今天\"那条：${todayTraining ?: "无"}")
            appendLine("本周训练计划里\"明天\"那条：${tomorrowTraining ?: "无"}")
            appendLine("最近记录摘要：$recent")
            appendLine("请按硬规则排出今天从现在到睡前的时间轴，并附上明天的 4 条锚点。")
        }.trim()
    }

    /**
     * 本周训练计划里「今天 + [offsetDays]」那天的安排（`标题 · 动作`）。
     *
     * @return 该天的安排；**本周计划尚未生成、或那天是休息日 → null**
     *         （调用方据此区分「无」与「休息」，见 prompt 硬规则 10）。
     */
    private suspend fun trainingLineFor(offsetDays: Long): String? {
        val plan = runCatching { TrainingPlanner(appContext).loadOrGenerate(force = false) }.getOrNull()
            ?: return null
        val dow = LocalDate.now().plusDays(offsetDays).dayOfWeek.value
        val day = plan.days.firstOrNull { it.dow == dow && !it.isRest } ?: return null
        val line = listOf(day.title, day.itemsLine())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        return line.ifBlank { null }
    }

    /** 近 3 日（不含今天）记录摘要；无记录写「无」。 */
    private suspend fun recentSummary(): String {
        val dayStart = dayStartHour()
        val today = LocalDate.parse(dayKeyOf(System.currentTimeMillis(), dayStart))
        val parts = mutableListOf<String>()
        for (offset in 1..3) {
            val key = today.minusDays(offset.toLong()).toString()
            val rows = runCatching { db.eventDao().listByDay(key) }.getOrDefault(emptyList())
            if (rows.isEmpty()) continue
            val kcal = rows.filter { it.type == PLAN_TYPE_MEAL }.sumOf { it.kcal }
            val sleep = rows.firstOrNull { it.type == PLAN_TYPE_SLEEP && it.sleepH > 0 }?.sleepH ?: 0.0
            parts += "$key ${rows.size} 条 / 摄入 $kcal kcal / 睡眠 ${trim(sleep)} 小时"
        }
        return if (parts.isEmpty()) "无" else parts.joinToString("；")
    }

    // ------------------------------------------------------------------
    // 本地时间轴构造（迁移自 PlanReviewViewModel 的规则条目）
    // ------------------------------------------------------------------

    /**
     * 规则条目 → 时间轴（按时段铺开）。三种形态与原 `buildLocalPlan` 一致：
     * 生病 → 单条清淡餐；已达标 → 收尾条目（训练 / 恢复）；有缺口 → 晚餐(+加餐)(+睡眠)。
     */
    private fun buildTimeline(
        s: TodaySummary,
        manualFoods: List<String>,
        pool: List<String>,
        sport: String,
        pain: List<String>,
        bedTime: String,
        now: String,
    ): List<TimelineItem> {
        if (s.hasIllness) {
            return listOf(
                TimelineItem(
                    time = slotOrNow("18:00", now),
                    type = PLAN_TYPE_MEAL,
                    title = "晚餐：清淡易消化",
                    detail = "小米粥 + 蒸蛋，避免油腻与生冷",
                    kcal = 400,
                    duration = "约 15 分钟",
                    why = "今天有生病记录，先恢复再训练",
                ),
            )
        }

        if (s.gap <= 0) {
            return listOf(tailItem(sport, pain, slotOrNow("20:00", now)))
        }

        val items = mutableListOf<TimelineItem>()

        if (s.gap >= 600) {
            items += TimelineItem(
                time = slotOrNow("18:30", now),
                type = PLAN_TYPE_MEAL,
                title = "晚餐：主食 + 蛋白质",
                detail = mealDetail(manualFoods, pool, "熟米饭 200g + 鸡胸或牛肉 150g + 一份绿叶菜"),
                kcal = 650,
                duration = "约 20 分钟",
                why = "还差 ${s.gap} kcal，这一顿补上一大半",
            )
        } else {
            items += TimelineItem(
                time = slotOrNow("18:30", now),
                type = PLAN_TYPE_MEAL,
                title = "晚餐：正常一份主食",
                detail = mealDetail(manualFoods, pool, "面食或米饭一份 + 一个鸡蛋"),
                kcal = 450,
                duration = "约 15 分钟",
                why = "缺口不大，一顿补齐",
            )
        }

        val remain = s.gap - items.sumOf { it.kcal }
        if (remain > 200) {
            items += TimelineItem(
                time = slotOrNow("21:00", now),
                type = PLAN_TYPE_MEAL,
                title = "加餐：睡前补充",
                detail = mealDetail(manualFoods, pool, "蛋白粉 1 勺 + 香蕉 1 根"),
                kcal = 400,
                duration = "约 5 分钟",
                why = "离睡眠还有几个小时，小份加餐好消化",
            )
        }

        // 睡眠行动条：近 3 日平均睡眠不足 6.5 小时才提（有数据才建议，不猜）
        if (s.sleepLast3.size >= 2 && s.sleepLast3.average() < 6.5) {
            items += TimelineItem(
                time = sleepSlot(bedTime, now),
                type = PLAN_TYPE_SLEEP,
                title = "睡：${bedTime.ifBlank { "定点" }} 前放下手机",
                detail = "今晚按目标就寝时间执行",
                kcal = 0,
                duration = "——",
                why = "近 3 天平均只睡 ${trim(s.sleepLast3.average())} 小时",
            )
        }

        return items
    }

    /** 热量已达标时的收尾条目：疼痛（F9）→ 恢复；否则训练（条件来自资源清单）。 */
    private fun tailItem(sport: String, pain: List<String>, time: String): TimelineItem = when {
        pain.isNotEmpty() -> TimelineItem(
            time = time,
            type = PLAN_TYPE_SLEEP,
            title = "恢复：补水 + 早睡",
            detail = "疼痛/不适部位（${pain.joinToString("、")}）相关动作今天全部避开",
            kcal = 0,
            duration = "——",
            why = "恢复优先于训练（疼痛避让）",
        )
        else -> TimelineItem(
            time = time,
            type = PLAN_TYPE_EXERCISE,
            title = "练：力量训练 30 分钟",
            detail = if (sport.isBlank()) {
                "深蹲 + 俯卧撑，自重就够"
            } else {
                "按你的条件练：${sport.lineSequence().joinToString("；")}"
            },
            kcal = 200,
            duration = "约 30 分钟",
            why = "今天热量已达标，正好安排训练",
        )
    }

    /**
     * 「明天」的粗颗粒锚点（问题 3 方案 C）：早 / 午 / 晚 三餐 +（非休息日才有的）训练。
     *
     * 纯模板铺开、零网络。三点刻意的约束：
     * 1. `time` 一律空串 —— 锚点**不显示具体时刻**（明天的时刻还没意义）；
     * 2. `kcal` 一律 0 —— 明天的摄入还没发生，给数字就是编数据；
     * 3. 第 4 条（训练位）按 `生病 / 疼痛 → 恢复`、`本周计划明天那条 → 跟它`、
     *    `资源清单有装备 → 按条件练`、**都没有就不发** 的顺序取一条；
     *    完全不发时，明天那格仍保留训练日行本身（休息日显示「休息」），
     *    不会被一条凭空造出来的训练锚点覆盖（见 [TimelineMerger.merge] 的抑制约定）。
     *
     * @param trainSummary 本周训练计划里「明天」那条（`标题 · 动作`）；null = 明天休息 / 无计划
     * @param hasIllness 今天有生病记录 → 明天也不排力量，训练位改「恢复」
     */
    private fun buildTomorrowAnchors(
        manualFoods: List<String>,
        pool: List<String>,
        sport: String,
        pain: List<String>,
        trainSummary: String?,
        hasIllness: Boolean,
    ): List<TimelineItem> {
        val out = mutableListOf<TimelineItem>()

        out += TimelineItem(
            time = "",
            type = PLAN_TYPE_MEAL,
            title = anchorTitle(manualFoods, pool, "燕麦牛奶 + 鸡蛋"),
            detail = "燕麦 60g + 牛奶 300ml + 鸡蛋 2 个",
            kcal = 0,
            duration = "约 15 分钟",
            why = TOMORROW_WHY,
            day = PLAN_DAY_TOMORROW,
            slot = PlanSlot.MORNING,
        )
        out += TimelineItem(
            time = "",
            type = PLAN_TYPE_MEAL,
            title = anchorTitle(manualFoods, pool, "主食 + 蛋白质 + 蔬菜"),
            detail = "米饭 200g + 鸡胸 150g + 一份绿叶菜",
            kcal = 0,
            duration = "约 20 分钟",
            why = TOMORROW_WHY,
            day = PLAN_DAY_TOMORROW,
            slot = PlanSlot.NOON,
        )
        out += TimelineItem(
            time = "",
            type = PLAN_TYPE_MEAL,
            title = anchorTitle(manualFoods, pool, "面食 + 牛肉 + 蔬菜"),
            detail = "面食一份 + 牛肉 120g + 一份蔬菜",
            kcal = 0,
            duration = "约 20 分钟",
            why = TOMORROW_WHY,
            day = PLAN_DAY_TOMORROW,
            slot = PlanSlot.EVENING,
        )

        // 第 4 条（训练位）四选一，优先级与 [tailItem] 同口径：生病 / 疼痛 → 恢复优先。
        when {
            hasIllness || pain.isNotEmpty() -> out += TimelineItem(
                time = "",
                type = PLAN_TYPE_SLEEP,
                title = "恢复：补水 + 早睡",
                detail = if (pain.isEmpty()) {
                    "今天记录了不适，明天先恢复，不排力量训练"
                } else {
                    "疼痛/不适部位（${pain.joinToString("、")}）相关动作先避开"
                },
                kcal = 0,
                duration = "——",
                why = TOMORROW_WHY,
                day = PLAN_DAY_TOMORROW,
                slot = PlanSlot.TRAIN,
            )
            trainSummary != null -> out += TimelineItem(
                time = "",
                type = PLAN_TYPE_EXERCISE,
                title = trainSummary.substringBefore(" · ").ifBlank { "按本周计划练" },
                detail = "按本周计划明天的安排执行",
                kcal = 0,
                duration = "约 30 分钟",
                why = TOMORROW_WHY,
                day = PLAN_DAY_TOMORROW,
                slot = PlanSlot.TRAIN,
            )
            sport.isNotBlank() -> out += TimelineItem(
                time = "",
                type = PLAN_TYPE_EXERCISE,
                title = "按你的条件练",
                detail = "按你的条件练：${sport.lineSequence().joinToString("；")}",
                kcal = 0,
                duration = "约 30 分钟",
                why = TOMORROW_WHY,
                day = PLAN_DAY_TOMORROW,
                slot = PlanSlot.TRAIN,
            )
        }

        return out
    }

    /** 锚点标题：手动清单优先于常吃池（都为空时用既有的常见搭配），最多取 2 项。 */
    private fun anchorTitle(manual: List<String>, pool: List<String>, fallback: String): String = when {
        manual.isNotEmpty() -> manual.take(2).joinToString(" + ")
        pool.isNotEmpty() -> pool.take(2).joinToString(" + ")
        else -> fallback
    }

    /**
     * 食物建议明细：手动清单（资源清单，"我现在就有"）优先于常吃池
     *（F7「他吃过 = 他买得到」）；都为空时回落既有建议，不编食物。
     */
    private fun mealDetail(manual: List<String>, pool: List<String>, fallback: String): String =
        when {
            manual.isNotEmpty() -> "用你手头的：${manual.take(3).joinToString("、")}（按平常的量）"
            pool.isNotEmpty() -> "优先常吃：${pool.take(3).joinToString("、")}（按平常的量）"
            else -> fallback
        }

    /** 兜底计划的 note（迁移自原 `buildNote`）。 */
    private fun buildNote(s: TodaySummary): String = when {
        s.hasIllness -> "今天记录了不适，计划已改为清淡饮食，暂不安排高强度运动。"
        s.recordCount == 0 -> "今天还没有记录，下面按默认目标给出建议。"
        s.gap <= 0 -> "今天已达标，可以安排一次力量训练。"
        s.gap >= 1500 -> "缺口较大，建议分成晚餐和加餐两次补上。"
        else -> "按当前缺口给出了具体数量和热量，照着吃即可。"
    }

    // ------------------------------------------------------------------
    // 解析 / 序列化 / 落库
    // ------------------------------------------------------------------

    private data class TimelineJson(val items: List<TimelineItem>, val note: String)

    /**
     * 防御性解析 `plan_json`（模型/旧数据永远不可信）：
     * 1. 根是 JSONObject 且有 items 数组 → 新格式；
     * 2. 根是 JSONArray → 逐元素当 item（更老形态）；
     * 3. 单条 item 字段缺失/非法一律收敛为安全值；`why` ↔ `whyNow` 双字段名；
     * 4. items 为空 → 返回 null（交调用方兜底）。
     */
    private fun parseTimelineJson(json: String?): TimelineJson? {
        if (json.isNullOrBlank()) return null
        return when (val root = loadsLenient(json)) {
            is JSONObject -> {
                val arr = root.optJSONArray("items") ?: return null
                val items = itemsOf(arr)
                if (items.isEmpty()) return null
                // ⚠️ 用 optText 而非 optString：optString 对 JSON null 会返回字面字符串
                //    "null"（本文件的 optText 专门挡了 JSONObject.NULL），模型给
                //    "note": null 时计划底部会显示字面 "null"。同时按 MAX_NOTE_LEN 截断。
                //    —— 这一处顺带修掉一个既有 P3（note 未挡 JSON null）。
                TimelineJson(items, root.optText("note").take(MAX_NOTE_LEN))
            }
            is JSONArray -> {
                val items = itemsOf(root)
                if (items.isEmpty()) return null
                TimelineJson(items, "")
            }
            else -> null
        }
    }

    /**
     * items 数组 → 条目列表（防御性，模型输出永不可信）。
     *
     * 与 v1 的差别（问题 3 方案 C）：按 `day` **分日计数**，今天 ≤ [MAX_TODAY_ITEMS]、
     * 明天 ≤ [MAX_TOMORROW_ITEMS] —— 单看总数会让"模型把明天写成 10 条"把今天挤空。
     * 明天锚点另有三条硬归一：`time` 强制空串、`kcal` 强制 0、`slot` 认不出则整条丢弃。
     */
    private fun itemsOf(arr: JSONArray): List<TimelineItem> {
        val out = mutableListOf<TimelineItem>()
        var todayCount = 0
        var tomorrowCount = 0
        // 条数上限：parseTimelineJson → renderTimeline 在主线程逐项 inflate，
        // 模型啰嗦或 prompt 被注入时返回成百条 = 卡顿 / ANR / OOM。
        for (i in 0 until minOf(arr.length(), MAX_PLAN_ITEMS)) {
            val o = arr.optJSONObject(i) ?: continue
            // day 只认「次日」这一个额外值：模型给 0 / 3 / 乱码一律收敛回今天。
            val tomorrow = o.optInt("day", PLAN_DAY_TODAY) >= PLAN_DAY_TOMORROW
            val slot = if (tomorrow) resolveSlot(o.optText("slot"), o.optText("time")) else ""
            // 时段认不出来 → 丢弃该锚点：宁可少一条，也不把说不清时段的行塞进「明天」。
            if (tomorrow && slot == null) continue
            if (tomorrow) {
                if (tomorrowCount >= MAX_TOMORROW_ITEMS) continue
            } else if (todayCount >= MAX_TODAY_ITEMS) {
                continue
            }

            // 先截断再判空：长度上限对 title/detail 生效后再决定是否跳过该项。
            val title = o.optText("title").take(MAX_TITLE_LEN)
            val detail = o.optText("detail").take(MAX_DETAIL_LEN)
            if (title.isEmpty() && detail.isEmpty()) continue

            if (tomorrow) tomorrowCount++ else todayCount++
            out += TimelineItem(
                // 锚点不显示具体时刻：即使模型给了 time 也一律清空（渲染口径唯一）。
                time = if (tomorrow) "" else o.optText("time").take(5),
                type = o.optText("type").ifEmpty { PLAN_TYPE_HABIT }.take(16),
                title = title,
                detail = detail,
                // 明天的摄入还没发生 → 不采信模型给的数字（锚点也不可「记一笔」）。
                kcal = if (tomorrow) 0 else o.optInt("kcal", 0),
                duration = o.optText("duration").take(16),
                why = o.optText("why").ifEmpty { o.optText("whyNow") }.take(MAX_WHY_LEN),
                day = if (tomorrow) PLAN_DAY_TOMORROW else PLAN_DAY_TODAY,
                slot = slot.orEmpty(),
            )
        }
        return out
    }

    /**
     * 归一化「明天」锚点的时段键（模型输出永不可信）。
     *
     * 容忍三种形态，按优先级：
     * 1. 规范 ASCII 键（`morning` / `noon` / `evening` / `train`）；
     * 2. 中文时段名（早 / 午 / 中 / 晚 / 练 / 运动）；
     * 3. 只写了 `time` 时刻 → 按小时折算（< 10 早 / < 15 午 / 其余 晚）。
     *
     * @return [PlanSlot] 之一；**null = 认不出** → 调用方丢弃该条（见 [itemsOf]）。
     */
    private fun resolveSlot(rawSlot: String, rawTime: String): String? {
        val s = rawSlot.lowercase(Locale.US)
        if (PlanSlot.MORNING in s || "早" in rawSlot) return PlanSlot.MORNING
        if (PlanSlot.NOON in s || "午" in rawSlot || "中" in rawSlot) return PlanSlot.NOON
        if (PlanSlot.EVENING in s || "晚" in rawSlot) return PlanSlot.EVENING
        if (PlanSlot.TRAIN in s || "练" in rawSlot || "运动" in rawSlot) return PlanSlot.TRAIN
        val hour = rawTime.take(2).toIntOrNull() ?: return null
        return when {
            hour < 10 -> PlanSlot.MORNING
            hour < 15 -> PlanSlot.NOON
            else -> PlanSlot.EVENING
        }
    }

    /** 安全取文本：缺失 / JSON null 一律返回空串（避免 "null" 字符串混入）。 */
    private fun JSONObject.optText(name: String): String {
        val v = opt(name) ?: return ""
        if (v === JSONObject.NULL) return ""
        return v.toString().trim()
    }

    /** [PlanResult] → `plan_json`（与 prompt 约定 schema 一致，便于重新加载）。 */
    private fun serialize(result: PlanResult): String {
        val root = JSONObject()
        root.put("version", PLAN_JSON_VERSION)
        root.put("generated_at", result.generatedAt)
        root.put("source", result.source)
        val arr = JSONArray()
        for (item in result.items) {
            val o = JSONObject()
            o.put("day", item.day)
            o.put("time", item.time)
            o.put("slot", item.slot)
            o.put("type", item.type)
            o.put("title", item.title)
            o.put("detail", item.detail)
            o.put("kcal", item.kcal)
            o.put("duration", item.duration)
            o.put("why", item.why)
            arr.put(o)
        }
        root.put("items", arr)
        root.put("note", result.note)
        return root.toString()
    }

    /** [PlanResult] → 纯文本（落 `content`，供导出/回退展示）。 */
    private fun renderText(result: PlanResult): String = result.items.joinToString("\n") { item ->
        listOf(item.time, item.title).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** 落 `daily_plans`（复用现成 PlanDao，零 schema 改动）。 */
    private suspend fun persist(date: String, result: PlanResult, targetKcal: Int) {
        runCatching {
            db.planDao().upsertPlan(
                DailyPlanEntity(
                    date = date,
                    targetKcal = targetKcal,
                    planJson = serialize(result),
                    content = renderText(result),
                    generatedAt = result.generatedAt,
                    source = result.source,
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private suspend fun goalInt(metric: String, fallback: Int): Int =
        db.goalDao().getByMetric(metric)?.targetValue?.toInt()?.takeIf { it > 0 } ?: fallback

    /** 日界线小时。**唯一夹取入口** = [dayStartHourOf]（禁止在别处再写 coerceIn）。 */
    private suspend fun dayStartHour(): Int =
        dayStartHourOf(db.settingsDao().get(SettingsKeys.DAY_START))

    /** 主目标名（增重 / 减重 / 保持）。prompt 上下文，措辞与 TodaySummary 一致。 */
    private fun goalName(idx: Int): String = when (idx) {
        1 -> "减重"
        2 -> "保持"
        3 -> "自定义（见下方用户自述）"
        else -> "增重"
    }

    /** 当前本地时间 `HH:mm`（24 小时制两位补零，与 TodaySummary.lines[0] 同口径）。 */
    private fun currentHhmm(): String {
        val t = java.time.LocalTime.now()
        return String.format(Locale.US, "%02d:%02d", t.hour, t.minute)
    }

    /** 若固定时段已过去则用"现在"，保证本地时间轴不排"过去"的整点。 */
    private fun slotOrNow(slot: String, now: String): String = if (slot <= now) now else slot

    private fun sleepSlot(bedTime: String, now: String): String =
        slotOrNow(bedTime.trim().ifBlank { "23:00" }, now)

    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.US, "%.1f", v)
}
