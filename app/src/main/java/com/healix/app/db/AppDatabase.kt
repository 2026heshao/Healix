package com.healix.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
