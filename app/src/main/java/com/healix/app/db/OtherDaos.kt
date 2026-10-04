package com.healix.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LlmCallDao {

    @Insert
    suspend fun insert(call: LlmCallEntity)

    @Query("SELECT * FROM llm_calls ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<LlmCallEntity>>

    @Query("SELECT COUNT(*) FROM llm_calls WHERE ts >= :since")
    suspend fun countSince(since: Long): Int

    /** 按用途计数（配额护栏用，功能补充 1.8 / 9.2） */
    @Query("SELECT COUNT(*) FROM llm_calls WHERE ts >= :since AND purpose = :purpose")
    suspend fun countSinceByPurpose(since: Long, purpose: String): Int

    @Query(
        "SELECT COUNT(*) FROM llm_calls WHERE ts >= :since AND status != 'ok'"
    )
    suspend fun countFailedSince(since: Long): Int

    /** 调试页 P95 计算用：取今日全部延迟样本。 */
    @Query("SELECT latency_ms FROM llm_calls WHERE ts >= :since")
    suspend fun latenciesSince(since: Long): List<Long>
}

@Dao
interface ChatMessageDao {

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Query(
        "SELECT * FROM chat_messages WHERE session_date = :sessionDate ORDER BY created_at ASC"
    )
    fun observeSession(sessionDate: String): Flow<List<ChatMessageEntity>>

    @Query(
        """
        SELECT * FROM (
            SELECT * FROM chat_messages WHERE session_date = :sessionDate
            ORDER BY created_at DESC LIMIT :limit
        ) ORDER BY created_at ASC
        """
    )
    suspend fun recentForContext(sessionDate: String, limit: Int = 16): List<ChatMessageEntity>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE session_date = :sessionDate AND role = 'user'")
    suspend fun countUserTurns(sessionDate: String): Int

    /** 微扩展 B：有消息的会话日期（倒序），聊天页日期切换用。纯查询，无 schema 变更。 */
    @Query("SELECT DISTINCT session_date FROM chat_messages ORDER BY session_date DESC")
    fun observeSessionDates(): Flow<List<String>>
}

@Dao
interface PresetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: PresetEntity): Long

    /** 首页横条：使用次数倒序取前 6 个（功能补充 2.1） */
    @Query("SELECT * FROM presets ORDER BY use_count DESC, last_used_at DESC LIMIT :limit")
    fun observeTop(limit: Int = 6): Flow<List<PresetEntity>>

    @Query("UPDATE presets SET use_count = use_count + 1, last_used_at = :now WHERE id = :id")
    suspend fun bumpUsage(id: Long, now: Long)

    @Query("SELECT * FROM presets ORDER BY name ASC")
    suspend fun listAll(): List<PresetEntity>
}

@Dao
interface PlanDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlan(plan: DailyPlanEntity)

    @Query("SELECT * FROM daily_plans WHERE date = :date LIMIT 1")
    fun observePlan(date: String): Flow<DailyPlanEntity?>

    @Query("SELECT * FROM daily_plans WHERE date = :date LIMIT 1")
    suspend fun getPlan(date: String): DailyPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReview(review: DailyReviewEntity)

    @Query("SELECT * FROM daily_reviews WHERE date = :date LIMIT 1")
    fun observeReview(date: String): Flow<DailyReviewEntity?>
}

@Dao
interface SettingsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: SettingEntity)

    @Query("SELECT value FROM settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM settings WHERE `key` = :key LIMIT 1")
    fun observe(key: String): Flow<String?>

    @Query("SELECT * FROM settings")
    suspend fun listAll(): List<SettingEntity>

    /**
     * 导出备份用：一次拿全部业务数据。
     * ⚠️ apiKey 不在 settings 表里，导出天然不含密钥（功能补充 2.4）。
     */
    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun remove(key: String)
}
