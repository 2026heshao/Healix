package com.healix.app.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 目标（PRD §3.2）。 */
@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    /** weight | training | sleep | habit */
    @ColumnInfo(name = "type") val type: String,
    /** primary | weight_kg | sessions_per_week | train_minutes_per_week | sleep_h | water_ml */
    @ColumnInfo(name = "metric") val metric: String,
    @ColumnInfo(name = "target_value") val targetValue: Double,
    @ColumnInfo(name = "start_value") val startValue: Double? = null,
    @ColumnInfo(name = "deadline") val deadline: String? = null,
    /** 1 = 主目标（决定首页大数字/训练处方以谁为准），0 = 非主目标 */
    @ColumnInfo(name = "is_primary") val isPrimary: Int = 0,
    /** active | achieved | archived */
    @ColumnInfo(name = "status") val status: String = "active",
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
)

/** 周训练计划（PRD §4.2 A2）。一周一行，整周复用 —— 这是控制 AI 成本的关键。 */
@Entity(tableName = "training_plans")
data class TrainingPlanEntity(
    /** ISO 周键 yyyy-Www */
    @PrimaryKey @ColumnInfo(name = "week_key") val weekKey: String,
    @ColumnInfo(name = "plan_json") val planJson: String? = null,
    @ColumnInfo(name = "content") val content: String? = null,
    @ColumnInfo(name = "generated_at") val generatedAt: Long = 0,
    /** ai | fallback */
    @ColumnInfo(name = "source") val source: String = "fallback",
)

/** 身体信号（PRD §7.2）。UNIQUE(rule_id, day_key) 是这张表存在的核心理由。 */
@Entity(
    tableName = "body_signals",
    indices = [Index(value = ["rule_id", "day_key"], unique = true)],
)
data class BodySignalEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    /** H1..H8 / T1..T3 */
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "day_key") val dayKey: String,
    /** info | notice | alert */
    @ColumnInfo(name = "level") val level: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "detail") val detail: String? = null,
    @ColumnInfo(name = "acknowledged") val acknowledged: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
)

/** 周期性事项（PRD §5.4 B3）：体检 / 洗牙 / 配镜 / 疫苗。 */
@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "interval_days") val intervalDays: Int,
    @ColumnInfo(name = "last_done_at") val lastDoneAt: Long? = null,
    @ColumnInfo(name = "next_due_at") val nextDueAt: Long,
    @ColumnInfo(name = "enabled") val enabled: Int = 1,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
)

/** goals.metric 的取值常量（唯一事实来源，其它文件引用这里，不要重定义）。 */
object GoalMetrics {
    const val PRIMARY = "primary"
    const val WEIGHT_KG = "weight_kg"
    const val SESSIONS_PER_WEEK = "sessions_per_week"
    const val TRAIN_MINUTES_PER_WEEK = "train_minutes_per_week"
    const val SLEEP_H = "sleep_h"
    const val WATER_ML = "water_ml"
}

/**
 * `goals.type` 的取值常量（**唯一事实来源**，其它文件引用这里，不要重定义）。
 *
 * 与 [GoalMetrics] 的区别：`metric` 是"目标度量哪一项"（进 DB 的键），
 * `type` 是"这条目标属于哪一类"（用于分组展示 / 语义归类）。
 * ⚠️ 两处曾各写一份 `"goal_mode"` 字面量（`SettingsViewModel` 与 `MainViewModel`），
 * 已收敛到此 —— 与 2026-10-03 键名分裂事故同类，改一处漏一处是最贵的 bug。
 */
object GoalTypes {
    /** 主目标行（`metric = PRIMARY`）的 `type`：`target_value` 编码 0/1/2。 */
    const val GOAL_MODE = "goal_mode"
    const val WEIGHT = "weight"
    const val TRAINING = "training"
    const val SLEEP = "sleep"
    const val HABIT = "habit"
}

/**
 * 目标值的**兜底默认**（唯一事实来源，其它文件引用这里，不要重定义）。
 *
 * 与 [GoalMetrics] 的区别：`GoalMetrics` 是 `goal.metric` 的**键**，
 * 这里是"用户没设过目标时用什么数"的**值**。两者必须分开 ——
 * 键是协议（进 DB、不能改），值是产品默认（可随指南更新）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么必须收敛到一处（2026-10-03 血泪）
 * ══════════════════════════════════════════════════════════════════════════
 * 此前同一组数在 `SettingsViewModel`（`DEFAULT_TRAIN_SESSIONS`）和
 * `StatusDetailViewModel`（`DEFAULT_SESSIONS_PER_WEEK`）各写了一份 ——
 * **值相同、名字不同**。除了日后必然会漂移，还直接引发了 CI #21 的编译失败：
 * `ExerciseSection` / `SleepSection` 是**顶层 data class**，它们的默认参数里
 * 非限定引用了 `StatusDetailViewModel.companion` 的常量，而顶层声明的解析域
 * 里没有那个 companion → `Unresolved reference`。
 *
 * 收敛到顶层 `object` 后，任何位置都能用 `GoalDefaults.X` 限定访问，问题不再复现。
 *
 * 取值依据：膳食指南推荐量（运动 150 分钟/周、饮水 1700ml、睡眠 7.5h）。
 * ⚠️ `HealthRules` 的 `H7`（< 150 分钟/周）**刻意不复用**这里的常量 ——
 * 那是临床判断阈值，不随用户改目标而变；耦合会让用户把目标调小后 H7 就不再预警。
 */
object GoalDefaults {
    /** 每周训练次数（次）。 */
    const val TRAIN_SESSIONS_PER_WEEK: Int = 3

    /** 每周训练时长（分钟）。 */
    const val TRAIN_MINUTES_PER_WEEK: Int = 150

    /** 每日睡眠（小时）。 */
    const val SLEEP_H: Double = 7.5

    /** 每日饮水（毫升）。 */
    const val WATER_ML: Int = 1700

    /** 每日目标摄入（千卡）。用户可在设置页覆盖（`SettingsKeys.TARGET_KCAL`）。 */
    const val TARGET_KCAL: Int = 2500
}
