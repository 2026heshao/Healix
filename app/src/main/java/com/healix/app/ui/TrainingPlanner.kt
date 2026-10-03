package com.healix.app.ui

import android.content.Context
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.db.EventEntity
import com.healix.app.db.GoalMetrics
import com.healix.app.db.SettingsKeys
import com.healix.app.db.TrainingPlanEntity
import com.healix.app.net.ChatMessage
import com.healix.app.net.ChatRequest
import com.healix.app.net.ChatResult
import com.healix.app.net.OpenAiCompatProvider
import com.healix.app.parse.DEFAULT_DAY_START_HOUR
import com.healix.app.parse.dayKeyOf
import com.healix.app.parse.loadsLenient
import com.healix.app.repo.ORIGIN_USER
import com.healix.app.repo.SOURCE_APP
import com.healix.app.rules.MuscleRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

/**
 * 周训练计划生成器（PRD §4.2 A2 / 设计规范系统 §9.5）。
 *
 * 职责：拼 prompt（目标 / 本周已练肌群 / 恢复度摘要 / 生病与睡眠降级条件）
 * → 调一次模型 → 解析 JSON 落 [TrainingPlanEntity]；失败时落本地兜底计划
 * （`source = "fallback"`），界面照常可用。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 成本设计（PRD §8.3）：**整周缓存**
 * ══════════════════════════════════════════════════════════════════════════
 * 一周一行（`week_key`），已有本周计划就不再调模型 —— 训练计划从"每天 1 次"
 * 降到"每周 1 次"，这是本模块控制 AI 用量的关键。只有用户点「生成本周计划」
 * （`force = true`）时才真正发起请求。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 失败必须降级可用（PRD 风险表：免费档 429 密集）
 * ══════════════════════════════════════════════════════════════════════════
 * 未配置 provider / 限流 / 超时 / JSON 解析失败，一律走 [localFallback]：
 * 生成一份纯本地、无 AI 参与的结构化计划，动作只按肌群给组次（不编造具体动作、
 * 不编热量 —— 项目纪律：不制造假数据）。界面据此照常渲染列表与「记一笔」。
 */

/**
 * prompt 版本号（PRD §8.1 明确要求**独立版本号**）。
 * 与抽取链的 `PROMPT_VER`（=v2）互不影响；此值只随训练 prompt 迭代递增。
 */
const val PROMPT_VER_TRAINING: String = "v1"

/**
 * 周训练计划 system prompt。
 *
 * ⚠️ 与 `pipeline/contract.py` 的 `PROMPT_TRAINING` **逐字一致**（同 `PROMPT_EXTRACT`
 * 与 `SchemaValidator.kt` 的关系）。Python 侧只做契约/回归校验，Kotlin 侧是实际调用方，
 * 两者改动必须同步。
 */
const val PROMPT_TRAINING: String = """你是 Healix 的训练计划助手。根据用户的目标、本周已练肌群与恢复度，排出本周（周一至周日）7 天的训练安排。

硬规则（逐条遵守，冲突时序号小的优先）：
1. 结合「本周已练肌群」与「恢复度摘要」：同一肌群 48 小时内不重复安排；恢复度低于 50% 的肌群本周内不再安排。
2. 若今日或昨日有生病记录 → 不安排任何训练，整周改为休息 + 补水 + 睡眠的安排。
3. 若今日睡眠不足 6 小时 → 当天训练降低强度：每个动作减 1 组，或改为轻量有氧。
4. 若本周训练次数已达到每周目标 → 多排休息日，不硬凑；绝不允许为了凑够次数而额外加练。
5. 组次区间按目标给：增肌 每组 6-12 次；力量 每组 1-5 次；保持体能 每组 12-20 次。
6. 禁止输出 1RM 估算、力量总分、综合评分或任何形式的打分。

输出要求：
- 只输出 JSON，不要任何解释文字，不要 markdown 代码围栏。
- days 恰好 7 条，dow 依次为 1..7（1=周一，7=周日）。
- 训练日 title 写部位/主题（如「推（胸/肩/三头）」），items 给 2-4 个动作，每个动作含 name / sets / reps，reps 用区间字符串（如 "8-10"）。
- 休息日 title 固定写「休息」，items 为空数组 []。
- focus 一句话概括本周重点；note 一句话提醒用户（可涉及恢复、睡眠或目标）。

输出格式固定为：
{"focus": "...", "days": [{"dow": 1, "title": "推（胸/肩/三头）", "items": [{"name": "杠铃卧推", "sets": 4, "reps": "8-10"}, {"name": "哑铃肩推", "sets": 3, "reps": "10-12"}]}, {"dow": 2, "title": "休息", "items": []}, {"dow": 3, "title": "拉（背/二头）", "items": [{"name": "引体", "sets": 4, "reps": "力竭"}]}, {"dow": 4, "title": "休息", "items": []}, {"dow": 5, "title": "腿", "items": [{"name": "深蹲", "sets": 4, "reps": "6-8"}]}, {"dow": 6, "title": "核心 + 有氧 20 分钟", "items": [{"name": "平板支撑", "sets": 3, "reps": "60秒"}]}, {"dow": 7, "title": "休息", "items": []}], "note": "本周重点：卧推比上周加 2.5kg"}
"""

