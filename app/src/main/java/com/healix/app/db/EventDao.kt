package com.healix.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    /**
     * 插入。`OnConflictStrategy.IGNORE` + UNIQUE(client_event_id) 共同实现幂等：
     * 重复投递返回 -1，UI 不报错、不出新行（功能补充 1.3）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(event: EventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(events: List<EventEntity>): List<Long>

    @Update
    suspend fun update(event: EventEntity)

    /** 按 client_event_id 回填 AI 抽取结果（幂等键的另一用处：覆盖而非新增）。 */
    @Query(
        """
        UPDATE events SET
            type = :type, time_hint = :timeHint, foods = :foods, exercise = :exercise,
            amount = :amount, kcal = :kcal, symptom = :symptom,
            weight_kg = :weightKg, sleep_h = :sleepH,
            parse_status = :parseStatus, last_error = :lastError, updated_at = :updatedAt
        WHERE client_event_id = :clientEventId
        """
    )
    suspend fun fillParsed(
        clientEventId: String,
        type: String,
        timeHint: String,
        foods: String,
        exercise: String,
        amount: String,
        kcal: Int,
        symptom: String,
        weightKg: Double,
        sleepH: Double,
        parseStatus: String,
        lastError: String?,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE events SET parse_status = 'failed', retry_count = retry_count + 1,
                          last_error = :error, updated_at = :updatedAt
        WHERE client_event_id = :clientEventId
        """
    )
    suspend fun markFailed(clientEventId: String, error: String, updatedAt: Long)

    @Query("SELECT * FROM events WHERE deleted_at IS NULL ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EventEntity>>

    @Query(
        "SELECT * FROM events WHERE day_key = :dayKey AND deleted_at IS NULL ORDER BY ts DESC"
    )
    fun observeByDay(dayKey: String): Flow<List<EventEntity>>

    @Query(
        "SELECT * FROM events WHERE day_key = :dayKey AND deleted_at IS NULL ORDER BY ts DESC"
    )
    suspend fun listByDay(dayKey: String): List<EventEntity>

    @Query(
        """
        SELECT * FROM events
        WHERE parse_status = 'pending' AND deleted_at IS NULL
        ORDER BY ts ASC LIMIT :limit
        """
    )
    suspend fun listPending(limit: Int = 20): List<EventEntity>

    @Query("SELECT * FROM events WHERE client_event_id = :clientEventId LIMIT 1")
    suspend fun findByClientId(clientEventId: String): EventEntity?

    /** 软删除。30 天后可由清理任务物理删除，避免误删不可恢复。 */
    @Query(
        "UPDATE events SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE id = :id"
    )
    suspend fun softDelete(id: Long, deletedAt: Long)

    @Query("SELECT * FROM events WHERE deleted_at IS NOT NULL AND deleted_at < :before")
    suspend fun listPurgeable(before: Long): List<EventEntity>

    @Query("DELETE FROM events WHERE deleted_at IS NOT NULL AND deleted_at < :before")
    suspend fun purgeDeleted(before: Long)

    // ---- 当日汇总（本地算术，不调 AI） ----

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN type = 'meal' THEN kcal ELSE 0 END), 0)
        FROM events WHERE day_key = :dayKey AND deleted_at IS NULL
        """
    )
    fun observeKcalIn(dayKey: String): Flow<Int>

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN type = 'exercise' THEN kcal ELSE 0 END), 0)
        FROM events WHERE day_key = :dayKey AND deleted_at IS NULL
        """
    )
    fun observeKcalOut(dayKey: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM events WHERE day_key = :dayKey AND deleted_at IS NULL")
    fun observeCountByDay(dayKey: String): Flow<Int>

    @Query(
        """
        SELECT weight_kg FROM events
        WHERE day_key = :dayKey AND deleted_at IS NULL AND type = 'body' AND weight_kg > 0
        ORDER BY ts DESC LIMIT 1
        """
    )
    fun observeLatestWeight(dayKey: String): Flow<Double?>

    @Query(
        """
        SELECT COALESCE(AVG(daily), 0) FROM (
            SELECT SUM(kcal) AS daily FROM events
            WHERE type = 'meal' AND deleted_at IS NULL AND day_key BETWEEN :dayFrom AND :dayTo
            GROUP BY day_key
        )
        """
    )
    suspend fun avgKcalInRange(dayFrom: String, dayTo: String): Double

    @Query(
        """
        SELECT day_key, SUM(CASE WHEN type = 'meal' THEN kcal ELSE 0 END) AS kcal_in,
               SUM(CASE WHEN type = 'exercise' THEN kcal ELSE 0 END) AS kcal_out
        FROM events
        WHERE deleted_at IS NULL AND day_key BETWEEN :dayFrom AND :dayTo
        GROUP BY day_key ORDER BY day_key ASC
        """
    )
    suspend fun dailyAggregate(dayFrom: String, dayTo: String): List<DailyAggregate>

    @Query("SELECT COUNT(*) FROM events WHERE type = 'illness' AND day_key = :dayKey AND deleted_at IS NULL")
    suspend fun countIllness(dayKey: String): Int

    /** 导出备份用：取全部未删除记录（不分页，导出是低频操作）。 */
    @Query("SELECT * FROM events WHERE deleted_at IS NULL ORDER BY ts ASC")
    suspend fun listAll(): List<EventEntity>
}

data class DailyAggregate(
    @androidx.room.ColumnInfo(name = "day_key") val dayKey: String,
    @androidx.room.ColumnInfo(name = "kcal_in") val kcalIn: Int,
    @androidx.room.ColumnInfo(name = "kcal_out") val kcalOut: Int,
)
