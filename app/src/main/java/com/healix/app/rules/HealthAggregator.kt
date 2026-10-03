package com.healix.app.rules

import android.content.Context
import com.healix.app.HealixApp
import com.healix.app.db.AppDatabase
import com.healix.app.db.BodySignalEntity
import com.healix.app.db.EventEntity
import com.healix.app.db.GoalDefaults
import com.healix.app.db.GoalMetrics
import com.healix.app.db.SettingsKeys
import com.healix.app.parse.DEFAULT_DAY_START_HOUR
import com.healix.app.parse.dayKeyOf
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 规则层的**输入聚合器**：把 `events` 表里的流水账算成 [HealthSnapshot]。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么要和 [HealthRules] 分开
 * ══════════════════════════════════════════════════════════════════════════
 * [HealthRules] 是**纯函数**（只依赖入参，不碰 DB、不碰 Android），因此可以离线单测、
 * 也可以被任何页面复用。聚合要做 IO，所以放在这一层。
 * 混在一起的话，规则就没法单测了 —— 而规则恰恰是最需要测的部分。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * ★ 两处已知的估算（诚实标注，不假装精确）
 * ══════════════════════════════════════════════════════════════════════════
 * 1. **运动时长**：`events` 表**没有时长列**（`amount` 是自由文本），
 *    所以 [minutesOf] 先从文本里抓「N 分钟」，抓不到时按
 *    [ASSUMED_MINUTES_PER_SESSION] 估算。这是**估算值**，
 *    规则 `H7` 的「这周运动不到 150 分钟」因此是近似判断。
 *    根治办法是 PRD 的 `A3` 训练日志结构化（`exercise_sets` 表），已列入 backlog。
 * 2. **生病"次数"**：`T3` 要的是"记了几次不适"，不是"记了几天"。
 *    一次感冒连续记 4 天不该算 4 次，所以按**病程次数**统计 ——
 *    相邻记录日间隔 > 2 天视为新的一次（[ILLNESS_NEW_EPISODE_GAP_DAYS]）。
 *
 * 这两个常数都写死在这里并附依据，**不要散落到规则里**。
 */
object HealthAggregator {

    /**
     * 抓不到时长时的兜底估算：一次运动记录按 30 分钟算。
     * ⚠️ 这是估算，不是事实。取值理由：`用户记一笔「晚上跑了五公里」这类短句时，
     * 常见的单次运动时长落在 20–40 分钟区间，取中值 30。
     */
    const val ASSUMED_MINUTES_PER_SESSION: Int = 30

    /** 生病记录间隔超过这个天数，算作新的一次病程。 */
    const val ILLNESS_NEW_EPISODE_GAP_DAYS: Long = 2

    /** 聚合回看窗口（天）。取 30 是为了同时覆盖 T3 的「近 30 日」。 */
    private const val LOOKBACK_DAYS: Long = 29

    // 兜底目标摄入已收敛到 `GoalDefaults.TARGET_KCAL`（跨文件唯一来源）。

