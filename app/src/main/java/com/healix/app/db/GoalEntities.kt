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
