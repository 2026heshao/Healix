package com.healix.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.healix.app.HealixApp
import com.healix.app.parse.DEFAULT_DAY_START_HOUR
import com.healix.app.parse.dayKeyOf
import kotlinx.coroutines.runBlocking

/**
 * 今日摘要（本地算术，**不调 AI**）。
 *
 * 这是三处地方共用的数据源：
 * - 对话页系统提示里喂的"已知数字"（功能补充 9.2 上下文策略）
 * - 对话页开场白 / 通知副标题的缺口播报
 * - 本地降级回答（1.7）
 *
 * 缺口计算是减法，不需要 AI。这是成本控制的关键（UI 设计方案 8.1）。
 */
internal data class TodaySummary(
    val kcalIn: Int,
    val kcalOut: Int,
    val target: Int,
    val weightKg: Double,
    val sleepH: Double,
    val recordCount: Int,
    val hasIllness: Boolean,
) {
    /** 缺口 = 目标 − 已摄入 + 已消耗 */
    val gap: Int get() = target - kcalIn + kcalOut

    /** 供系统提示使用的几行数字。 */
    val lines: List<String>
        get() = buildList {
            add("- 已摄入 $kcalIn kcal，目标 $target kcal，还差 ${if (gap > 0) gap else 0} kcal")
            add("- 记录条数 $recordCount")
            if (kcalOut > 0) add("- 运动消耗约 $kcalOut kcal")
            if (weightKg > 0) add("- 今日体重 $weightKg kg")
            if (sleepH > 0) add("- 今日睡眠 $sleepH 小时")
            if (hasIllness) add("- 今日记录了生病不适")
            if (recordCount == 0) add("- 今天还没有任何记录")
        }

    companion object {
        const val KEY_TARGET_KCAL = com.healix.app.db.SettingsKeys.TARGET_KCAL
        const val DEFAULT_TARGET_KCAL = 2500

        /**
         * 阻塞式读取当日汇总。
         *
         * 为什么用 runBlocking：调用点都在 IO 线程（ChatEngine 从 ViewModel 的
         * Dispatchers.IO 协程里同步调用）。这是 UI 层小查询，不做复杂编排。
         */
        fun build(context: Context): TodaySummary = runBlocking {
            val app = HealixApp.from(context)
            val db = app.database
            val now = System.currentTimeMillis()
            val dayStart = db.settingsDao().get(com.healix.app.db.SettingsKeys.DAY_START)
                ?.toIntOrNull() ?: DEFAULT_DAY_START_HOUR
            val dayKey = dayKeyOf(now, dayStart)

            val target = db.settingsDao().get(KEY_TARGET_KCAL)?.toIntOrNull()
                ?: DEFAULT_TARGET_KCAL

            val dao = db.eventDao()
            val list = dao.listByDay(dayKey)

            TodaySummary(
                kcalIn = list.filter { it.type == "meal" }.sumOf { it.kcal },
                kcalOut = list.filter { it.type == "exercise" }.sumOf { it.kcal },
                target = target,
                weightKg = list.firstOrNull { it.type == "body" && it.weightKg > 0 }?.weightKg ?: 0.0,
                sleepH = list.firstOrNull { it.type == "sleep" && it.sleepH > 0 }?.sleepH ?: 0.0,
                recordCount = list.size,
                hasIllness = list.any { it.type == "illness" },
            )
        }

        /** 异步版本：不阻塞调用线程（UI 层入口用）。 */
        fun buildAsync(context: Context, onResult: (TodaySummary) -> Unit) {
            val appContext = context.applicationContext
            val handler = Handler(Looper.getMainLooper())
            Thread {
                val s = build(appContext)
                handler.post { onResult(s) }
            }.start()
        }
    }
}