    /**
     * 从 DB 聚合出规则层需要的全部输入。**纯读，不写任何东西。**
     *
     * 一次 `listInRange` 取回近 30 日全部记录再在内存里分组 ——
     * 30 天 × 每天几条 = 百来行，本地过滤成本远低于写 8 个专门的 SQL，
     * 也避免为每条规则各维护一个 DAO 方法。日记录量上到千级再考虑下推 SQL。
     */
    suspend fun snapshot(
        db: AppDatabase,
        dayStartHour: Int,
        targetKcal: Int,
    ): HealthSnapshot {
        val now = System.currentTimeMillis()
        val todayKey = dayKeyOf(now, dayStartHour)
        val today = LocalDate.parse(todayKey)

        val rows = db.eventDao()
            .listInRange(today.minusDays(LOOKBACK_DAYS).toString(), todayKey)
            .filter { it.deletedAt == null }

        // 「近 3 日」= 今天 + 前两天，升序。必须按**日期**取而不是"最近 3 条记录"——
        // 否则漏记一天也会被当成"连读 3 天"，规则 H1/H4/H5 全部失真。
        val last3 = (2 downTo 0).map { today.minusDays(it.toLong()).toString() }
        val last7From = today.minusDays(6).toString()
        val prev7From = today.minusDays(13).toString()
        // ⚠️ `last7From` 是**字符串**，减法必须在转字符串之前做 ——
        //    `last7From.minusDays(1)` 是 `String.minusDays`，String 上没这个方法，
        //    编译期直接 `Unresolved reference 'minusDays'`（CI #21 就栽在这行）。
        //    单独算一个 prev7To，别在字符串上做日期运算。
        val prev7To = today.minusDays(7).toString()
        val last14From = today.minusDays(13).toString()

        val byDay: Map<String, List<EventEntity>> = rows.groupBy { it.dayKey }

        fun mealKcalOf(day: String): Int =
            byDay[day].orEmpty().filter { it.type == "meal" }.sumOf { it.kcal }

        fun exerciseKcalOf(day: String): Int =
            byDay[day].orEmpty().filter { it.type == "exercise" }.sumOf { it.kcal }

        val sleepLast3 = last3.mapNotNull { day ->
            byDay[day].orEmpty().firstOrNull { it.type == "sleep" && it.sleepH > 0 }?.sleepH
        }

        val mealsLast3 = last3.map { day ->
            byDay[day].orEmpty().count { it.type == "meal" }
        }

        val gapLast3 = last3.map { day ->
            targetKcal - mealKcalOf(day) + exerciseKcalOf(day)
        }

        val weights14 = dailyLastWeights(rows, last14From, todayKey)
        val weights7 = dailyLastWeights(rows, last7From, todayKey)

        // 近 7 日 / 前 7 日必须用**互斥的日期区间**取，不能靠"取前 N 个"去切 ——
        // 漏记的日子不占位，切片会串档。
        val sleepCur7 = dailySleepHours(rows, last7From, todayKey)
        val sleepPrev7 = dailySleepHours(rows, prev7From, prev7To)

        val exerciseRows7 = rows.filter { it.type == "exercise" && it.dayKey >= last7From }

        val latestWeight = weights14.lastOrNull() ?: 0.0
        val heightCm = db.settingsDao().get(SettingsKeys.HEIGHT)?.toDoubleOrNull() ?: 0.0

        val primaryGoalIndex = db.goalDao()
            .getByMetric(GoalMetrics.PRIMARY)
            ?.targetValue
            ?.toInt() ?: PRIMARY_GOAL_GAIN

        return HealthSnapshot(
            dayKey = todayKey,
            nowMinutes = LocalTime.now().let { it.hour * 60 + it.minute },
            sleepLast3 = sleepLast3,
            exerciseCount7 = exerciseRows7.size,
            exerciseMinutes7 = exerciseRows7.sumOf { minutesOf(it) },
            mealsLast3 = mealsLast3,
            gapLast3 = gapLast3,
            weights14 = weights14,
            weightDelta7 = delta(weights7),
            weightDelta14 = delta(weights14),
            sleepAvgCur7 = sleepCur7.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
            sleepAvgPrev7 = sleepPrev7.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
            isWeightLossGoal = primaryGoalIndex == PRIMARY_GOAL_LOSS,
            bmi = bmiOf(latestWeight, heightCm),
            illnessCount30 = countIllnessEpisodes(rows),
            recordCountToday = byDay[todayKey].orEmpty().size,
        )
    }

    /**
     * 打开 App 时调用一次：聚合 → 求值 → 落 `body_signals`。
     *
     * **`UNIQUE(rule_id, day_key)` 是整套预警机制不变成"每日唠叨"的唯一保障**
     * （PRD §7.2）：没有它，每次 `onResume` 都会重算同一句话，
     * 用户三天就会把提示关掉。这里用 `OnConflictStrategy.IGNORE` 落地这条约束。
     *
     * **本方法 0 次 AI 调用**（PRD §5.1 的成本设计）：免费档模型 429 密集，
     * 预警又每天都要跑，绝不能挂在 AI 上。规则条件与文案全是本地常量。
     *
     * @return 本次命中的信号（已按 priority 升序），供 UI 直接取第一条显示。
     */
    suspend fun scanAndPersist(context: Context): List<HealthSignal> {
        val db = HealixApp.from(context).database
        val dayStart = db.settingsDao().get(SettingsKeys.DAY_START)
            ?.toIntOrNull()?.coerceIn(0, 12) ?: DEFAULT_DAY_START_HOUR
        val target = db.settingsDao().get(SettingsKeys.TARGET_KCAL)
            ?.toIntOrNull() ?: GoalDefaults.TARGET_KCAL

        val snap = snapshot(db, dayStart, target)
        val signals = HealthRules.evaluate(context, snap)

        val now = System.currentTimeMillis()
        for (s in signals) {
            try {
                db.bodySignalDao().insertIgnore(
                    BodySignalEntity(
                        ruleId = s.ruleId,
                        dayKey = snap.dayKey,
                        level = s.level,
                        title = s.shortDisplay,
                        detail = s.fullDisplay,
                        acknowledged = 0,
                        createdAt = now,
                    ),
                )
            } catch (e: Exception) {
                // 信号落库失败不能影响首页渲染 —— 它是提示，不是业务数据
            }
        }
        return signals
    }

