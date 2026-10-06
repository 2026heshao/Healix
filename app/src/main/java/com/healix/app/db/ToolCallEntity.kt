package com.healix.app.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 工具调用审计（v0.3 B5，§1.2.3）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么是**独立新表**而不是给 `llm_calls` 加列
 * ══════════════════════════════════════════════════════════════════════════
 * `llm_calls` 遵守**配额不变式**：一次 provider 往返**恰好一行**（失败也记、
 * 禁补记，见 `HealthAgent.recordRoundTrip`）。而一次往返里可有 **N 次工具调用**，
 * 把 args/result 塞进单行只能 JSON 硬挤、且污染 `llm_calls` 的语义。
 * 故工具调用单独成表，粒度 = **单次工具调用**。本表**不参与**配额计数。
 *
 * ⚠️ 本表**有意不纳入备份**（DR-1）：工具调用是设备本地诊断/审计痕迹
 *    （名/参/耗时/是否确认），不是跨设备有意义的用户数据；与 `llm_calls` 同类。
 *    见 `ExportWriter` KDoc「刻意不导出」清单。
 *
 * @property id           本地自增主键
 * @property ts           调用时间戳（建索引，供"最近调用"查询）
 * @property callUid      单次调用唯一 id（写工具用它回填 [approved]）
 * @property name         工具名
 * @property args         参数 JSON（截断后存）
 * @property resultDigest 结果摘要（截断后存）
 * @property latencyMs    执行耗时（毫秒）
 * @property needsConfirm 写工具 = 1（需用户确认）；只读 = 0
 * @property approved     写工具：1 = 已确认并落库 / 0 = 已取消；只读 = null（未决）
 */
@Entity(tableName = "tool_calls", indices = [Index(value = ["ts"])])
data class ToolCallEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "ts") val ts: Long,
    @ColumnInfo(name = "call_uid") val callUid: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "args") val args: String,
    @ColumnInfo(name = "result_digest") val resultDigest: String,
    @ColumnInfo(name = "latency_ms") val latencyMs: Long,
    @ColumnInfo(name = "needs_confirm") val needsConfirm: Int,
    @ColumnInfo(name = "approved") val approved: Int? = null,
)

@Dao
interface ToolCallDao {

    @Insert
    suspend fun insert(call: ToolCallEntity)

    /** 写工具"拟稿 → 确认/拒绝"回填（[approved] = 1 / 0）。 */
    @Query("UPDATE tool_calls SET approved = :approved WHERE call_uid = :callUid")
    suspend fun markApproved(callUid: String, approved: Int)

    /** 调试页"最近工具调用"用。 */
    @Query("SELECT * FROM tool_calls ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ToolCallEntity>>
}
