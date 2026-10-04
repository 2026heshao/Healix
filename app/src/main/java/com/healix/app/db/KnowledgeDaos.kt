package com.healix.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDocDao {

    @Insert
    suspend fun insert(doc: KnowledgeDocEntity): Long

    /** 就绪回填：页数 / 切片数一起写（10.3 ④ 原地回填，不闪烁）。 */
    @Query(
        "UPDATE knowledge_docs SET status = :status, page_count = :pageCount, " +
            "chunk_count = :chunkCount, last_error = :lastError WHERE id = :id"
    )
    suspend fun finishParse(id: Long, status: String, pageCount: Int, chunkCount: Int, lastError: String? = null)

    /** 重试前先回到 parsing 态（第二行「解析中」）。 */
    @Query("UPDATE knowledge_docs SET status = :status, last_error = NULL WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("SELECT * FROM knowledge_docs ORDER BY added_at DESC")
    fun observeAll(): Flow<List<KnowledgeDocEntity>>

    @Query("SELECT * FROM knowledge_docs ORDER BY added_at DESC")
    suspend fun listAll(): List<KnowledgeDocEntity>

    @Query("SELECT * FROM knowledge_docs WHERE status = 'ready'")
    suspend fun listReady(): List<KnowledgeDocEntity>

    @Query("SELECT * FROM knowledge_docs WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): KnowledgeDocEntity?

    @Query("SELECT COUNT(*) FROM knowledge_docs")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM knowledge_docs WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface KnowledgeChunkDao {

    @Insert
    suspend fun insertAll(chunks: List<KnowledgeChunkEntity>)

    @Query("SELECT * FROM knowledge_chunks WHERE doc_id = :docId ORDER BY seq ASC")
    suspend fun listForDoc(docId: Long): List<KnowledgeChunkEntity>

    /** 重试解析前清空旧切片，避免双份。 */
    @Query("DELETE FROM knowledge_chunks WHERE doc_id = :docId")
    suspend fun deleteForDoc(docId: Long)
}
