package com.healix.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.healix.app.HealixApp
import com.healix.app.db.EventEntity
import com.healix.app.db.GoalDefaults
import com.healix.app.db.GoalMetrics
import com.healix.app.db.SettingsKeys
import com.healix.app.db.kcalTargetOf
import com.healix.app.parse.dayKeyOf
import com.healix.app.parse.dayStartHourOf
import com.healix.app.rules.HealthAggregator
import kotlinx.coroutines.runBlocking
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 今日摘要（本地算术，**不调 AI**）。
 *
 * 这是三处地方共用的数据源：
 * - 对话页系统提示里喂的"已知数字"（功能补充 9.2 上下文策略）
 * - 对话页开场白 / 通知副标题的缺口播报
 * - 本地降级回答（1.7）
 *
 * 缺口计算是减法，不需要 AI。这是成本控制的关键（UI 设计方案 8.1）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * v4 扩展（PRD §8.2 人格改写）
 * ══════════════════════════════════════════════════════════════════════════
 * AI 的身份从「增重助理」变成「健康助理」，摘要也从 1 维扩到多维。
 * 两条纪律：
 * 1. **只喂"有数据的维度"** —— 空维度不占行，控制 token（PRD §8.2 / §11 风险表）
 * 2. **摘要由本地算术生成，不额外调 AI**
 * 3. ★ **隐私开关同步过滤**（PRD §14.3）：隐藏热量后系统提示里也必须去掉 kcal，
 *    否则 AI 会在回复里把数字说出来，开关等于白做。
 */
