package com.healix.app.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 对话消息（功能补充 9.2）。
 * 按天分会话（sessionDate），MVP 只有"按天"一个维度，不做会话列表。
 */
@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["session_date", "created_at"])],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** yyyy-MM-dd */
    @ColumnInfo(name = "session_date")
    val sessionDate: String,

    /** user | assistant | tool */
    @ColumnInfo(name = "role")
    val role: String,

    @ColumnInfo(name = "content")
    val content: String = "",

    /** role=tool 时记录工具名 */
    @ColumnInfo(name = "tool_name")
    val toolName: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,
)