    /**
     * 标记为已读（用户进了状态详情页就算看过了）。
     * 已读后首页状态行**必须切回摘要态**，不是永久停留在信号态（规范 §9.2）。
     */
    suspend fun acknowledgeAll(context: Context) {
        val db = HealixApp.from(context).database
        val dayStart = db.settingsDao().get(SettingsKeys.DAY_START)
            ?.toIntOrNull()?.coerceIn(0, 12) ?: DEFAULT_DAY_START_HOUR
        val since = LocalDate.parse(dayKeyOf(System.currentTimeMillis(), dayStart))
            .minusDays(7).toString()
        try {
            db.bodySignalDao()
                .listUnread(since, 50)
                .forEach { db.bodySignalDao().acknowledge(it.id) }
        } catch (e: Exception) {
            // 同上：忽略
        }
    }

    // ------------------------------------------------------------------
    // 内部工具（全部纯函数，无 IO）
    // ------------------------------------------------------------------

    private val MINUTES_RE = Regex("""(\d{1,3}(?:\.\d)?)\s*分钟""")

    /** 从自由文本里抓运动时长（分钟）；抓不到按 [ASSUMED_MINUTES_PER_SESSION] 估算。 */
    private fun minutesOf(e: EventEntity): Int {
        val text = e.amount.ifBlank { e.rawText }
        val m = MINUTES_RE.find(text) ?: MINUTES_RE.find(e.rawText)
        val parsed = m?.groupValues?.get(1)?.toDoubleOrNull()
        return if (parsed != null && parsed > 0) {
            parsed.toInt().coerceIn(1, 600)
        } else {
            ASSUMED_MINUTES_PER_SESSION
        }
    }

    /**
     * 每个有记录的日子取**最后一条**体重（同一天可称多次，取最新）。
     * 区间是闭区间 `[fromDay, toDay]`，升序返回。
     */
    private fun dailyLastWeights(
        rows: List<EventEntity>,
        fromDay: String,
        toDay: String,
    ): List<Double> {
        val byDay = LinkedHashMap<String, Double>()
        rows.filter {
            it.type == "body" && it.weightKg > 0 && it.dayKey >= fromDay && it.dayKey <= toDay
        }.forEach { byDay[it.dayKey] = it.weightKg }
        return byDay.values.toList()
    }

    /** 每个有记录的日子取**最后一条**睡眠时长。闭区间 `[fromDay, toDay]`，升序返回。 */
    private fun dailySleepHours(
        rows: List<EventEntity>,
        fromDay: String,
        toDay: String,
    ): List<Double> {
        val byDay = LinkedHashMap<String, Double>()
        rows.filter {
            it.type == "sleep" && it.sleepH > 0 && it.dayKey >= fromDay && it.dayKey <= toDay
        }.forEach { byDay[it.dayKey] = it.sleepH }
        return byDay.values.toList()
    }

    /** 首尾差。少于 2 个点无法判断趋势 → 返回 0（"没有趋势"而不是"涨了"）。 */
    private fun delta(values: List<Double>): Double =
        if (values.size < 2) 0.0 else values.last() - values.first()

    /**
     * BMI。身高或体重缺失返回 0，**不猜**（0 在规则层表示"算不出"，因此
     * `H8`/体重段的安全条款都不会被误触发）。
     */
    private fun bmiOf(weightKg: Double, heightCm: Double): Double {
        if (weightKg <= 0 || heightCm <= 0) return 0.0
        val h = heightCm / 100.0
        return weightKg / (h * h)
    }

    /**
     * 近 30 日生病**病程**次数（不是记录条数）：
     * 相邻记录日间隔 > [ILLNESS_NEW_EPISODE_GAP_DAYS] 视为新的一次。
     * 理由：一次感冒连续记 4 天不该算 4 次，否则 `T3`（≥3 次）会被一次病程刷爆。
     */
    private fun countIllnessEpisodes(rows: List<EventEntity>): Int {
        val days = rows.filter { it.type == "illness" }
            .map { it.dayKey }
            .distinct()
            .sorted()
        if (days.isEmpty()) return 0

        var episodes = 1
        var prev = LocalDate.parse(days.first())
        for (d in days.drop(1)) {
            val cur = LocalDate.parse(d)
            if (ChronoUnit.DAYS.between(prev, cur) > ILLNESS_NEW_EPISODE_GAP_DAYS) episodes++
            prev = cur
        }
        return episodes
    }

    /** 主目标编码（与 `SettingsViewModel` 的约定一致）：0 增重 / 1 减重 / 2 保持。 */
    private const val PRIMARY_GOAL_GAIN = 0
    private const val PRIMARY_GOAL_LOSS = 1
}
