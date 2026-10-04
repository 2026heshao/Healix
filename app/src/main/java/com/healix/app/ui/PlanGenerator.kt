package com.healix.app.ui

import android.content.Context
import com.healix.app.HealixApp
import com.healix.app.db.DailyPlanEntity
import com.healix.app.db.GoalDefaults
import com.healix.app.db.GoalMetrics
import com.healix.app.db.SettingsKeys
import com.healix.app.net.ChatMessage
import com.healix.app.net.ChatRequest
import com.healix.app.net.ChatResult
import com.healix.app.net.ErrKind
import com.healix.app.net.OpenAiCompatProvider
import com.healix.app.parse.DEFAULT_DAY_START_HOUR
import com.healix.app.parse.dayKeyOf
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
 */
const val PROMPT_VER_PLAN: String = "v1"

/**
 * 今日计划 system prompt（设计规范 §4.3 / 增量设计 §4.4）。
 *
 * ⚠️ 本 prompt **不进** `pipeline/contract.py`：`contract.py` 的契约范围由
 *    `check_kotlin.py` 的 `PROMPT_PARITY` 显式枚举（只含抽取链与训练链），
 *    两者都有 Python 侧镜像实现、参与离线回归；计划链在 Python 侧无镜像，
 *    塞进契约文件只增加"逐字节一致"的手工维护负担而无校验价值。
 *    因此 `PROMPT_PLAN` / `PROMPT_VER_PLAN` **只存在于本文件**。
 */
const val PROMPT_PLAN: String = """你是 Healix 的今日计划助手。围绕用户的目标与当下真实数据，把今天（从现在到睡前）要做的事排成一条时间轴。

硬规则（逐条遵守，冲突时序号小的优先）：
1. 只排「现在之后」的时段：已经过去的时刻不再安排（time 必须晚于"现在是 HH:mm"）。
2. 结合「热量缺口」安排饮食：缺口大就把正餐+加餐都排上并给具体数量；已达标则不再堆餐，改排训练或恢复。
3. 若今天有生病记录 → 全天改为恢复安排（清淡饮食 + 补水 + 早睡），不排任何训练。
4. 若今日睡眠不足 6 小时或近 3 日睡眠连续偏低 → 训练降强度（减量或改轻量有氧），并优先排早睡。
5. 有疼痛/不适部位 → 运动建议必须避开相关动作，优先恢复性建议（睡眠、补水）。有忌口/过敏 → 饮食建议必须绕开。
6. 训练只在热量已达标或缺口很小时安排；同一天最多一条训练项，强度参考本周训练计划里"今天"那条。
7. 禁止输出 1RM 估算、力量总分、综合评分或任何形式的打分；禁止给出药物剂量。
8. 每项都要有明确的 time（HH:mm 24 小时制）；按时间从早到晚排序，最多 6 项。

输出要求：
- 只输出 JSON，不要任何解释文字，不要 markdown 代码围栏。
- items 每项含 time / type / title / detail / kcal / duration / why。
- type ∈ {meal, exercise, sleep, habit}；sleep/habit 项 kcal 写 0，duration 可写"——"。
- title 简短（如"晚餐""力量训练""睡觉"）；detail 给具体怎么做（数量/动作/时长）；
  duration 写"约 N 分钟"；why 用一句话说明为什么安排在现在。
- note 一句话总结今天的重点。

输出格式固定为：
{"items": [{"time": "12:30", "type": "meal", "title": "午餐", "detail": "米饭 200g + 鸡胸 150g + 一份绿叶菜", "kcal": 650, "duration": "约 20 分钟", "why": "还差 1200 kcal，先补一半"}, {"time": "18:30", "type": "exercise", "title": "力量训练", "detail": "深蹲 4×8 / 卧推 4×8", "kcal": 200, "duration": "约 30 分钟", "why": "午餐后 6 小时，状态正好"}, {"time": "23:00", "type": "sleep", "title": "睡觉", "detail": "睡前 1 小时放下手机", "kcal": 0, "duration": "——", "why": "近 3 天睡眠偏低，今晚提前睡"}], "note": "今天先补足蛋白，晚上安排一次力量"}
"""

/**
 * 时间轴条目（同时作为 UI 模型）。
 *
 * @property time  "HH:mm" 24 小时制；旧数据可能为空 → 渲染时隐藏时间列。
 * @property type  meal | exercise | sleep | habit（仅 meal/exercise 可「记一笔」）。
 * @property kcal  估算热量；sleep/habit 为 0。
 * @property duration 空串显示「——」。
 * @property why   一句「为什么是现在」；空串不显示。
 */
