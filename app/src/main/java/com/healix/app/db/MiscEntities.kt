package com.healix.app.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 今日计划（UI 设计方案 8.3）。AI 失败时降级为 fallback 纯文本，不能显示空白。 */
@Entity(tableName = "daily_plans")
data class DailyPlanEntity(
    @PrimaryKey
    @ColumnInfo(name = "date")
    val date: String,

    @ColumnInfo(name = "target_kcal")
    val targetKcal: Int = 0,

    /** AI 输出的结构化建议 JSON */
    @ColumnInfo(name = "plan_json")
    val planJson: String? = null,

    /** 兜底纯文本 */
    @ColumnInfo(name = "content")
    val content: String? = null,

    @ColumnInfo(name = "generated_at")
    val generatedAt: Long = 0,

    /** ai | fallback */
    @ColumnInfo(name = "source")
    val source: String = "fallback",
)

/** 每日复盘（总方案第五节）。 */
@Entity(tableName = "daily_reviews")
data class DailyReviewEntity(
    @PrimaryKey
    @ColumnInfo(name = "date")
    val date: String,

    @ColumnInfo(name = "content")
    val content: String? = null,

    @ColumnInfo(name = "model")
    val model: String? = null,

    @ColumnInfo(name = "generated_at")
    val generatedAt: Long = 0,
)

/**
 * 食物预设一键记录（功能补充 2.1，★最高性价比）。
 * 点一下 = 一条记录，完全不打字、不调 AI。
 */
@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "foods_json")
    val foodsJson: String = "[]",

    @ColumnInfo(name = "kcal")
    val kcal: Int = 0,

    @ColumnInfo(name = "use_count")
    val useCount: Int = 0,

    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,
)

/**
 * 设置（非敏感项）。
 * ⚠️ apiKey **不存这里** —— 明文 SQLite 可被 root / 备份导出读到。
 * 见 security/SecretStore.kt（EncryptedSharedPreferences）。
 */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "value")
    val value: String = "",
)
