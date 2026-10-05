package com.healix.app.ui

/**
 * 日期 / 时间格式化与日长常量。集中一处，避免各页各写一遍。
 *
 * v8：由 `MainActivity.kt` 底部抽出为独立文件 —— 记录页迁为 [RecordFragment] 后，
 * 本对象被「记录 / 助理 / 设置 / 计划」等多页共用，挂在宿主（记录页）所在文件里
 * 语义上已不成立（宿主不再持有这些格式化逻辑）。
 */
internal object HealixDate {

    /**
     * 一天的毫秒数。**唯一来源** —— 提醒顺延（[SettingsViewModel]）、临期判定
     * （[SettingsFragment]）、跨日回看（[TrainingPlanner]）都必须用它；
     * 不得再出现 `86_400_000L` 一类第二、第三份字面量（改一处漏一处）。
     */
    const val DAY_MS = 24L * 60 * 60 * 1000

    private val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /**
     * 按 day_key（日界线口径）渲染日期标签。
     *
     * 与列表共用同一 day_key，避免凌晨窗口（默认日界 04:00）内
     * 「列表已算作今天、日期标签却按 LocalDate.now() 显示昨天」的口径打架。
     * day_key 解析失败时兜底为系统当天，保证标签永不空白。
     */
    fun labelOf(dayKey: String): String {
        val d = runCatching { java.time.LocalDate.parse(dayKey) }.getOrNull()
            ?: java.time.LocalDate.now()
        return "${d.monthValue}月${d.dayOfMonth}日 ${WEEKDAYS[d.dayOfWeek.value - 1]}"
    }

    fun timeLabel(ts: Long): String {
        val t = java.time.Instant.ofEpochMilli(ts)
            .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        return "%02d:%02d".format(t.hour, t.minute)
    }

    /** 会话日期标签：今天 / 昨天 / M月d日 */
    fun sessionLabel(date: java.time.LocalDate): String {
        val today = java.time.LocalDate.now()
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> "${date.monthValue}月${date.dayOfMonth}日"
        }
    }
}
