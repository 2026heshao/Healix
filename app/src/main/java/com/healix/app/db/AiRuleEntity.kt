package com.healix.app.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 单条规则的字符上限（v0.3 B4）。
 *
 * 写侧（[com.healix.app.ui.RulesViewModel] / [com.healix.app.ui.RulesFragment]）在落库前
 * 用它截断，避免超长串永久占页并挤占 prompt。
 *
 * ⚠️ 与 `ImportReader` 的 `MAX_RULE_LEN` **刻意不同名**：读侧那道截断是"不信任来源"
 *    的**独立**防御（手改文件 / 别版本写出），语义上就该各自持有一份上限；
 *    且 `pipeline/check_kotlin.py` 的 `check_duplicate_constants` 判据是「同名**且**同值」，
 *    用不同名避免无意义的收敛提示。两者数值同为 500，保持一致。
 */
internal const val RULE_TEXT_MAX_LEN = 500

/**
 * 用户自定义输出偏好（v0.3 B4，决策 D1 = S1 新表）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么是**表**而不是 settings 的 KV
 * ══════════════════════════════════════════════════════════════════════════
 * 规则是**多行 + 有序 + 可启停**的结构（[sortOrder] / [enabled]），KV settings
 * 表达不了；且自由文本不该以"非敏感项"的名义混进 settings 整表被导出。
 *
 * 命名避让：包 `com.healix.app.rules` 已存在（`HealthRules` / `FoodPool` 等），
 * 故实体/DAO 命名 `AiRuleEntity` / `AiRuleDao`，与既有 `HealthRules` 不撞名。
 *
 * ⚠️ 本表**纳入备份**（DR-1 = 是）：写侧 [com.healix.app.ui.ExportWriter] / 读侧
 *    [com.healix.app.ui.ImportReader] 两侧必须同步，字段集 = `{text, enabled,
 *    sort_order, created_at}`（**不导 `id`**，主键是设备本地自增值）。由
 *    `pipeline/check_kotlin.py` 的 `check_backup_parity` 守卫。
 *
 * @property id        本地自增主键（不导出）
 * @property text      规则原文（用户眼里的身份，导入侧按它去重）
 * @property enabled   1 = 生效 / 0 = 停用
 * @property sortOrder 显示顺序（升序）
 * @property createdAt 创建时间戳
 */
@Entity(tableName = "rules")
data class AiRuleEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "enabled") val enabled: Int = 1,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
)

@Dao
interface AiRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: AiRuleEntity): Long

    /** 注入 prompt 用：只取生效规则，按顺序。 */
    @Query("SELECT * FROM rules WHERE enabled = 1 ORDER BY sort_order ASC, id ASC")
    suspend fun listEnabled(): List<AiRuleEntity>

    /** 导出备份用（DR-1）：全部规则（含停用），按顺序。 */
    @Query("SELECT * FROM rules ORDER BY sort_order ASC, id ASC")
    suspend fun listAll(): List<AiRuleEntity>

    /** 规则库页响应式列表。 */
    @Query("SELECT * FROM rules ORDER BY sort_order ASC, id ASC")
    fun observeAll(): Flow<List<AiRuleEntity>>

    @Query("UPDATE rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Int)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: Long)
}
