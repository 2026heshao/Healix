package com.healix.app.ui

import android.content.Context
import com.healix.app.R

/**
 * [TimelineEntry.source] 的来源。
 *
 * ⚠️ 用 **enum** 而非字符串常量：来源只在本功能内部用于分发「记一笔」的写入路径，
 *    enum 让 `when` 穷尽、且不会与既有 `GoalTypes.TRAINING` / `PURPOSE_TRAINING`
 *    等同值字符串常量撞车（`check_duplicate_constants` 收的是「同名且同值」）。
 */
enum class TimelineSource {
    /** 今日 AI 计划（`daily_plans`）条目。 */
    PLAN,

    /** 本周训练计划条目。 */
    TRAINING,
}

/**
 * 统一时间轴条目（v8 需求 7）。
 *
 * 需求 7 把原先分居两个 Tab 的「本周训练计划」与「今日 AI 计划」合并到
 * **同一条按周（周一→周日）铺开的时间轴**上：
 *
 * - `dayIndex` ∈ 0..6（周一 = 0）是**第一排序键**；
 * - 天内第二排序键 `sortKey`：训练日条目恒为 `""`（排当天最前），计划条目为 `HH:mm`；
 * - 今日计划条目只在 `dayIndex == todayDow - 1` 那一天展开（计划天然只有今日一份）。
 *
 * ⚠️ 这是**纯 UI 层合并**：不新增表 / 列、不碰 `events` schema、不升 Room version、
 *    无需 Migration。训练日的 `done` 由 `TrainingPlanner.completedDows()` 现算。
 *
 * ⚠️ 比增量设计 §3 的 10 字段多了一个 `kcal`：计划条目的「记一笔」要按**估算热量**
 *    落库（口径见 `PlanGenerator.localTimeline` 的 KDoc），而该值无法从其余字段反推。
 *
 * @property dayIndex 0 = 周一 … 6 = 周日（第一排序键）
 * @property sortKey 天内排序键（训练日 `""`；计划项 `HH:mm`）
 * @property timeLabel 显示用时间（训练日为空 → 渲染时整列隐藏）
 * @property type meal | exercise | sleep | habit（仅用于「记一笔」重建 [TimelineItem]）
 * @property title 主标题
 * @property detail 明细（训练日 = 动作串）
 * @property meta 已格式化的「时长 · 为什么」（两个来源共用 `plan_action_meta`；两者都空则空串）
 * @property source [TimelineSource] 之一（决定「记一笔」走哪条写入路径）
 * @property canLog 是否显示「记一笔」
 * @property done 是否已完成（训练日 = 本周该天已记录；计划条目恒 false）
 * @property kcal 估算热量（「记一笔」原样落库；训练日 = 0）
 */
data class TimelineEntry(
    val dayIndex: Int,
    val sortKey: String,
    val timeLabel: String,
    val type: String,
    val title: String,
    val detail: String,
    val meta: String,
    val source: TimelineSource,
    val canLog: Boolean,
    val done: Boolean,
    val kcal: Int = 0,
)

/**
 * 统一时间轴合并器（v8 需求 7，纯函数、无副作用）。
 *
 * 输出 = 本周 7 天各自的训练日条目 + 今日的计划条目，按 `(dayIndex, sortKey)` 排序。
 * `training == null`（尚未生成本周计划）时不产生训练条目，时间轴只剩今日计划。
 */
object TimelineMerger {

    /** 一周 7 天；`dayIndex` 上限。 */
    private const val DAYS_IN_WEEK = 7

    private const val EVENT_MEAL = "meal"
    private const val EVENT_EXERCISE = "exercise"

    /** 时长缺省占位（与 `PlanGenerator` / 旧渲染同口径）。 */
    private const val DASH = "——"

    /**
     * 合并本周训练计划与今日计划为一条按周铺开的时间轴。
     *
     * ⚠️ 训练日条目 `canLog = !isRest && !done`（**不是设计稿里的恒 false**）：
     *    「本周计划 → 记一笔」是既有功能，若在训练条目上禁用「记一笔」，等于把该写入
     *    入口整个删掉（违反"每个移出/合并的入口都要有人接住"）。故保留：非休息日且
     *    未记录时可点；已记录时 `done = true` 驱动 UI 显示「已记录」。
     *
     * @param ctx 仅用于取 `plan_action_meta`（项目禁止在 Kotlin 里硬编码中文）
     * @param plan 今日计划条目（空 = 无缓存且本地兜底也失败）
     * @param training 本周训练计划（null = 尚未生成）
     * @param completedDows 本周已记录的训练日 ISO dow 集合
     * @param todayDow 今日 ISO dow（1 = 周一 … 7 = 周日）
     */
    fun merge(
        ctx: Context,
        plan: List<TimelineItem>,
        training: TrainingPlan?,
        completedDows: Set<Int>,
        todayDow: Int,
    ): List<TimelineEntry> {
        val todayIndex = (todayDow - 1).coerceIn(0, DAYS_IN_WEEK - 1)
        val out = ArrayList<TimelineEntry>(DAYS_IN_WEEK + plan.size)

        for (dayIndex in 0 until DAYS_IN_WEEK) {
            val dow = dayIndex + 1

            training?.days?.firstOrNull { it.dow == dow }?.let { day ->
                val done = !day.isRest && completedDows.contains(dow)
                out += TimelineEntry(
                    dayIndex = dayIndex,
                    sortKey = "",
                    timeLabel = "",
                    type = EVENT_EXERCISE,
                    title = day.title,
                    detail = if (day.isRest) "" else day.itemsLine(),
                    meta = "",
                    source = TimelineSource.TRAINING,
                    canLog = !day.isRest && !done,
                    done = done,
                )
            }

            if (dayIndex == todayIndex) {
                for (item in plan) {
                    out += TimelineEntry(
                        dayIndex = dayIndex,
                        sortKey = item.time,
                        timeLabel = item.time,
                        type = item.type,
                        title = item.title,
                        detail = item.detail,
                        meta = metaOf(ctx, item),
                        source = TimelineSource.PLAN,
                        canLog = item.type == EVENT_MEAL || item.type == EVENT_EXERCISE,
                        done = false,
                        kcal = item.kcal,
                    )
                }
            }
        }

        return out.sortedWith(compareBy({ it.dayIndex }, { it.sortKey }))
    }

    /** 与旧时间轴渲染同口径：duration 空 → 占位「——」；why 空则不接后半段。 */
    private fun metaOf(ctx: Context, item: TimelineItem): String {
        if (item.duration.isBlank() && item.why.isBlank()) return ""
        val duration = item.duration.ifBlank { DASH }
        return if (item.why.isBlank()) {
            duration
        } else {
            ctx.getString(R.string.plan_action_meta, duration, item.why)
        }
    }
}