data class TimelineItem(
    val time: String,
    val type: String,
    val title: String,
    val detail: String,
    val kcal: Int,
    val duration: String = "",
    val why: String = "",
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
    val quotaExhausted: Boolean = false,
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
        /** `plan_json` schema 版本（见增量设计 §3.4）。 */
        private const val PLAN_JSON_VERSION = 2

        /** 主目标「增重」编码（与 HealthAggregator / SettingsViewModel 口径一致）。 */
        private const val PLAN_PRIMARY_GAIN = 0

        /** item.type 缺失时的安全兜底（无「记一笔」，最不具副作用）。 */
        private const val PLAN_TYPE_HABIT = "habit"

        /** `type` 常量（本地兜底条目用；与 prompt 约定一致）。 */
        private const val PLAN_TYPE_MEAL = "meal"
        private const val PLAN_TYPE_EXERCISE = "exercise"
        private const val PLAN_TYPE_SLEEP = "sleep"
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
     * **按时段铺开**成一条时间轴。**不编造具体动作名称、不编热量**（项目纪律：
     * 不制造假数据）—— 食物建议优先取手动清单，物品条件取资源清单。
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

        PlanResult(
            items = buildTimeline(summary, manualFoods, pool, sport, pain, bedTime, now),
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
     * 每次 provider 往返都 [EventRepository.recordPlanCall]（成功记 OK，
     * 失败按 [ErrKind] 记 TIMEOUT / HTTP_ERROR / RETRY_EXHAUSTED）。
     *
     * ⚠️ internal：签名暴露 internal 类型 [TodaySummary]（同 [localTimeline]）。
     */
    internal suspend fun update(todayKey: String, summary: TodaySummary): PlanResult = withContext(Dispatchers.IO) {
        val config = runCatching { app.eventRepository.loadProviderConfig() }.getOrNull()
        if (config == null || !config.isUsable()) {
            // 未配置 / 不可用：不调网。与 AI 失败走同一收口 ——
            // 有缓存保留上一版，无缓存才落本地兜底（不得用兜底覆盖已有缓存）
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
                app.eventRepository.recordPlanCall(
                    model = config.model,
                    attempts = 1,
                    latencyMs = latencyMs,
                    status = EventRepository.STATUS_OK,
                    httpCode = 200,
                    inputTokens = result.usage.inputTokens,
                    outputTokens = result.usage.outputTokens,
                )
                val parsed = parseTimelineJson(result.content)
                if (parsed != null) {
                    val plan = PlanResult(
                        items = parsed.items,
                        note = parsed.note,
                        source = TrainingPlanner.SOURCE_AI,
                        generatedAt = System.currentTimeMillis(),
                    )
                    persist(todayKey, plan, summary.target)
                    return@withContext plan
                }
                // items 为空 / 解析失败 → 视失败（保留缓存或走兜底）
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

    /** 聚合喂给模型的本地事实：目标 + 结构化目标 + 画像 + 今日摘要 + 今日训练 + 近 3 日摘要。 */
    private suspend fun buildUserContext(): String {
        val goalStatement = db.settingsDao().get(SettingsKeys.GOAL_STATEMENT).orEmpty().trim()
        val primaryIdx = db.goalDao().getByMetric(GoalMetrics.PRIMARY)
            ?.targetValue?.toInt() ?: PLAN_PRIMARY_GAIN
        val targetKcal = db.settingsDao().get(SettingsKeys.TARGET_KCAL)
            ?.toIntOrNull()?.takeIf { it > 0 } ?: GoalDefaults.TARGET_KCAL
        val sessions = goalInt(GoalMetrics.SESSIONS_PER_WEEK, GoalDefaults.TRAIN_SESSIONS_PER_WEEK)
        val minutes = goalInt(GoalMetrics.TRAIN_MINUTES_PER_WEEK, GoalDefaults.TRAIN_MINUTES_PER_WEEK)
        val sleepH = db.goalDao().getByMetric(GoalMetrics.SLEEP_H)
            ?.targetValue?.takeIf { it > 0.0 } ?: GoalDefaults.SLEEP_H
        val waterMl = goalInt(GoalMetrics.WATER_ML, GoalDefaults.WATER_ML)

        val summary = TodaySummary.build(appContext)
        val profile = ProfileContext.build(db)
        val todayTraining = todayTrainingLine()
        val recent = recentSummary()

        return buildString {
            appendLine("目标（用户自述）：" + goalStatement.ifEmpty { "未填写" })
            appendLine("主目标：" + goalName(primaryIdx))
            appendLine("每日目标摄入：$targetKcal kcal")
            appendLine("每周训练：$sessions 次 / $minutes 分钟")
            appendLine("睡眠目标：${trim(sleepH)} 小时   饮水目标：$waterMl ml")
            if (profile.isNotBlank()) appendLine(profile)
            summary.lines.forEach { appendLine(it) }
            appendLine("本周训练计划里\"今天\"那条：$todayTraining")
            appendLine("最近记录摘要：$recent")
            appendLine("请按硬规则排出今天从现在到睡前的时间轴。")
        }.trim()
    }

    /** 本周训练计划里「今天」那一条（无则「无」）。 */
    private suspend fun todayTrainingLine(): String {
        val plan = runCatching { TrainingPlanner(appContext).loadOrGenerate(force = false) }.getOrNull()
            ?: return "无"
        val dow = LocalDate.now().dayOfWeek.value
        val day = plan.days.firstOrNull { it.dow == dow && !it.isRest } ?: return "无"
        val line = listOf(day.title, day.itemsLine())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        return line.ifBlank { "无" }
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
                TimelineJson(items, root.optString("note").trim())
            }
            is JSONArray -> {
                val items = itemsOf(root)
                if (items.isEmpty()) return null
                TimelineJson(items, "")
            }
            else -> null
        }
    }

    private fun itemsOf(arr: JSONArray): List<TimelineItem> {
        val out = mutableListOf<TimelineItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optText("title")
            val detail = o.optText("detail")
            if (title.isEmpty() && detail.isEmpty()) continue
            out += TimelineItem(
                time = o.optText("time"),
                type = o.optText("type").ifEmpty { PLAN_TYPE_HABIT },
                title = title,
                detail = detail,
                kcal = o.optInt("kcal", 0),
                duration = o.optText("duration"),
                why = o.optText("why").ifEmpty { o.optText("whyNow") },
            )
        }
        return out
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
            o.put("time", item.time)
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

    private suspend fun dayStartHour(): Int =
        db.settingsDao().get(SettingsKeys.DAY_START)
            ?.toIntOrNull()?.coerceIn(0, 12) ?: DEFAULT_DAY_START_HOUR

    /** 主目标名（增重 / 减重 / 保持）。prompt 上下文，措辞与 TodaySummary 一致。 */
    private fun goalName(idx: Int): String = when (idx) {
        1 -> "减重"
        2 -> "保持"
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
