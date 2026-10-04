package com.healix.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Healix 本地数据库。
 *
 * **Migration 纪律（硬约束，不可协商）**
 * - `exportSchema = true`，schema JSON 提交进仓库（app/schemas/）
 * - 每个版本一个显式 `Migration` 对象
 * - **禁用 `fallbackToDestructiveMigration()`**
 *
 * 理由很硬：数据只有手机本地一份副本。迁移失败静默删库 = 数据全灭。
 * 宁可崩溃报错，也不要静默重建。
 */
@Database(
    entities = [
        EventEntity::class,
        LlmCallEntity::class,
        ChatMessageEntity::class,
        DailyPlanEntity::class,
        DailyReviewEntity::class,
        PresetEntity::class,
        SettingEntity::class,
        GoalEntity::class,
        TrainingPlanEntity::class,
        BodySignalEntity::class,
        ReminderEntity::class,
        KnowledgeDocEntity::class,
        KnowledgeChunkEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
// ⚠️ 这里**故意不加** @TypeConverters。
//
// 曾经加过一个空的 HealixConverters 类，KSP 直接报：
//   Class is referenced as a converter but it does not have any converter methods.
// 也就是说 Room 既不允许「签名重复的转换器」，也不允许「空转换器类」。
//
// 当前所有实体字段（Long/Int/Double/String/Long?）都是 Room 原生支持的类型，
// 不需要任何自定义转换。将来真需要时再加类 + @TypeConverters，
// 并确保每个方法都有独立的参数/返回类型组合。
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun llmCallDao(): LlmCallDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun presetDao(): PresetDao
    abstract fun planDao(): PlanDao
    abstract fun settingsDao(): SettingsDao
    abstract fun goalDao(): GoalDao
    abstract fun trainingPlanDao(): TrainingPlanDao
    abstract fun bodySignalDao(): BodySignalDao
    abstract fun reminderDao(): ReminderDao
    abstract fun knowledgeDocDao(): KnowledgeDocDao
    abstract fun knowledgeChunkDao(): KnowledgeChunkDao

    companion object {
        private const val DB_NAME = "healix.db"

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v1 → v2：新增目标 / 周训练计划 / 身体信号 / 周期性提醒 4 张表。
         *
         * DDL 必须与 Room 依据实体生成的建表语句**逐字段一致**（Room 在
         * 打开数据库时按 PRAGMA table_info 的 name/type/notNull/pk 位置比对，
         * 不一致直接抛 IllegalStateException）。这里的每条 CREATE TABLE /
         * CREATE INDEX 都照 `app/schemas/.../1.json` 的 createSql 格式书写：
         * 标识符用反引号包裹、自增主键写 `INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL`、
         * 可空列不加 NOT NULL、非自增主键在列定义后用 `PRIMARY KEY(...)` 声明。
         *
         * ⚠️ **迁移只做 DDL，不 seed 业务数据**（职责单一：只改结构）。
         * 默认提醒不是结构的一部分，由 `SettingsViewModel.ensureReminderDefaultsIfEmpty()`
         * 在空表时补齐（文案走 `R.string.reminder_*`，避免在 Kotlin 里硬编码中文）。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `metric` TEXT NOT NULL, `target_value` REAL NOT NULL, `start_value` REAL, `deadline` TEXT, `is_primary` INTEGER NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `training_plans` (`week_key` TEXT NOT NULL, `plan_json` TEXT, `content` TEXT, `generated_at` INTEGER NOT NULL, `source` TEXT NOT NULL, PRIMARY KEY(`week_key`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `body_signals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `rule_id` TEXT NOT NULL, `day_key` TEXT NOT NULL, `level` TEXT NOT NULL, `title` TEXT NOT NULL, `detail` TEXT, `acknowledged` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_body_signals_rule_id_day_key` ON `body_signals` (`rule_id`, `day_key`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reminders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `interval_days` INTEGER NOT NULL, `last_done_at` INTEGER, `next_due_at` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v2 → v3：知识库两表（功能清单 2 F12 / 设计规范 10.8）。
         * 纯 DDL，写法照 MIGRATION_1_2 惯例：标识符反引号、自增主键
         * `INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL`、可空列不加 NOT NULL。
         * 建表语句与 `KnowledgeEntities.kt` 的 @Entity 定义逐字段一致。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `knowledge_docs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `file_name` TEXT NOT NULL, `uri` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `page_count` INTEGER NOT NULL, `status` TEXT NOT NULL, `chunk_count` INTEGER NOT NULL, `added_at` INTEGER NOT NULL, `last_error` TEXT)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `knowledge_chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `doc_id` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `content` TEXT NOT NULL, `page_no` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_knowledge_chunks_doc_id` ON `knowledge_chunks` (`doc_id`)"
                )
            }
        }

        /**
         * 所有历史 Migration。v1 之前无历史版本；v2 起每升一次 version
         * 必须往这里加一个 Migration 对象。
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): AppDatabase {
            val builder = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME,
            )
            if (MIGRATIONS.isNotEmpty()) {
                builder.addMigrations(*MIGRATIONS)
            }
            // ⚠️ 此处**故意不调用** fallbackToDestructiveMigration()。
            // 缺 Migration 时 Room 会抛 IllegalStateException —— 这是我们想要的：
            // 崩溃报错可定位，静默删库不可恢复。
            return builder.build()
        }
    }
}
