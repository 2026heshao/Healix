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
    /**
     * 改某个 metric 的目标值。
     *
     * ⚠️⚠️ **UPDATE 型：`status='active'` 的行不存在时静默 no-op（不报错、返回 void）**。
     * 调用前**必须**先 `ensureActiveGoal(db, metric, now)`（`db/GoalEntities.kt`）——
     * 2026-10-07 的真机 bug「热量目标改成什么都没反应、一直显示 2500」就是漏了这一步：
     * v8 问题 2b 删掉默认目标预置后新装机 `goals` 是空表，而 kcal 的 UI 入口是
     * **固定行**（没有「添加目标」路径可创建它的数据行）。
     */
    @Query("UPDATE goals SET target_value = :value, updated_at = :now WHERE metric = :metric AND status = 'active'")
    suspend fun setTarget(metric: String, value: Double, now: Long)

    /**
     * 把 `metric` 置为主目标（其余清 0）。
     *
     * ⚠️⚠️ 与 [setTarget] 同款：**UPDATE 型，缺行静默 no-op**。写前必须先
     * `ensureActiveGoal(db, metric, now)`（`db/GoalEntities.kt`）—— 否则用户跳过首启引导
     * （新装机 `goals` 是空表）后在设置页选「主目标 = 减重」会什么都没发生。
     */
    @Query("UPDATE goals SET is_primary = CASE WHEN metric = :metric THEN 1 ELSE 0 END, updated_at = :now WHERE status = 'active'")
    suspend fun setPrimary(metric: String, now: Long)
    @Query("SELECT COUNT(*) FROM goals WHERE status = 'active'") suspend fun countActive(): Int
    /** 单指标 active 计数（2026-10-09 主目标自愈判据用）。 */
    @Query("SELECT COUNT(*) FROM goals WHERE metric = :metric AND status = 'active'")
    suspend fun countActiveByMetric(metric: String): Int

    // ── 归档 / 恢复（v8 需求 4）：只改数据行 status，**不碰 schema、不升 version** ──
    //
    // 归档 = `status='archived'`。`observeActive()` / `getByMetric()` / `listActive()`
    // 都带 `status='active'` → 归档项自动从设置页消失，且下游消费方
    // （MainViewModel / PlanGenerator / TrainingPlanner / HealthAggregator）
    // 读 `getByMetric` 拿到 null → 各自回落 `GoalDefaults`，**无需改任何消费方**。

    /** 归档一个指标（active → archived）。仅对当前 active 行生效，重复调用幂等。 */
    @Query("UPDATE goals SET status = 'archived', updated_at = :now WHERE metric = :metric AND status = 'active'")
    suspend fun archiveGoal(metric: String, now: Long)

    /** 恢复一个指标（archived → active）。仅对当前 archived 行生效，重复调用幂等。 */
    @Query("UPDATE goals SET status = 'active', updated_at = :now WHERE metric = :metric AND status = 'archived'")
    suspend fun restoreGoal(metric: String, now: Long)

    /**
     * 按 metric 取**任意状态**的一行（不限 active）。
     * 与 [getByMetric] 的区别：后者只查 active，用于"有没有生效的目标"；
     * 本查询用于「添加目标」时判断"该指标是曾归档过（restore）还是从未创建（insert）"。
     */
    @Query("SELECT * FROM goals WHERE metric = :metric LIMIT 1")
    suspend fun getByMetricAny(metric: String): GoalEntity?

    /** 全部归档目标（「添加目标」弹窗的"可恢复"列表）。 */
    @Query("SELECT * FROM goals WHERE status = 'archived' ORDER BY is_primary DESC, id ASC")
    fun observeArchived(): Flow<List<GoalEntity>>

    /**
     * 导出备份用（v8 T07）：一次拿全部目标，**含 archived 行**。
     *
     * 为什么不复用 [listActive]：归档是"移出目标栏"，不是"删除"（重新添加即恢复）。
     * 只导出 active 的话，用户在新机上重新添加那个目标时历史就断了。
     */
    @Query("SELECT * FROM goals ORDER BY id ASC")
    suspend fun listAll(): List<GoalEntity>
}

@Dao
interface TrainingPlanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(plan: TrainingPlanEntity)
    @Query("SELECT * FROM training_plans WHERE week_key = :weekKey LIMIT 1")
    fun observeWeek(weekKey: String): Flow<TrainingPlanEntity?>
    @Query("SELECT * FROM training_plans WHERE week_key = :weekKey LIMIT 1")
    suspend fun getWeek(weekKey: String): TrainingPlanEntity?

    /** 导出备份用（v8 T07）：全部周计划，按周键升序。 */
    @Query("SELECT * FROM training_plans ORDER BY week_key ASC")
    suspend fun listAll(): List<TrainingPlanEntity>
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

    /**
     * 取某一日区间内的全部身体提示（2026-10-07 P0，`query_warnings` 工具用）。
     *
     * 与 [listUnread] 的差别：**含已确认**的提示（已确认只代表用户看过，事实仍在），
     * 且带 `day_to` 上界 —— 工具是「查某段历史」而不是「看今天的红点」。
     * `day_key` 格式 `yyyy-MM-dd`，区间比较即字典序比较；按日期倒序、同日按 id 倒序。
     */
    @Query(
        "SELECT * FROM body_signals WHERE day_key BETWEEN :dayFrom AND :dayTo " +
            "ORDER BY day_key DESC, id DESC LIMIT :limit",
    )
    suspend fun listInRange(dayFrom: String, dayTo: String, limit: Int): List<BodySignalEntity>
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

    /**
     * 导出备份用（v8 T07）：全部提醒，**含 `enabled = 0` 的行**。
     * 停用是"暂时不想被提醒"，不是删除 —— 只导出 enabled 的会让用户重装后
     * 发现自己停用的事项永久消失。
     */
    @Query("SELECT * FROM reminders ORDER BY id ASC")
    suspend fun listAll(): List<ReminderEntity>
}
