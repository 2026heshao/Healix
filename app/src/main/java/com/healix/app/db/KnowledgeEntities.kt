package com.healix.app.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 知识库文档（功能清单 2 F12 / 设计规范系统 10.x）。
 *
 * 状态机与 events.parse_status 同构：`pending | parsing | ready | failed`。
 * 「扫描版」「超 20MB」不是新状态，是 `failed`/`ready` 的**带原因文案变体**：
 * - 扫描版（无文字层）：status=ready 且 chunk_count=0 —— 文件本身没坏；
 * - 超 20MB：status=failed 且 last_error=too_large，不发起解析。
 *
 * ⚠️ 比规范 10.8 的"字段要点"多一列 `uri`：规范表格列的是要点而非全集，
 * 而「打开原文」「点此重试」「重启后仍可打开」（10.8 文件选择注）都必须
 * 重新读文件，没有持久化 URI 这三件事都做不了。SAF 权限由
 * `takePersistableUriPermission` 持久化（照 ExportWriter 的 SAF 先例）。
 */
@Entity(tableName = "knowledge_docs")
data class KnowledgeDocEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** 列表展示标题（取文件显示名）。 */
    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    /** SAF 文档 URI（content://），持久化读权限后跨重启可用。 */
    @ColumnInfo(name = "uri")
    val uri: String,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    @ColumnInfo(name = "page_count")
    val pageCount: Int,

    /** pending | parsing | ready | failed */
    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "chunk_count")
    val chunkCount: Int,

    @ColumnInfo(name = "added_at")
    val addedAt: Long,

    /** 失败原因（中性语义，UI 不染红）。too_large = 超 20MB；其余为解析异常摘要。 */
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
)

/** 状态常量（唯一事实来源，其它文件引用这里）。 */
object KnowledgeStatus {
    const val PENDING = "pending"
    const val PARSING = "parsing"
    const val READY = "ready"
    const val FAILED = "failed"

    /** last_error 的保留值：超过 20MB，不发起解析。 */
    const val ERR_TOO_LARGE = "too_large"
}

/**
 * 知识库切片（F12）。检索时全量载入内存打分（20 份 × ~100 片 ≈ 数 MB 内，可接受）。
 */
@Entity(
    tableName = "knowledge_chunks",
    indices = [Index(value = ["doc_id"])],
)
data class KnowledgeChunkEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "doc_id")
    val docId: Long,

    /** 同一文档内的序号（0 起），保证检索拼接时顺序稳定。 */
    @ColumnInfo(name = "seq")
    val seq: Int,

    @ColumnInfo(name = "content")
    val content: String,

    /** 切片起始所在页（1 起），来源标注用。 */
    @ColumnInfo(name = "page_no")
    val pageNo: Int,
)
