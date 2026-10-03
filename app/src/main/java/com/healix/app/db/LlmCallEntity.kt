package com.healix.app.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * LLM 调用埋点（功能补充第三章）。
 *
 * `promptVer` 是最关键的一列 —— 没有版本号就无法回答"为什么上周抽得比现在准"。
 */
@Entity(
    tableName = "llm_calls",
    indices = [Index(value = ["ts"])],
)
data class LlmCallEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "ts")
    val ts: Long,

    /** extract | plan | review | ask | agent_loop */
    @ColumnInfo(name = "purpose")
    val purpose: String,

    @ColumnInfo(name = "event_id")
    val eventId: Long? = null,

    @ColumnInfo(name = "model")
    val model: String = "",

    /** ★ prompt 版本号 */
    @ColumnInfo(name = "prompt_ver")
    val promptVer: String = "",

    @ColumnInfo(name = "attempts")
    val attempts: Int = 1,

    @ColumnInfo(name = "latency_ms")
    val latencyMs: Long = 0,

    /** ok | retry_exhausted | schema_invalid | http_error | timeout */
    @ColumnInfo(name = "status")
    val status: String = "",

    @ColumnInfo(name = "http_code")
    val httpCode: Int? = null,

    @ColumnInfo(name = "input_tokens")
    val inputTokens: Int? = null,

    @ColumnInfo(name = "output_tokens")
    val outputTokens: Int? = null,

    /** 错误信息前 200 字，脱敏后 */
    @ColumnInfo(name = "error_head")
    val errorHead: String? = null,
)
