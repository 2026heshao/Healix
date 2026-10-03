package com.healix.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healix.app.HealixApp
import com.healix.app.db.BodySignalEntity
import com.healix.app.db.EventEntity
import com.healix.app.db.GoalMetrics
import com.healix.app.db.ReminderEntity
import com.healix.app.db.SettingsKeys
import com.healix.app.parse.DEFAULT_DAY_START_HOUR
import com.healix.app.parse.todayDayKey
import com.healix.app.rules.HealthRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 运动段。 */
data class ExerciseSection(
    val sessionsDone: Int = 0,
    val sessionsGoal: Int = DEFAULT_SESSIONS_PER_WEEK,
    val minutesDone: Int = 0,
    val minutesGoal: Int = DEFAULT_TRAIN_MINUTES_PER_WEEK,
    /** 最近训练（近 30 天，倒序，最多 8 条）。 */
    val recent: List<EventEntity> = emptyList(),
)

/** 睡眠段。`values` 为近 7 日每日睡眠合计（升序，只含有记录的日）。 */
data class SleepSection(
    val values: List<Double> = emptyList(),
    val avg: Double = 0.0,
    val goal: Double = DEFAULT_SLEEP_H,
    /** 本周均值 − 上周均值；null = 上周无数据，不显示对比句。 */
    val diffVsPrev: Double? = null,
)

/** 体重段。`values` 为近 30 日体重（升序）。 */
data class WeightSection(
    val values: List<Double> = emptyList(),
    val current: Double = 0.0,
    val goal: Double = 0.0,
    /** 近 14 日首尾差（正 = 涨）；null = 近 14 日不足 2 次测量。 */
    val delta14: Double? = null,
    /** 0 = 算不出（缺身高或缺体重）。 */
    val bmi: Double = 0.0,
    /** 是否曾经记录过体重（含 30 天以前）。 */
    val hasEverRecorded: Boolean = false,
    /** BMI < 18.5 且近 14 日体重停滞/下降 → 追加就医引导（H8 安全条款）。 */
    val showBmiGuide: Boolean = false,
    /** 隐私：`SettingsKeys.HIDE_WEIGHT == "true"` 时隐藏体重数字（§9.7 ④）。 */
    val weightHidden: Boolean = false,
)

/** 病程时间线单行。 */
data class IllnessRow(val index: Int, val date: String, val text: String)

/** 身体段。 */
data class BodySection(
    val signals: List<BodySignalEntity> = emptyList(),
    /** 0 = 无活动病程。 */
    val illnessDay: Int = 0,
    val timeline: List<IllnessRow> = emptyList(),
    val reminders: List<ReminderEntity> = emptyList(),
)

/** 状态页全部内容。 */
data class StatusUi(
    val exercise: ExerciseSection = ExerciseSection(),
    val sleep: SleepSection = SleepSection(),
    val weight: WeightSection = WeightSection(),
    val body: BodySection = BodySection(),
)

/**
 * 状态详情页 ViewModel（设计规范 §9.4）。
 *
 * ⚠️ **本页只读 `body_signals`，不负责写入扫描** —— 规则求值与落库由首页负责，
 * 这里绝不重复实现规则扫描（重复实现会让"同一规则两处判定"漂移）。
 *
 * 全部数据本地读，0 次 AI 调用。窗口边界用与写入端同一条日界线（`dayKeyOf`）。
 */
class StatusDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val container = HealixApp.from(app)
    private val db = container.database

    private val _state = MutableStateFlow(StatusUi())
    val state: StateFlow<StatusUi> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 重新读取（本页无写入，只在进入时读一次即可）。 */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = load()
        }
    }

    private suspend fun load(): StatusUi {
        val dayStart = db.settingsDao().get(SettingsKeys.DAY_START)
            ?.toIntOrNull() ?: DEFAULT_DAY_START_HOUR
        // 与写入端同一条日界线，避免窗口边界漂移
        val today = LocalDate.parse(todayDayKey(dayStart))
        val todayKey = today.toString()
        // 隐私开关：隐藏体重数字（§9.7 ④）。只影响体重段的数字展示，不影响折线与就医引导。
        val hideWeight = db.settingsDao().get(SettingsKeys.HIDE_WEIGHT) == "true"

        return StatusUi(
            exercise = loadExercise(today, todayKey),
            sleep = loadSleep(today, todayKey),
            weight = loadWeight(today, todayKey, hideWeight),
            body = loadBody(today, todayKey),
        )
    }

    // ── 运动 ──────────────────────────────────────────────────────────

    private suspend fun loadExercise(today: LocalDate, todayKey: String): ExerciseSection {
        val sessionsRow = db.goalDao().getByMetric(GoalMetrics.SESSIONS_PER_WEEK)
        val minutesRow = db.goalDao().getByMetric(GoalMetrics.TRAIN_MINUTES_PER_WEEK)
        // 读不到目标 → 膳食指南推荐量 3 次 / 150 分钟兜底
        val sessionsGoal = sessionsRow?.targetValue?.toInt()?.takeIf { it > 0 }
            ?: DEFAULT_SESSIONS_PER_WEEK
        val minutesGoal = minutesRow?.targetValue?.toInt()?.takeIf { it > 0 }
            ?: DEFAULT_TRAIN_MINUTES_PER_WEEK

        // 本周（ISO 周，周一起）
        val monday = today.with(java.time.DayOfWeek.MONDAY).toString()
        val weekRows = db.eventDao().listByTypeInRange(EXERCISE, monday, todayKey)
        // 「次」= 有训练的天数（同一天多条不重复计数）
        val sessionsDone = weekRows.map { it.dayKey }.distinct().size
        val minutesDone = weekRows.sumOf { minutesOf(it) }

        val recent = db.eventDao()
            .listByTypeInRange(EXERCISE, today.minusDays(29).toString(), todayKey)
            .sortedByDescending { it.ts }
            .take(MAX_RECENT_TRAINING)

        return ExerciseSection(
            sessionsDone = sessionsDone,
            sessionsGoal = sessionsGoal,
            minutesDone = minutesDone,
            minutesGoal = minutesGoal,
            recent = recent,
        )
    }

    /**
     * 从 `amount` / `exercise` 文本里榨出分钟数（事件表没有独立的时长字段）。
     * 支持 `30分钟` / `1小时` / `45 min`；榨不出返回 0，**绝不猜**。
     */
    private fun minutesOf(e: EventEntity): Int {
        val text = if (e.amount.isNotBlank()) e.amount else e.exercise
        val match = DURATION_RE.find(text) ?: return 0
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0
        val unit = match.groupValues[2]
        return if (unit == "小时" || unit == "h") (value * 60).toInt() else value.toInt()
    }

    // ── 睡眠 ──────────────────────────────────────────────────────────

    private suspend fun loadSleep(today: LocalDate, todayKey: String): SleepSection {
        val curRows = db.eventDao()
            .listByTypeInRange(SLEEP, today.minusDays(6).toString(), todayKey)
        val prevRows = db.eventDao()
            .listByTypeInRange(SLEEP, today.minusDays(13).toString(), today.minusDays(7).toString())

        val curValues = dailySleep(curRows)
        val prevValues = dailySleep(prevRows)
        val avgCur = if (curValues.isEmpty()) 0.0 else curValues.average()
        val avgPrev = if (prevValues.isEmpty()) 0.0 else prevValues.average()
        val diff = if (curValues.isNotEmpty() && prevValues.isNotEmpty()) avgCur - avgPrev else null

        val goal = db.goalDao().getByMetric(GoalMetrics.SLEEP_H)?.targetValue?.takeIf { it > 0 }
            ?: DEFAULT_SLEEP_H

        return SleepSection(values = curValues, avg = avgCur, goal = goal, diffVsPrev = diff)
    }

    /** 按日合并睡眠（同日多条求和，与抽取链「同类型合并」的口径一致），升序。 */
    private fun dailySleep(rows: List<EventEntity>): List<Double> =
        rows.filter { it.sleepH > 0 }
            .groupBy { it.dayKey }
            .toSortedMap()
            .map { (_, dayRows) -> dayRows.sumOf { it.sleepH } }

    // ── 体重 ──────────────────────────────────────────────────────────

    private suspend fun loadWeight(
        today: LocalDate,
        todayKey: String,
        hidden: Boolean,
    ): WeightSection {
        // 一次取全量（个人级数据量小），用于区分「从未记录」与「窗口内无记录」
        val allRows = db.eventDao().weightRowsInRange(EPOCH_DAY, todayKey)
        val hasEver = allRows.isNotEmpty()
        val values = allRows
            .filter { it.dayKey >= today.minusDays(29).toString() }
            .map { it.weightKg }
        val current = values.lastOrNull() ?: 0.0

        val rows14 = allRows.filter { it.dayKey >= today.minusDays(13).toString() }
        val delta14 = if (rows14.size >= 2) {
            rows14.last().weightKg - rows14.first().weightKg
        } else {
            null
        }

        val goal = db.goalDao().getByMetric(GoalMetrics.WEIGHT_KG)?.targetValue ?: 0.0
        val heightCm = db.settingsDao().get(SettingsKeys.HEIGHT)?.toDoubleOrNull() ?: 0.0
        val bmi = if (current > 0 && heightCm > 0) {
            current / ((heightCm / 100.0) * (heightCm / 100.0))
        } else {
            0.0
        }
        // H8 安全条款：BMI 偏低 + 近 14 日停滞/下降。趋势至少需要 2 次测量才可判定。
        val showGuide = bmi > 0.0 && bmi < BMI_LOW &&
            delta14 != null && delta14 <= 0.0

        return WeightSection(
            values = values,
            current = current,
            goal = goal,
            delta14 = delta14,
            bmi = bmi,
            hasEverRecorded = hasEver,
            showBmiGuide = showGuide,
            weightHidden = hidden,
        )
    }

    // ── 身体 ──────────────────────────────────────────────────────────

    private suspend fun loadBody(today: LocalDate, todayKey: String): BodySection {
        // 未读信号（近 7 日），按规则优先级排序（就医 > 睡眠 > 运动 > 缺口 > 记录）
        val signals = db.bodySignalDao()
            .listUnread(today.minusDays(6).toString(), MAX_SIGNALS)
            .sortedWith(compareBy({ HealthRules.priorityOf(it.ruleId) }, { it.id }))

        val illnessRows = db.eventDao()
            .listByTypeInRange(ILLNESS, today.minusDays(29).toString(), todayKey)

        val (illnessDay, timeline) = buildIllness(today, illnessRows)

        val reminders = db.reminderDao().observeEnabled().first()

        return BodySection(
            signals = signals,
            illnessDay = illnessDay,
            timeline = timeline,
            reminders = reminders,
        )
    }

    /**
     * 从近 30 日不适记录里取**当前这次病程**。
     *
     * 判定（规范未写死，为可复现而定义）：
     * 1. 最近一次不适距今 > 7 天 → 视为已结束，不显示病程段
     * 2. 从最近一次记录所在日向前回溯，遇到没有记录的日就停 → 这一段为本次病程
     * 3. 第 N 天 = 病程首日到今天的自然日天数（含首日与今天）
     *
     * ⚠️ 只呈现用户填写的原文，App 不生成、不评价、不给严重度打分。
     */
    private fun buildIllness(
        today: LocalDate,
        illnessRows: List<EventEntity>,
    ): Pair<Int, List<IllnessRow>> {
        if (illnessRows.isEmpty()) return 0 to emptyList()
        val days = illnessRows.map { it.dayKey }.toSet()
        val latest = LocalDate.parse(illnessRows.last().dayKey)
        if (ChronoUnit.DAYS.between(latest, today) > ILLNESS_ACTIVE_DAYS) {
            return 0 to emptyList()
        }

        var start = latest
        while (days.contains(start.minusDays(1).toString())) {
            start = start.minusDays(1)
        }
        val startKey = start.toString()
        val latestKey = latest.toString()
        val course = illnessRows
            .filter { it.dayKey >= startKey && it.dayKey <= latestKey }
            .sortedBy { it.ts }
        val day = (ChronoUnit.DAYS.between(start, today) + 1).toInt()
        val rows = course.mapIndexed { index, e ->
            IllnessRow(
                index = index + 1,
                date = e.dayKey.substring(5), // yyyy-MM-dd → MM-dd
                text = e.rawText.ifBlank { e.symptom },
            )
        }
        return day to rows
    }

    companion object {
        const val EXERCISE = "exercise"
        const val SLEEP = "sleep"
        const val ILLNESS = "illness"

        /** 膳食指南推荐量的兜底目标。 */
        const val DEFAULT_SESSIONS_PER_WEEK = 3
        const val DEFAULT_TRAIN_MINUTES_PER_WEEK = 150
        const val DEFAULT_SLEEP_H = 7.5

        /** 中国 BMI 偏低阈值（<18.5）。 */
        const val BMI_LOW = 18.5

        private const val MAX_RECENT_TRAINING = 8
        private const val MAX_SIGNALS = 20
        private const val ILLNESS_ACTIVE_DAYS = 7L
        private const val EPOCH_DAY = "1970-01-01"

        private val DURATION_RE = Regex("(\\d+(?:\\.\\d+)?)\\s*(小时|分钟|h|min)")
    }
}