internal data class TodaySummary(
    val kcalIn: Int,
    val kcalOut: Int,
    val target: Int,
    val weightKg: Double,
    val sleepH: Double,
    val recordCount: Int,
    val hasIllness: Boolean,
    // ── v4 新增多维字段 ──────────────────────────────────────────────
    /** 本周（ISO 周一到今天）运动记录条数 */
    val exerciseCountThisWeek: Int = 0,
    /** 每周训练次数的目标（来自 `goals` 表，兜底 3 次） */
    val goalSessionsWeek: Int = 3,
    /** 今日是否已练 */
    val trainedToday: Boolean = false,
    /** 近 3 个有记录日的睡眠小时（升序，最多 3 个） */
    val sleepLast3: List<Double> = emptyList(),
    /** 近 14 日体重首尾差（正 = 涨） */
    val weightDelta14: Double = 0.0,
    /** 本次病程第 N 天（0 = 没有进行中的病程） */
    val illnessDay: Int = 0,
    /** 隐私：隐藏热量数字 */
    val hideKcal: Boolean = false,
    /** 隐私：隐藏体重数字 */
    val hideWeight: Boolean = false,
    /** 主目标编码：0 增重 / 1 减重 / 2 保持 / 3 自定义（与设置页约定一致） */
    val primaryGoalIndex: Int = 0,
    /** 自定义主目标文本（primaryGoalIndex == 3 时用；settings.GOAL_STATEMENT 同源） */
    val primaryGoalCustom: String = "",
) {
    /** 缺口 = 目标 − 已摄入 + 已消耗 */
    val gap: Int get() = target - kcalIn + kcalOut

    /** 主目标名，供 system prompt 说明"这个人当前的主要目标是什么"。 */
    val primaryGoalName: String
        get() = when (primaryGoalIndex) {
            1 -> "减重"
            2 -> "保持"
            3 -> primaryGoalCustom.ifBlank { "自定义" }
            else -> "增重"
        }

    /**
     * 供系统提示使用的几行数字。
     *
     * ⚠️ 硬编码中文是**刻意的**：这一段只在 system prompt 里出现，不是用户可见 UI，
     * 项目既有做法（`ChatEngine.systemPrompt`）也是如此。UI 文案一律走 `strings.xml`。
     */
    val lines: List<String>
        get() = buildList {
            // 当前时间（F4）：本地时间 24 小时制两位补零，
            // 让模型知道"现在几点"才能给分时段建议（如深夜免烹饪）
            val nowTime = java.time.LocalTime.now()
            add(
                String.format(
                    java.util.Locale.US,
                    "- 现在是 %02d:%02d",
                    nowTime.hour,
                    nowTime.minute,
                ),
            )
            // 热量三行：隐藏热量时整段省略（不写"已隐藏"，那会被模型当成一种状态去讨论）
            if (!hideKcal) {
                add("- 已摄入 $kcalIn kcal，目标 $target kcal，还差 ${if (gap > 0) gap else 0} kcal")
                if (kcalOut > 0) add("- 运动消耗约 $kcalOut kcal")
            }
            add("- 记录条数 $recordCount")

            // 运动（v4）
            if (exerciseCountThisWeek > 0 || goalSessionsWeek > 0) {
                val today = if (trainedToday) "今天练过了" else "今天还没练"
                add("- 本周运动 $exerciseCountThisWeek/$goalSessionsWeek 次，$today")
            }

            // 睡眠（v4）：近 3 日逐日列出，让模型能看出"连续偏低"
            if (sleepLast3.size >= 2) {
                val seq = sleepLast3.joinToString(" / ") { trim(it) }
                add("- 近 ${sleepLast3.size} 日睡眠 $seq 小时")
            } else if (sleepH > 0) {
                add("- 今日睡眠 $sleepH 小时")
            }

            // 体重（v4）：隐藏体重时整行省略
            if (!hideWeight) {
                if (weightDelta14 > 0.05) {
                    add("- 近 14 日体重 ↑${trim(weightDelta14)} kg")
                } else if (weightDelta14 < -0.05) {
                    add("- 近 14 日体重 ↓${trim(-weightDelta14)} kg")
                } else if (weightKg > 0) {
                    add("- 今日体重 $weightKg kg")
                }
            }

            // 生病（v4）：指出是第几天，模型才知道该不该劝休息
            if (illnessDay > 0) {
                add("- 今日有生病记录，已第 $illnessDay 天")
            }

            if (recordCount == 0) add("- 今天还没有任何记录")
        }

    companion object {
        // 兜底默认值（目标摄入 / 每周训练次数）已收敛到
        // `com.healix.app.db.GoalDefaults` —— 跨文件唯一来源，不要在这里重定义。
        // v8 问题 2a：kcal 目标读 `goals` 表（`kcalTargetOf`），
        // 原 `KEY_TARGET_KCAL` 转发常量随之删除。

        /** 生病记录间隔超过这个天数算新的一次病程（与 HealthAggregator 同口径）。 */
        private const val ILLNESS_GAP_DAYS = 2L

        /**
         * 阻塞式读取当日汇总。
         *
         * 为什么用 runBlocking：调用点都在 IO 线程（ChatEngine 从 ViewModel 的
         * Dispatchers.IO 协程里同步调用）。这是 UI 层小查询，不做复杂编排。
         */
        fun build(context: Context): TodaySummary = runBlocking { buildSuspending(context) }

        /** 异步版本：不阻塞调用线程（UI 层入口用）。 */
        fun buildAsync(context: Context, onResult: (TodaySummary) -> Unit) {
            val appContext = context.applicationContext
            val handler = Handler(Looper.getMainLooper())
            Thread {
                val s = build(appContext)
                handler.post { onResult(s) }
            }.start()
        }

        private suspend fun buildSuspending(context: Context): TodaySummary {
            val db = HealixApp.from(context).database
            val dao = db.eventDao()
            val now = System.currentTimeMillis()
            // 日界线走唯一入口 dayStartHourOf（§1 收口）。
            val dayStart = dayStartHourOf(db.settingsDao().get(SettingsKeys.DAY_START))
            val dayKey = dayKeyOf(now, dayStart)
            val today = LocalDate.parse(dayKey)

            // v8 问题 2a：kcal 目标改读 `goals` 表（唯一入口 kcalTargetOf）。
            val target = kcalTargetOf(db)

            // 多维聚合直接复用规则层的聚合器 —— 一处口径，避免摘要与预警两套算法漂移
            val snap = HealthAggregator.snapshot(db, dayStart, target)

            val list = dao.listByDay(dayKey)
            val todayExercise = list.count { it.type == "exercise" }
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
            val weekExercise = runCatching {
                dao.countByTypeInRange("exercise", monday, dayKey)
            }.getOrDefault(0)

            val goalSessions = db.goalDao()
                .getByMetric(GoalMetrics.SESSIONS_PER_WEEK)
                ?.targetValue?.toInt() ?: GoalDefaults.TRAIN_SESSIONS_PER_WEEK

            val hideKcal = db.settingsDao().get(SettingsKeys.HIDE_KCAL) == "true"
            val hideWeight = db.settingsDao().get(SettingsKeys.HIDE_WEIGHT) == "true"

            return TodaySummary(
                kcalIn = list.filter { it.type == "meal" }.sumOf { it.kcal },
                kcalOut = list.filter { it.type == "exercise" }.sumOf { it.kcal },
                target = target,
                weightKg = list.firstOrNull { it.type == "body" && it.weightKg > 0 }?.weightKg ?: 0.0,
                sleepH = list.firstOrNull { it.type == "sleep" && it.sleepH > 0 }?.sleepH ?: 0.0,
                recordCount = list.size,
                hasIllness = list.any { it.type == "illness" },
                exerciseCountThisWeek = weekExercise,
                goalSessionsWeek = goalSessions,
                trainedToday = todayExercise > 0,
                sleepLast3 = snap.sleepLast3,
                weightDelta14 = snap.weightDelta14,
                illnessDay = illnessDayOf(dao, dayKey, today),
                hideKcal = hideKcal,
                hideWeight = hideWeight,
                primaryGoalIndex = db.goalDao()
                    .getByMetric(GoalMetrics.PRIMARY)
                    ?.targetValue?.toInt() ?: 0,
                primaryGoalCustom = db.settingsDao()
                    .get(SettingsKeys.GOAL_STATEMENT)
                    .orEmpty()
                    .trim(),
            )
        }

        /**
         * 本次病程第 N 天：从最近一条生病记录往前数，相邻记录日间隔 ≤
         * [ILLNESS_GAP_DAYS] 视为同一次病程；今天/昨天没有记录则视为无进行中病程。
         */
        private suspend fun illnessDayOf(
            dao: com.healix.app.db.EventDao,
            dayKey: String,
            today: LocalDate,
        ): Int {
            val days = runCatching {
                dao.listByTypeInRange(
                    "illness",
                    today.minusDays(30).toString(),
                    dayKey,
                )
            }.getOrDefault(emptyList<EventEntity>())
                .map { it.dayKey }
                .distinct()
                .sorted()
            if (days.isEmpty()) return 0

            val last = LocalDate.parse(days.last())
            // 最近一次生病记录离今天超过 2 天 → 不是"进行中"
            if (ChronoUnit.DAYS.between(last, today) > ILLNESS_GAP_DAYS) return 0

            var count = 1
            var cursor = last
            for (d in days.dropLast(1).asReversed()) {
                val cur = LocalDate.parse(d)
                if (ChronoUnit.DAYS.between(cur, cursor) > ILLNESS_GAP_DAYS) break
                count++
                cursor = cur
            }
            return count
        }

        private fun trim(v: Double): String =
            if (v == v.toLong().toDouble()) {
                v.toLong().toString()
            } else {
                String.format(java.util.Locale.US, "%.1f", v)
            }
    }
}
