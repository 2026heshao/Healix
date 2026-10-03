package com.healix.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(goal: GoalEntity): Long
    @Query("SELECT * FROM goals WHERE status = 'active' ORDER BY is_primary DESC, id ASC")
    fun observeActive(): Flow<List<GoalEntity>>
    @Query("SELECT * FROM goals WHERE status = 'active' ORDER BY is_primary DESC, id ASC")
    suspend fun listActive(): List<GoalEntity>
    @Query("SELECT * FROM goals WHERE metric = :metric AND status = 'active' LIMIT 1")
    suspend fun getByMetric(metric: String): GoalEntity?
    @Query("SELECT * FROM goals WHERE metric = :metric AND status = 'active' LIMIT 1")
    fun observeByMetric(metric: String): Flow<GoalEntity?>
    @Query("UPDATE goals SET target_value = :value, updated_at = :now WHERE metric = :metric AND status = 'active'")
    suspend fun setTarget(metric: String, value: Double, now: Long)
    @Query("UPDATE goals SET is_primary = CASE WHEN metric = :metric THEN 1 ELSE 0 END, updated_at = :now WHERE status = 'active'")
    suspend fun setPrimary(metric: String, now: Long)
    @Query("SELECT COUNT(*) FROM goals WHERE status = 'active'") suspend fun countActive(): Int
}

@Dao
interface TrainingPlanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(plan: TrainingPlanEntity)
    @Query("SELECT * FROM training_plans WHERE week_key = :weekKey LIMIT 1")
    fun observeWeek(weekKey: String): Flow<TrainingPlanEntity?>
    @Query("SELECT * FROM training_plans WHERE week_key = :weekKey LIMIT 1")
    suspend fun getWeek(weekKey: String): TrainingPlanEntity?
}

@Dao
interface BodySignalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(signal: BodySignalEntity): Long
    @Query("SELECT * FROM body_signals WHERE day_key = :dayKey ORDER BY id DESC")
    fun observeByDay(dayKey: String): Flow<List<BodySignalEntity>>
    @Query("SELECT * FROM body_signals WHERE day_key = :dayKey ORDER BY id DESC")
    suspend fun listByDay(dayKey: String): List<BodySignalEntity>
    @Query("SELECT * FROM body_signals WHERE acknowledged = 0 AND day_key >= :sinceDay ORDER BY id DESC LIMIT :limit")
    fun observeUnread(sinceDay: String, limit: Int): Flow<List<BodySignalEntity>>
    @Query("SELECT * FROM body_signals WHERE acknowledged = 0 AND day_key >= :sinceDay ORDER BY id DESC LIMIT :limit")
    suspend fun listUnread(sinceDay: String, limit: Int): List<BodySignalEntity>
    @Query("UPDATE body_signals SET acknowledged = 1 WHERE id = :id") suspend fun acknowledge(id: Long)
    @Query("SELECT COUNT(*) FROM body_signals WHERE rule_id = :ruleId AND day_key = :dayKey")
    suspend fun countFor(ruleId: String, dayKey: String): Int
}

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(reminder: ReminderEntity): Long
    @Query("SELECT * FROM reminders WHERE enabled = 1 ORDER BY next_due_at ASC")
    fun observeEnabled(): Flow<List<ReminderEntity>>
    @Query("SELECT * FROM reminders WHERE enabled = 1 ORDER BY next_due_at ASC")
    suspend fun listEnabled(): List<ReminderEntity>
    @Query("SELECT COUNT(*) FROM reminders") suspend fun count(): Int
    @Query("UPDATE reminders SET last_done_at = :doneAt, next_due_at = :nextDueAt WHERE id = :id")
    suspend fun markDone(id: Long, doneAt: Long, nextDueAt: Long)
    @Query("DELETE FROM reminders WHERE id = :id") suspend fun delete(id: Long)
}
