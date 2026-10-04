package com.healix.app.rules

import android.content.Context
import com.healix.app.HealixApp
import com.healix.app.db.AppDatabase
import com.healix.app.db.SettingsKeys
import com.healix.app.parse.DEFAULT_DAY_START_HOUR
import com.healix.app.parse.dayKeyOf
import com.healix.app.repo.parseFoodsJson
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/**
 * 常吃食物池（功能清单 2 F7：食物可获得性，从记录里自动学）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 口径（清单 §三 F7 原文，一条不放松）
 * ══════════════════════════════════════════════════════════════════════════
 * - 数据源：近 30 天 `events(type=meal).foods`（JSON 数组）逐项计数；
 * - 门槛：出现 ≥ [MIN_COUNT] 次进「常吃清单」——"他吃过 = 他买得到"是最可靠的
 *   可获得性证据；
 * - 输出：按频率降序（同频按名称升序稳定排序），截取 [MAX_ITEMS] 条；
 * - 空判定：清单可能为空（记录太少 / 都没到门槛），调用方必须处理空态，
 *   **不要**拿兜底食物清单冒充——那是编数据。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 不做什么（清单 F7「不做什么」原文）
 * ══════════════════════════════════════════════════════════════════════════
 * 不做库存管理、不上传数据（纯本地 SQLite 统计）、不做营养数据库。
 *
 * 聚合模式对齐 [HealthAggregator]：一次 `listByTypeInRange` 取回区间内全部
 * meal 记录，内存里计数 —— 30 天 × 每天几条 = 百来行，不为它写专门 SQL。
 */
object FoodPool {

    /** 进常吃清单的最低出现次数（清单原文：近 30 天出现 ≥3 次）。 */
    const val MIN_COUNT: Int = 3

    /** 回看窗口：近 30 天（与 HealthAggregator 的 LOOKBACK_DAYS 同口径）。 */
    private const val LOOKBACK_DAYS: Long = 29

    /** 注入 prompt 的上限条数：控制 token，频率最高的前几名足够代表"常吃"。 */
    private const val MAX_ITEMS: Int = 12

    /**
     * 聚合近 30 天食物频次，返回常吃清单（频率降序）。
     * 纯读，不写库；DB 异常返回空清单（调用方走空态，不让统计挂掉主流程）。
     */
    suspend fun topFoods(db: AppDatabase, dayStartHour: Int): List<String> {
        val todayKey = dayKeyOf(System.currentTimeMillis(), dayStartHour)
        val from = runCatching {
            LocalDate.parse(todayKey).minusDays(LOOKBACK_DAYS).toString()
        }.getOrDefault(todayKey)

        val rows = runCatching {
            db.eventDao().listByTypeInRange("meal", from, todayKey)
        }.getOrDefault(emptyList())

        val counts = LinkedHashMap<String, Int>()
        for (row in rows) {
            for (food in parseFoodsJson(row.foods)) {
                val name = food.trim()
                if (name.isEmpty()) continue
                counts[name] = (counts[name] ?: 0) + 1
            }
        }

        return counts.filterValues { it >= MIN_COUNT }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { it.key },
            )
            .take(MAX_ITEMS)
            .map { it.key }
    }

    /**
     * 阻塞式读取常吃清单，供 `ChatEngine.systemPrompt`（非挂起）调用。
     * 与 [com.healix.app.ui.TodaySummary.build] 的 runBlocking 同一先例：
     * 调用点都在 IO 线程，UI 层小查询不做复杂编排。
     */
    fun build(context: Context): List<String> = runBlocking {
        val db = HealixApp.from(context).database
        val dayStart = db.settingsDao().get(SettingsKeys.DAY_START)
            ?.toIntOrNull() ?: DEFAULT_DAY_START_HOUR
        topFoods(db, dayStart)
    }
}
