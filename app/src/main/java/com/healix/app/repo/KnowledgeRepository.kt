package com.healix.app.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.healix.app.HealixApp
import com.healix.app.db.KnowledgeChunkEntity
import com.healix.app.db.KnowledgeDocEntity
import com.healix.app.db.KnowledgeStatus
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 检索命中片段（10.4）：内容 + 来源（文档标题与页码）。
 */
data class KnowledgeHit(
    val docId: Long,
    val docTitle: String,
    val pageNo: Int,
    val content: String,
    val score: Double,
)

/**
 * PDF 知识库仓库（功能清单 2 F12 / 设计规范系统 10.3–10.4）。
 *
 * 职责：上传入列（0 弹窗）→ 本地解析（pdfbox 逐页提文字 → 按段落切片）→
 * 关键词打分检索 → 删除。**PDF 原文件不出设备**，进 prompt 的只有命中片段。
 *
 * 解析纯本地（Dispatchers.IO），飞行模式下全流程可用（QA K7）。
 */
class KnowledgeRepository(private val context: Context) {

    private val db = HealixApp.from(context).database

    /**
     * 入列：**0 弹窗**，选中即插列表顶部（status=pending），返回文档 id。
     * 调用方随后启动 [parse]。
     */
    suspend fun enqueue(uri: Uri): Long = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "document.pdf"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: name
                    size = if (c.isNull(1)) 0L else c.getLong(1)
                }
            }
        val title = name.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { name }

        // >20MB：直接 failed + last_error=too_large，不发起解析（QA K5）
        if (size > MAX_BYTES) {
            return@withContext db.knowledgeDocDao().insert(
                KnowledgeDocEntity(
                    title = title,
                    fileName = name,
                    uri = uri.toString(),
                    sizeBytes = size,
                    pageCount = 0,
                    status = KnowledgeStatus.FAILED,
                    chunkCount = 0,
                    addedAt = System.currentTimeMillis(),
                    lastError = KnowledgeStatus.ERR_TOO_LARGE,
                ),
            )
        }

        // 持久化读权限：重启后「打开原文」「重试」仍可用（10.8）
        try {
            resolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Exception) {
            // 个别 provider 不支持持久化 —— 不阻断入列，本次会话内仍可读
        }

        db.knowledgeDocDao().insert(
            KnowledgeDocEntity(
                title = title,
                fileName = name,
                uri = uri.toString(),
                sizeBytes = size,
                pageCount = 0,
                status = KnowledgeStatus.PENDING,
                chunkCount = 0,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * 解析：页数 → 逐页提文字 → 按段落切片入库。
     * - 空文字层 = status=ready 且 chunk_count=0（扫描版，不是 failed，QA K3）；
     * - 异常 = status=failed + last_error（中性语义，QA K4）。
     */
    suspend fun parse(docId: Long) = withContext(Dispatchers.IO) {
        val doc = db.knowledgeDocDao().getById(docId) ?: return@withContext
        // >20MB 的文档从未发起解析（QA K5）：重试路径也不得绕过这一约束
        if (doc.lastError == KnowledgeStatus.ERR_TOO_LARGE) return@withContext
        db.knowledgeDocDao().setStatus(docId, KnowledgeStatus.PARSING)

        try {
            PDFBoxResourceLoader.init(context.applicationContext)

            val pageTexts = mutableListOf<String>()
            val resolver = context.contentResolver
            resolver.openInputStream(Uri.parse(doc.uri))?.use { input ->
                PDDocument.load(input).use { pdf ->
                    val pages = pdf.numberOfPages
                    for (i in 1..pages) {
                        val stripper = PDFTextStripper().apply {
                            startPage = i
                            endPage = i
                        }
                        pageTexts.add(stripper.getText(pdf))
                    }
                }
            }

            val fullText = pageTexts.joinToString("") { it }
            if (fullText.isBlank()) {
                // 扫描版：文件没问题，只是没有文字层 —— ready + 0 切片
                db.knowledgeDocDao().finishParse(
                    docId, KnowledgeStatus.READY, pageTexts.size, 0, null,
                )
                return@withContext
            }

            val chunks = buildChunks(pageTexts)
            db.knowledgeChunkDao().deleteForDoc(docId)
            db.knowledgeChunkDao().insertAll(
                chunks.mapIndexed { idx, (pageNo, content) ->
                    KnowledgeChunkEntity(
                        docId = docId,
                        seq = idx,
                        content = content,
                        pageNo = pageNo,
                    )
                },
            )
            db.knowledgeDocDao().finishParse(
                docId, KnowledgeStatus.READY, pageTexts.size, chunks.size, null,
            )
        } catch (e: Exception) {
            db.knowledgeDocDao().finishParse(
                docId, KnowledgeStatus.FAILED, 0, 0, e.message?.take(ERROR_MAX) ?: "parse_error",
            )
        }
    }

    /** 删除文档与其全部切片（释放 URI 持久化权限尽力而为）。 */
    suspend fun delete(doc: KnowledgeDocEntity) = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(doc.uri),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Exception) {
            // 权限本来就不在时忽略
        }
        db.knowledgeChunkDao().deleteForDoc(doc.id)
        db.knowledgeDocDao().delete(doc.id)
    }

    /**
     * 关键词打分检索（**不是向量检索**，功能清单 2 非目标已划界）。
     *
     * 问题分词（CJK 2-gram + 英数整词）与切片词重叠计分，只对 status=ready
     * 文档的切片全量内存打分（20 份量级毫秒级）。命中片段按分数取 TopN，
     * 拼接总量 ≤ [maxChars]（≈300 token）；无命中返回空列表（不注入 prompt）。
     */
    suspend fun search(question: String, maxChars: Int = 450): List<KnowledgeHit> =
        withContext(Dispatchers.IO) {
            if (question.isBlank()) return@withContext emptyList()
            val tokens = tokenize(question)
            if (tokens.isEmpty()) return@withContext emptyList()

            val docs = db.knowledgeDocDao().listReady()
            if (docs.isEmpty()) return@withContext emptyList()

            val hits = mutableListOf<KnowledgeHit>()
            for (d in docs) {
                for (c in db.knowledgeChunkDao().listForDoc(d.id)) {
                    val content = c.content.lowercase()
                    var score = 0.0
                    for (t in tokens) {
                        if (content.contains(t)) score += t.length
                    }
                    if (score > 0) {
                        hits.add(KnowledgeHit(d.id, d.title, c.pageNo, c.content, score))
                    }
                }
            }

            // 分数相同按文档新→旧、页码小→大，来源行稳定
            hits.sortWith(
                compareByDescending<KnowledgeHit> { it.score }
                    .thenByDescending { it.docId }
                    .thenBy { it.pageNo },
            )

            val picked = mutableListOf<KnowledgeHit>()
            var total = 0
            for (h in hits) {
                if (total + h.content.length > maxChars) continue
                picked.add(h)
                total += h.content.length
            }
            picked
        }

    // ── 内部 ─────────────────────────────────────────────────────

    /**
     * 分段切片：每片 ≤ [CHUNK_MAX] 汉字，记录起始页码。
     * 段落 = 空行分隔；超长段落硬切，避免单段超限。
     */
    private fun buildChunks(pageTexts: List<String>): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var current = StringBuilder()
        var currentPage = 1

        fun flush() {
            val s = current.toString().trim()
            if (s.isNotEmpty()) out.add(currentPage to s)
            current = StringBuilder()
        }

        pageTexts.forEachIndexed { idx, pageText ->
            val pageNo = idx + 1
            pageText.split(Regex("\n\\s*\n")).forEach { para ->
                val p = para.trim()
                if (p.isEmpty()) return@forEach
                val pieces = if (p.length <= CHUNK_MAX) listOf(p) else p.chunked(CHUNK_MAX)
                for (piece in pieces) {
                    if (current.isNotEmpty() && current.length + piece.length + 1 > CHUNK_MAX) {
                        flush()
                        currentPage = pageNo
                    }
                    if (current.isEmpty()) currentPage = pageNo
                    if (current.isNotEmpty()) current.append('\n')
                    current.append(piece)
                    if (current.length >= CHUNK_MAX) flush()
                }
            }
        }
        flush()
        return out
    }

    /**
     * 检索分词：连续 CJK 取 2-gram（单字保留 1-gram），连续英数取整词小写。
     * 标点/空白一律作为边界。
     */
    private fun tokenize(question: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        val q = question.lowercase()
        while (i < q.length) {
            val ch = q[i]
            when {
                ch.isLetterOrDigit() && ch.code < 0x2E80 -> { // ASCII 词
                    var j = i
                    while (j < q.length && q[j].isLetterOrDigit() && q[j].code < 0x2E80) j++
                    tokens.add(q.substring(i, j))
                    i = j
                }
                ch.code in 0x2E80..0x9FFF -> { // CJK
                    var j = i
                    while (j < q.length && q[j].code in 0x2E80..0x9FFF) j++
                    val run = q.substring(i, j)
                    if (run.length == 1) {
                        tokens.add(run)
                    } else {
                        for (k in 0 until run.length - 1) tokens.add(run.substring(k, k + 2))
                    }
                    i = j
                }
                else -> i++
            }
        }
        return tokens
    }

    companion object {
        /** 上限：>20MB 直接拒收（10.2 超限态）。 */
        const val MAX_BYTES: Long = 20L * 1024 * 1024

        /** 单片上限（汉字），≈300 token 预算内（10.4 ②）。 */
        private const val CHUNK_MAX = 450

        /** last_error 截断长度，防异常消息爆表。 */
        private const val ERROR_MAX = 200
    }
}
