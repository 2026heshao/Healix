package com.healix.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration

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
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(HealixConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao
    abstract fun llmCallDao(): LlmCallDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun presetDao(): PresetDao
    abstract fun planDao(): PlanDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        private const val DB_NAME = "healix.db"

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * 所有历史 Migration。v1 尚无历史版本，数组为空；
         * 每次升 version 必须往这里加一个 Migration 对象。
         */
        val MIGRATIONS: Array<Migration> = arrayOf()

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

/** Room 类型转换器。目前实体字段已用基础类型，保留扩展位。 */
class HealixConverters {
    @TypeConverter
    fun fromLongOrNull(value: Long?): Long? = value

    @TypeConverter
    fun toLongOrNull(value: Long?): Long? = value
}
