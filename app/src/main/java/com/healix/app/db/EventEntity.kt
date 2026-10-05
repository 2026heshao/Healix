package com.healix.app.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 事件表（定稿 schema）。
 *
 * 与 Python 侧 `pipeline/store.py` 的 SCHEMA_SQL **字段必须一字不差**，
 * 任一侧改动都要同步另一侧并递增 Room version + 新增 Migration。
 */
@Entity(
    tableName = "events",
    indices = [
        Index(value = ["client_event_id"], unique = true),
        Index(value = ["day_key"]),
        Index(value = ["ts"]),
        Index(value = ["type"]),
        Index(value = ["parse_status"]),
    ],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** 幂等键：录入瞬间生成。通知栏 RemoteInput 会重复投递，靠 UNIQUE + IGNORE 去重 */
    @ColumnInfo(name = "client_event_id")
    val clientEventId: String,

    /** 事件发生时间（epoch millis） */
    @ColumnInfo(name = "ts")
    val ts: Long,

    /** 记录日 yyyy-MM-dd，按自定义日界线（默认 4:00）计算 */
    @ColumnInfo(name = "day_key")
    val dayKey: String,

    /** 原始输入。必留 —— 抽错时唯一的后悔药 */
    @ColumnInfo(name = "raw_text")
    val rawText: String,

    /** meal | exercise | body | sleep | illness | other */
    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "time_hint")
    val timeHint: String = "",

    /** JSON 数组字符串 */
    @ColumnInfo(name = "foods")
    val foods: String = "[]",

    @ColumnInfo(name = "exercise")
    val exercise: String = "",

    @ColumnInfo(name = "amount")
    val amount: String = "",

    @ColumnInfo(name = "kcal")
    val kcal: Int = 0,

    @ColumnInfo(name = "symptom")
    val symptom: String = "",

    @ColumnInfo(name = "weight_kg")
    val weightKg: Double = 0.0,

    @ColumnInfo(name = "sleep_h")
    val sleepH: Double = 0.0,

    /** app | notification | preset | ai_suggestion | recent */
    @ColumnInfo(name = "source")
    val source: String = "app",

    /** pending | done | failed —— 离线待处理队列（功能补充 1.1） */
    @ColumnInfo(name = "parse_status")
    val parseStatus: String = "pending",

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    /** user | ai_suggestion */
    @ColumnInfo(name = "origin")
    val origin: String = "user",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0,

    /** 软删除。NULL 表示未删 */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
)
