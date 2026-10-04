package com.healix.app.db

import androidx.room.ColumnInfo
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

    /**
     * 软删除恢复（规范 11.3 左滑删除的「撤销」）：清掉 deleted_at 即回到列表
     * —— ts / day_key 均未动，ORDER BY ts DESC 回插后自然在原位（V3 断言）。
     */
    @Query(
        "UPDATE events SET deleted_at = NULL, updated_at = :now WHERE id = :id"
    )
    suspend fun restore(id: Long, now: Long)

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

    // ---- v4 扩展：跨日范围查询（规则层 / 状态详情页 / 训练页用，全部本地读，不调 AI） ----

    /**
     * 按类型取某一日区间内的全部记录，按日期与时间升序。
     * 规则层聚合睡眠 / 运动 / 体重 / 生病时统一走这里，避免为每条规则各写一个 SQL。
     */
    @Query(
        """
        SELECT * FROM events
        WHERE type = :type AND day_key BETWEEN :dayFrom AND :dayTo AND deleted_at IS NULL
        ORDER BY day_key ASC, ts ASC
        """
    )
    suspend fun listByTypeInRange(type: String, dayFrom: String, dayTo: String): List<EventEntity>

    /** 取某一日区间内的全部记录（不限类型），升序。 */
    @Query(
        """
        SELECT * FROM events
        WHERE day_key BETWEEN :dayFrom AND :dayTo AND deleted_at IS NULL
        ORDER BY day_key ASC, ts ASC
        """
    )
    suspend fun listInRange(dayFrom: String, dayTo: String): List<EventEntity>

    /**
     * 某一日区间内**有记录的日子**（去重升序）。
     * 用途：判断"近 3 日是否每天都有睡眠记录"，不能拿 3 条记录的日期当 3 天用。
     */
    @Query(
        """
        SELECT DISTINCT day_key FROM events
        WHERE day_key BETWEEN :dayFrom AND :dayTo AND deleted_at IS NULL
        ORDER BY day_key ASC
        """
    )
    suspend fun daysWithRecords(dayFrom: String, dayTo: String): List<String>

    /**
     * 体重记录（按日升序）。趋势图与停滞判定都用它 ——
     * 只取 `weight_kg > 0` 的行，0 是兜底值不是真实体重。
     */
    @Query(
        """
        SELECT * FROM events
        WHERE type = 'body' AND weight_kg > 0 AND deleted_at IS NULL
              AND day_key BETWEEN :dayFrom AND :dayTo
        ORDER BY day_key ASC, ts ASC
        """
    )
    suspend fun weightRowsInRange(dayFrom: String, dayTo: String): List<EventEntity>

    /** 某类型在区间内的记录条数（H2 / H4 / T3 判定用）。 */
    @Query(
        """
        SELECT COUNT(*) FROM events
        WHERE type = :type AND day_key BETWEEN :dayFrom AND :dayTo AND deleted_at IS NULL
        """
    )
    suspend fun countByTypeInRange(type: String, dayFrom: String, dayTo: String): Int

    /** 区间内运动消耗合计（训练 Tab 的汇总行用）。 */
    @Query(
        """
        SELECT COALESCE(SUM(kcal), 0) FROM events
        WHERE type = 'exercise' AND day_key BETWEEN :dayFrom AND :dayTo AND deleted_at IS NULL
        """
    )
    suspend fun sumExerciseKcalInRange(dayFrom: String, dayTo: String): Int

    /** 按 client_event_id 删（撤销条用，物理删除：刚写入的记录撤销就是反悔，不留软删痕迹）。 */
    @Query("DELETE FROM events WHERE client_event_id = :clientEventId")
    suspend fun deleteByClientId(clientEventId: String)

    /** 导出备份用：取全部未删除记录（不分页，导出是低频操作）。 */
    @Query("SELECT * FROM events WHERE deleted_at IS NULL ORDER BY ts ASC")
    suspend fun listAll(): List<EventEntity>
}

/**
 * 按天聚合的投影（趋势页用）。
 *
 * ⚠️ 类型必须是 **Long**，不能是 Int：
 *    SQLite 的 `SUM()` 返回 64 位 INTEGER，Room 对 POJO 投影做严格类型校验，
 *    声明成 Int 会编译失败（这一点和标量返回不同 —— 标量返回 Room 允许隐式
 *    窄化，POJO 字段不允许）。
 *    调用方若需要 Int，自行做边界检查后转换，不要让 Room 替我们猜。
 */
data class DailyAggregate(
    @ColumnInfo(name = "day_key") val dayKey: String,
    @ColumnInfo(name = "kcal_in") val kcalIn: Long,
    @ColumnInfo(name = "kcal_out") val kcalOut: Long,
)