/** 一个动作。`reps` 用区间字符串（如 "8-10" / "力竭"）。 */
data class TrainingItem(
    val name: String,
    val sets: Int,
    val reps: String,
)

/** 一天安排。休息日 `items` 为空、`isRest = true`。 */
data class TrainingDay(
    val dow: Int,
    val title: String,
    val items: List<TrainingItem>,
    val isRest: Boolean,
) {
    /** 人类可读动作串，例：`杠铃卧推 4×8-10 · 哑铃肩推 3×10-12`。 */
    fun itemsLine(): String =
        items.joinToString(" · ") { "${it.name} ${it.sets}×${it.reps}" }
}

/** 一周计划。`source ∈ {ai, fallback}`。 */
data class TrainingPlan(
    val focus: String,
    val days: List<TrainingDay>,
    val note: String,
    val source: String,
)

class TrainingPlanner(context: Context) {

    private val app: HealixApp = HealixApp.from(context)
    private val appContext: Context = context.applicationContext
    private val db = app.database

    companion object {
        const val SOURCE_AI = "ai"
        const val SOURCE_FALLBACK = "fallback"

        /** 每周训练次数兜底目标（与设置页默认值一致，PRD §3.1）。 */
        const val DEFAULT_SESSIONS = 3

        /** 主目标索引：0=增重，1=减重，其它=保持。与 HealthAggregator 口径一致。 */
        private const val GOAL_GAIN = 0
        private const val GOAL_LOSS = 1
    }

    // ------------------------------------------------------------------
    // 读：整周缓存
    // ------------------------------------------------------------------

    /**
     * 取本周计划。
     *
     * - `force = false`：**只读缓存**。命中返回计划；未命中返回 null（调用方显示空态），
     *   **绝不触发模型调用** —— 自动生成会违背"每周 1 次"的成本纪律。
     * - `force = true`：重新生成（用户点了「生成本周计划」/「重试」）。
     */
    suspend fun loadOrGenerate(force: Boolean): TrainingPlan? = withContext(Dispatchers.IO) {
        val key = weekKey()
        if (!force) {
            val cached = db.trainingPlanDao().getWeek(key)
            return@withContext cached?.let { parsePlan(it.planJson, it.source) }
        }
        generate(key)
    }

    private suspend fun generate(key: String): TrainingPlan = withContext(Dispatchers.IO) {
        val plan = tryAi() ?: localFallback()
        db.trainingPlanDao().upsert(
            TrainingPlanEntity(
                weekKey = key,
                planJson = serialize(plan),
                content = renderText(plan),
                generatedAt = System.currentTimeMillis(),
                source = plan.source,
            ),
        )
        plan
    }

    // ------------------------------------------------------------------
    // AI 调用（失败返回 null，由调用方降级）
    // ------------------------------------------------------------------

