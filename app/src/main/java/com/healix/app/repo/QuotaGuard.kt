package com.healix.app.repo

import android.content.Context
import com.healix.app.HealixApp
import com.healix.app.db.LlmCallDao
import com.healix.app.db.SettingsDao
import com.healix.app.db.SettingsKeys
import com.healix.app.parse.dayKeyOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 调用预算护栏（功能补充 1.8 / 9.2 C5 配额）。
 *
 * 缺口：按钮可被连点。虽然不是 Agent 循环，但"异常 → 连点 → 累计大量调用"存在可能，
 * 且每次等待 1.5 秒以上的体验也差。
 *
 * 设计取舍：**两类配额分开计数**
 * - 抽取（extract）默认 20 次/天 —— 用户主动记录，几乎不会触及
 * - 对话（ask / agent_loop）默认 15 次/天 —— 一次对话可能多次往返，消耗快
 *
 * 分开计的理由：一次对话 = 1–3 次模型往返（工具调用），若和抽取共用配额，
 * 用户聊几句就把当天的记录额度吃掉了，体验断裂。
 *
 * 计数基准：本地自然日（按 [dayKeyOf] 的日界线），不是 UTC 日 —— 否则
 * 凌晨记一笔会算到"明天"的配额里。
 */
class QuotaGuard(private val context: Context) {

    private val llmCallDao: LlmCallDao = HealixApp.from(context).database.llmCallDao()
    private val settingsDao: SettingsDao = HealixApp.from(context).database.settingsDao()

    companion object {
        /**
         * settings 键名。
         *
         * ⚠️ 2026-10-03 修复：过去这里是 `quota_daily_call_limit` / `quota_daily_chat_limit`，
         * 而设置页写入的是 `daily_quota` / `chat_quota` —— 键名分裂导致
         * **用户在设置页改的配额从未生效过**（永远回落默认值 20 / 15）。
         * 现在统一引用 [SettingsKeys]。
         */
        const val KEY_DAILY_CALL_LIMIT = SettingsKeys.EXTRACT_QUOTA
        const val KEY_DAILY_CHAT_LIMIT = SettingsKeys.CHAT_QUOTA

        /** 默认值（功能补充 1.8：每日 AI 调用上限 20；9.2：对话 15） */
        const val DEFAULT_DAILY_CALL_LIMIT = 20
        const val DEFAULT_DAILY_CHAT_LIMIT = 15

        /** 归入"抽取"配额的 purpose */
        private val CALL_PURPOSES = listOf(
            PURPOSE_EXTRACT, PURPOSE_PLAN, PURPOSE_REVIEW,
        )

        /** 归入"对话"配额的 purpose */
        private val CHAT_PURPOSES = listOf(
            PURPOSE_ASK, "agent_loop",
        )
    }

    /**
     * 抽取类调用是否还有额度。purpose = extract / plan / review。
     *
     * @return true = 可以调用；false = 已用尽，调用方必须走本地 fallback
     */
    suspend fun canExtract(): Boolean = withContext(Dispatchers.IO) {
        val limit = loadDailyCallLimit()
        if (limit <= 0) return@withContext false
        val used = countPurposes(CALL_PURPOSES, dayStartMillis())
        used < limit
    }

    /**
     * 对话类调用是否还有额度。purpose = ask / agent_loop。
     *
     * UI 文案：「今日对话次数已用完，明天再来」（**不用 negative 色**，这是预期行为不是错误）。
     */
    suspend fun canChat(): Boolean = withContext(Dispatchers.IO) {
        val limit = loadDailyChatLimit()
        if (limit <= 0) return@withContext false
        val used = countPurposes(CHAT_PURPOSES, dayStartMillis())
        used < limit
    }

    /** 今日抽取额度剩余（设置页/调试页展示用）。 */
    suspend fun remainingExtract(): Int = withContext(Dispatchers.IO) {
        (loadDailyCallLimit() - countPurposes(CALL_PURPOSES, dayStartMillis())).coerceAtLeast(0)
    }

    /** 今日对话额度剩余。 */
    suspend fun remainingChat(): Int = withContext(Dispatchers.IO) {
        (loadDailyChatLimit() - countPurposes(CHAT_PURPOSES, dayStartMillis())).coerceAtLeast(0)
    }

    /** 今日总调用次数（调试页用，不分 purpose）。 */
    suspend fun usedToday(): Int = withContext(Dispatchers.IO) {
        try {
            llmCallDao.countSince(dayStartMillis())
        } catch (e: Exception) {
            0
        }
    }

    /** 今日失败次数（调试页用）。 */
    suspend fun failedToday(): Int = withContext(Dispatchers.IO) {
        try {
            llmCallDao.countFailedSince(dayStartMillis())
        } catch (e: Exception) {
            0
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 按多个 purpose 求和计数。
     *
     * dao 只提供了 countSinceByPurpose（单 purpose），这里循环求和 —— 不新增 DAO 方法，
     * 因为 purpose 种类少（≤5），查询次数可忽略，且避免改动已定稿的 DB 层文件。
     */
    private suspend fun countPurposes(purposes: List<String>, since: Long): Int {
        var total = 0
        for (p in purposes) {
            total += try {
                llmCallDao.countSinceByPurpose(since, p)
            } catch (e: Exception) {
                0
            }
        }
        return total
    }

    /**
     * 当日配额窗口起点（epoch millis）。
     *
     * 用 [dayKeyOf] 同一套日界线逻辑反推：先算出当前所属"记录日"，
     * 再取其 00:00 + dayStartHour 小时。
     *
     * 做法：拿当前时间的 dayKey（yyyy-MM-dd），把这个日期的 00:00 解析回时间戳，
     * 加 dayStartHour 小时，即为窗口起点。这样"凌晨 2 点记的一笔"仍算前一日的配额。
     */
    private suspend fun dayStartMillis(): Long {
        val dayStartHour = settingsDao.get(EventRepository.KEY_DAY_START_HOUR)
            ?.toIntOrNull()?.coerceIn(0, 12) ?: 4

        val dayKey = dayKeyOf(System.currentTimeMillis(), dayStartHour)
        val parts = dayKey.split('-')
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, parts[0].toInt())
        cal.set(Calendar.MONTH, parts[1].toInt() - 1) // Calendar 月份从 0 开始
        cal.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        return cal.timeInMillis + dayStartHour * 60L * 60L * 1000L
    }

    private suspend fun loadDailyCallLimit(): Int =
        settingsDao.get(KEY_DAILY_CALL_LIMIT)?.toIntOrNull()
            ?.coerceIn(0, 500) ?: DEFAULT_DAILY_CALL_LIMIT

    private suspend fun loadDailyChatLimit(): Int =
        settingsDao.get(KEY_DAILY_CHAT_LIMIT)?.toIntOrNull()
            ?.coerceIn(0, 500) ?: DEFAULT_DAILY_CHAT_LIMIT
}