    private suspend fun tryAi(): TrainingPlan? {
        val config = app.eventRepository.loadProviderConfig() ?: return null
        if (!config.isUsable()) return null

        val provider = OpenAiCompatProvider(config)
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(role = "system", content = PROMPT_TRAINING),
                ChatMessage(role = "user", content = buildUserContext()),
            ),
            temperature = 0.4,
            timeoutMs = 20_000L,
            // 免费档 429 密集：重试 2 次即可，不把用户卡在 90 秒的退避链上
            maxRetries = 2,
            retryBaseSeconds = 1.5,
            exponentialBackoff = true,
        )

        return when (val result = provider.chat(request)) {
            is ChatResult.Ok -> parsePlan(result.content, SOURCE_AI)
            is ChatResult.Err -> null
        }
    }

    /** 聚合喂给模型的本地事实（**不做任何推测**，全是库里的真实数据）。 */
    private suspend fun buildUserContext(): String {
        val now = System.currentTimeMillis()
        val dayStart = dayStartHour()
        val today = LocalDate.now()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val todayKey = dayKeyOf(now, dayStart)
        val yesterdayKey = dayKeyOf(now - 86_400_000L, dayStart)

        val weekRows = db.eventDao()
            .listByTypeInRange("exercise", monday.toString(), today.toString())
        val trained = weekRows
            .flatMap { MuscleRecovery.musclesOf(it.rawText, it.exercise) }
            .distinct()
        val recovery = MuscleRecovery.summary(weekRows, now)
        val illnessCount = db.eventDao().countByTypeInRange("illness", yesterdayKey, todayKey)
        val sleepToday = db.eventDao()
            .listByTypeInRange("sleep", todayKey, todayKey)
            .maxOfOrNull { it.sleepH } ?: 0.0

        return buildString {
            appendLine("本周目标：${goalLabel()}")
            appendLine("每周训练次数目标：${sessionsGoal()} 次")
            appendLine("本周已练肌群：${if (trained.isEmpty()) "无" else trained.joinToString("、")}")
            appendLine("各肌群恢复度：$recovery")
            appendLine("今日/昨日生病记录：${if (illnessCount > 0) "有" else "无"}")
            // ⚠️ sleepToday 无记录时是 0.0，直接拼进去会写成「今日睡眠：0 小时」，
            //    模型会当成「用户真的睡了 0 小时」从而生成"你必须多睡"这类
            //    与训练计划无关的噪音建议。与上面 illnessCount 的「有/无」同口径处理。
            //    措辞是给模型看的上下文、不是 UI 文案，故不走 strings.xml。
            appendLine("今日睡眠：${if (sleepToday > 0.0) "${trimNumber(sleepToday)} 小时" else "未记录"}")
            appendLine("请排出本周（周一至周日）7 天训练安排。")
        }
    }

    // ------------------------------------------------------------------
    // 解析 / 序列化
    // ------------------------------------------------------------------

    /**
     * 解析计划 JSON → [TrainingPlan]。
     *
     * 不抛异常：任何异常字段都收敛为安全值（对齐抽取链"模型输出永远不可信"的原则）。
     * 返回 null 表示彻底无法使用，交由调用方降级。
     */
    private fun parsePlan(json: String?, source: String): TrainingPlan? {
        if (json.isNullOrBlank()) return null
        val obj = loadsLenient(json) as? JSONObject ?: return null
        val arr = obj.optJSONArray("days") ?: return null
        val restTitle = appContext.getString(R.string.training_rest)

        val byDow = LinkedHashMap<Int, TrainingDay>()
        for (i in 0 until arr.length()) {
            val dayObj = arr.optJSONObject(i) ?: continue
            val dow = dayObj.optInt("dow", 0)
            if (dow !in 1..7) continue

            val title = dayObj.optString("title").trim().ifEmpty { restTitle }
            val items = mutableListOf<TrainingItem>()
            val itemsArr = dayObj.optJSONArray("items")
            if (itemsArr != null) {
                for (j in 0 until itemsArr.length()) {
                    val itemObj = itemsArr.optJSONObject(j) ?: continue
                    val name = itemObj.optString("name").trim()
                    if (name.isEmpty()) continue
                    items += TrainingItem(
                        name = name,
                        sets = itemObj.optInt("sets", 0),
                        reps = itemObj.optString("reps").trim(),
                    )
                }
            }
            byDow[dow] = TrainingDay(
                dow = dow,
                title = title,
                items = items,
                isRest = items.isEmpty() || title == restTitle,
            )
        }
        if (byDow.isEmpty()) return null

        // 补齐缺失的天为休息日，保证永远是 7 行（周一到周日）
        val days = (1..7).map { dow ->
            byDow[dow] ?: TrainingDay(dow, restTitle, emptyList(), true)
        }
        return TrainingPlan(
            focus = obj.optString("focus").trim(),
            days = days,
            note = obj.optString("note").trim(),
            source = source,
        )
    }

    /** 计划 → plan_json（与 prompt 约定的 schema 一致，便于重新加载）。 */
    private fun serialize(plan: TrainingPlan): String {
        val root = JSONObject()
        root.put("focus", plan.focus)
        val daysArr = JSONArray()
        for (day in plan.days) {
            val dayObj = JSONObject()
            dayObj.put("dow", day.dow)
            dayObj.put("title", day.title)
            val itemsArr = JSONArray()
            for (item in day.items) {
                val itemObj = JSONObject()
                itemObj.put("name", item.name)
                itemObj.put("sets", item.sets)
                itemObj.put("reps", item.reps)
                itemsArr.put(itemObj)
            }
            dayObj.put("items", itemsArr)
            daysArr.put(dayObj)
        }
        root.put("days", daysArr)
        root.put("note", plan.note)
        return root.toString()
    }

    /** 计划 → 纯文本（落 `content`，供导出/回退展示）。 */
    private fun renderText(plan: TrainingPlan): String = plan.days.joinToString("\n") { day ->
        val head = "${dowLabel(day.dow)} ${day.title}"
        if (day.isRest || day.items.isEmpty()) head else "$head\n${day.itemsLine()}"
    }

    // ------------------------------------------------------------------
    // 本地兜底计划（无 AI 可用）
    // ------------------------------------------------------------------

    /**
     * 纯本地降级计划：按目标次数把 6 个肌群轮转进本周。
     * **不编造具体动作名称、不编热量** —— 只给肌群 + 组次区间，
     * 让「记一笔」与周维度仍可用（PRD 风险表要求降级可用）。
     */
    private suspend fun localFallback(): TrainingPlan {
        val restTitle = appContext.getString(R.string.training_rest)
        val sessions = sessionsGoal().coerceIn(1, 6)
        // 训练日优先落在周一/三/五，其次周二/四/六 —— 给恢复留间隔
        val slots = listOf(1, 3, 5, 2, 4, 6)
        val trainingDows = slots.take(sessions).sorted()
        // 用肌群下标而非字面量：文案只从 [MuscleRecovery.MUSCLES] 取，避免硬编码中文
        val muscles = MuscleRecovery.MUSCLES
        val templates = listOf(
            listOf(0, 3, 4), // 胸 / 肩 / 手臂
            listOf(1, 5),    // 背 / 核心
            listOf(2),       // 腿
        )
        val reps = when (goalIndex()) {
            GOAL_GAIN -> "8-12"
            GOAL_LOSS -> "12-15"
            else -> "6-10"
        }

        val days = (1..7).map { dow ->
            val index = trainingDows.indexOf(dow)
            if (index < 0) {
                TrainingDay(dow, restTitle, emptyList(), true)
            } else {
                val groups = templates[index % templates.size].mapNotNull { muscles.getOrNull(it) }
                TrainingDay(
                    dow = dow,
                    title = groups.joinToString(" / "),
                    items = groups.map { TrainingItem(name = it, sets = 3, reps = reps) },
                    isRest = false,
                )
            }
        }
        val focus = goalLabel() + " · " + appContext.getString(R.string.unit_times, sessions)
        return TrainingPlan(
            focus = focus,
            days = days,
            note = appContext.getString(R.string.mode_simplified_local),
            source = SOURCE_FALLBACK,
        )
    }

    // ------------------------------------------------------------------
    // 直写记录（「记一笔」，PRD §15.6：用户陈述触发 → 直接写）
    // ------------------------------------------------------------------

    /**
     * 按计划原文直写一条 `exercise` 记录，返回 `clientEventId`（供撤销条使用）。
     *
     * ⚠️ **与对话页 `propose_log` 的区别（PRD §15.6，别混）**：
     * 这里由用户的**陈述**触发（他自己点的按钮，内容是 App 给的），意图 100% 明确，
     * 因此**不弹确认**，直接写 + 5 秒撤销；对话页的「记一笔」由 AI 的**推测**触发，
     * 必须由人点头。两条路径方向相反，不合并。
     *
     * - `kcal = 0`：**不编热量**（项目纪律：不制造假数据）
     * - `parseStatus = "done"`：内容是 App 给的，不需要 AI 再解析一次
     * - `source = "app"` / `origin = "user"`：来源可追溯
     */
    suspend fun logPlanDay(day: TrainingDay): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dayStart = dayStartHour()
        val clientEventId = UUID.randomUUID().toString()
        val rawText = if (day.items.isEmpty()) {
            "${dowLabel(day.dow)} ${day.title}"
        } else {
            "${dowLabel(day.dow)} ${day.title} · ${day.itemsLine()}"
        }

        db.eventDao().insertIgnore(
            EventEntity(
                clientEventId = clientEventId,
                ts = now,
                dayKey = dayKeyOf(now, dayStart),
                rawText = rawText,
                type = "exercise",
                timeHint = "",
                foods = "[]",
                exercise = day.title,
                amount = day.itemsLine(),
                kcal = 0,
                symptom = "",
                weightKg = 0.0,
                sleepH = 0.0,
                source = SOURCE_APP,
                parseStatus = "done",
                origin = ORIGIN_USER,
                createdAt = now,
                updatedAt = now,
            ),
        )
        clientEventId
    }

    // ------------------------------------------------------------------
    // 汇总：本周已完成哪些天
    // ------------------------------------------------------------------

    /**
     * 本周计划中「已记录」的 dow 集合。
     *
     * 判定：本周区间内存在一条 `exercise` 记录，其 `exercise` 等于该天的 title，
     * 且其 `day_key` 落在该天对应的日期上。用 title 匹配而非新增列 —— DB 层不动。
     */
    suspend fun completedDows(plan: TrainingPlan): Set<Int> = withContext(Dispatchers.IO) {
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sunday = monday.plusDays(6)
        val rows = db.eventDao()
            .listByTypeInRange("exercise", monday.toString(), sunday.toString())

        val done = mutableSetOf<Int>()
        for (row in rows) {
            val date = runCatching { LocalDate.parse(row.dayKey) }.getOrNull() ?: continue
            val dow = date.dayOfWeek.value
            val day = plan.days.firstOrNull { it.dow == dow && !it.isRest } ?: continue
            if (row.exercise == day.title) done += dow
        }
        done
    }

    // ------------------------------------------------------------------
    // 目标 / 周键 / 工具
    // ------------------------------------------------------------------

    /** 主目标展示名（增重 / 减重 / 保持）。 */
    suspend fun goalLabel(): String = appContext.getString(
        when (goalIndex()) {
            GOAL_GAIN -> R.string.goal_gain
            GOAL_LOSS -> R.string.goal_loss
            else -> R.string.goal_keep
        },
    )

    /** 每周训练次数目标（默认 3）。 */
    suspend fun sessionsGoal(): Int =
        db.goalDao().getByMetric(GoalMetrics.SESSIONS_PER_WEEK)
            ?.targetValue?.toInt()?.takeIf { it > 0 } ?: DEFAULT_SESSIONS

    private suspend fun goalIndex(): Int =
        db.goalDao().getByMetric(GoalMetrics.PRIMARY)
            ?.targetValue?.toInt() ?: GOAL_GAIN

    /** 今日 ISO 星期（1=周一 … 7=周日）。 */
    fun todayDow(): Int = LocalDate.now().dayOfWeek.value

    /** ISO 周键 `yyyy-Www`（用 [WeekFields.ISO]，不用 SimpleDateFormat 手拼 —— 跨年周会错）。 */
    fun weekKey(): String = weekKeyOf(LocalDate.now())

    private suspend fun dayStartHour(): Int =
        db.settingsDao().get(SettingsKeys.DAY_START)
            ?.toIntOrNull()?.coerceIn(0, 12) ?: DEFAULT_DAY_START_HOUR

    /** 去掉无意义的小数尾巴：7.0 → "7"，7.5 → "7.5"。 */
    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}

/** ISO 周键 `yyyy-Www`。用 [WeekFields.ISO] 处理跨年周（PRD §4.2 A2）。 */
fun weekKeyOf(date: LocalDate): String {
    val field = WeekFields.ISO
    val year = date.get(field.weekBasedYear())
    val week = date.get(field.weekOfWeekBasedYear())
    return String.format(Locale.US, "%04d-W%02d", year, week)
}

/** 周内某天的本地化短名（"周一"…"周日"）。用 JDK/ICU 的 CLDR 数据，不硬编码中文。 */
fun dowLabel(dow: Int): String = runCatching {
    DayOfWeek.of(dow).getDisplayName(TextStyle.SHORT, Locale.CHINESE)
}.getOrNull().orEmpty().ifEmpty { dow.toString() }
