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
 * 计划条目的目标日（`TimelineItem.day` / `TimelineEntry.day`）。
 *
 * 问题 3 方案 C 只细化用户真正会盯看的**两天**：
 * 今天按 `HH:mm` 细排；明天只给 `早 / 午 / 晚 / 训练` 四条粗颗粒**锚点**；
 * 后天起仍只有一条无时刻的训练日行（进到那天自然变「今天」，细排随之生成）。
 *
 * ⚠️ 这是**计划条目自己的**日序号，与 [TimelineEntry.dayIndex]（周内槽位 0..6）不同：
 *    `day` 表示「这份计划把这条排给了哪一天」（1 = 生成当日，2 = 次日），
 *    `dayIndex` 是合并后在周表里落哪一格。
 */
const val PLAN_DAY_TODAY = 1

/** 次日的计划条目（见 [PLAN_DAY_TODAY]）。 */
const val PLAN_DAY_TOMORROW = 2

/**
 * 「明天」锚点的时段键 —— 时段枚举、天内排序键与展示名的**唯一来源**。
 *
 * ⚠️ 键值刻意用 ASCII（`morning` / `noon` / …）而非「早 / 午 / 晚 / 训练」：
 *    前者是**数据**（要落 `plan_json`、要与模型对齐），后者是**文案**（要在
 *    `strings.xml` 里本地化）。两者混用会让「改文案」变成「改数据格式」。
 */
object PlanSlot {

    const val MORNING = "morning"
    const val NOON = "noon"
    const val EVENING = "evening"

    /** 训练锚点：**全天项**（无具体时刻，天内排最前，与训练日行同口径）。 */
    const val TRAIN = "train"

    /**
     * 天内排序键（第二排序键）。
     *
     * 契约：`早 08:00 < 午 12:00 < 晚 19:00`；「训练」返回空串 → 作为**全天项置顶**
     * （`""` 字典序最小，与训练日行的 `sortKey = ""` 一致，故无需额外排序键）。
     * 未知时段也按全天项处理（排最前总比插到「晚餐」后面更不易误导）。
     */
    fun sortKeyOf(slot: String): String = when (slot) {
        MORNING -> "08:00"
        NOON -> "12:00"
        EVENING -> "19:00"
        else -> ""
    }

    /**
     * 时段展示名（`早 / 午 / 晚 / 训练`）。
     *
     * @return 资源 id；**未知时段返回 null** —— 调用方渲染空时间列，绝不拿 `0`
     *         去 `getString`（那是 `Resources.NotFoundException`）。
     */
    fun labelResOf(slot: String): Int? = when (slot) {
        MORNING -> R.string.anchor_slot_morning
        NOON -> R.string.anchor_slot_noon
        EVENING -> R.string.anchor_slot_evening
        TRAIN -> R.string.anchor_slot_train
        else -> null
    }
}

/**
 * 统一时间轴条目（v8 需求 7；问题 3 方案 C 增补 `day`）。
 *
 * 需求 7 把原先分居两个 Tab 的「本周训练计划」与「今日 AI 计划」合并到
 * **同一条按周（周一→周日）铺开的时间轴**上：
 *
 * - `dayIndex` ∈ 0..6（周一 = 0）是**第一排序键**；
 * - 天内第二排序键 `sortKey`：训练日条目恒为 `""`（排当天最前），
 *   今日计划条目为 `HH:mm`，明天锚点为时段占位序（见 [PlanSlot.sortKeyOf]）；
 * - 今日计划条目落在 `dayIndex == todayDow - 1`；明天锚点落在**次日**那格
 *   （今天为周日时次日越出本周表 → 该组不渲染）。
 *
 * ⚠️ 这是**纯 UI 层合并**：不新增表 / 列、不碰 `events` schema、不升 Room version、
 *    无需 Migration。训练日的 `done` 由 `TrainingPlanner.completedDows()` 现算。
 *
 * ⚠️ 比增量设计 §3 的 10 字段多了 `kcal`：计划条目的「记一笔」要按**估算热量**
 *    落库（口径见 `PlanGenerator.localTimeline` 的 KDoc），而该值无法从其余字段反推。
 *
 * @property dayIndex 0 = 周一 … 6 = 周日（第一排序键）
 * @property day [PLAN_DAY_TODAY] / [PLAN_DAY_TOMORROW]；训练日行恒为 [PLAN_DAY_TODAY]
 * @property sortKey 天内排序键（训练日 `""`；今日计划项 `HH:mm`；明天锚点时段占位序）
 * @property timeLabel 显示用时间列（训练日为空 → 整列隐藏；明天锚点为 `早/午/晚/训练`）
 * @property type meal | exercise | sleep | habit（仅用于「记一笔」重建 [TimelineItem]）
 * @property title 主标题
 * @property detail 明细（训练日 = 动作串）
 * @property meta 已格式化的「时长 · 为什么」（两个来源共用 `plan_action_meta`；两者都空则空串）
 * @property source [TimelineSource] 之一（决定「记一笔」走哪条写入路径）
 * @property canLog 是否显示「记一笔」
 * @property done 是否已完成（训练日 = 本周该天已记录；计划条目恒 false）
 * @property kcal 估算热量（「记一笔」原样落库；训练日 / 明天锚点 = 0）
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
    val day: Int = PLAN_DAY_TODAY,
)

/**
 * 统一时间轴合并器（v8 需求 7 + 问题 3 方案 C，纯函数、无副作用）。
 *
 * 输出 = 本周 7 天各自的训练日条目 + 今日计划条目（今天格）+ 明天锚点（次日格），
 * 按 `(dayIndex, sortKey)` 排序。`training == null`（尚未生成本周计划）时不产生训练
 * 条目，时间轴只剩计划条目。
 */
object TimelineMerger {

    /** 一周 7 天；`dayIndex` 上限。 */
    private const val DAYS_IN_WEEK = 7

    private const val EVENT_MEAL = "meal"
    private const val EVENT_EXERCISE = "exercise"

    /** 时长缺省占位（与 `PlanGenerator` / 旧渲染同口径）。 */
    private const val DASH = "——"

    /**
     * 合并本周训练计划与计划条目（今天 + 明天）为一条按周铺开的时间轴。
     *
     * ⚠️ 训练日条目 `canLog = !isRest && !done`（**不是设计稿里的恒 false**）：
     *    「本周计划 → 记一笔」是既有功能，若在训练条目上禁用「记一笔」，等于把该写入
     *    入口整个删掉（违反"每个移出/合并的入口都要有人接住"）。故保留：非休息日且
     *    未记录时可点；已记录时 `done = true` 驱动 UI 显示「已记录」。
     *
     * ⚠️ **明天锚点一律 `canLog = false`** —— 这是硬规则，不是 UI 偏好：
     *    `PlanReviewViewModel.logSuggestion` 按 `dayKeyOf(now)` 落库，即"此刻"。
     *    明天的一餐今天写下就是**错误日期**的记录。所以锚点只读不可记。
     *
     * ⚠️ **某天有「训练」锚点时，抑制该天的训练日行**：否则明天会同时出现
     *    「训练日 · 推（胸/肩/三头）」与「训练 · 深蹲 4×8」两条同义行。锚点是更
     *    细的那条（它已结合本周计划里明天那条），故由它接管该天。
     *
     * @param ctx 仅用于取文案资源（项目禁止在 Kotlin 里硬编码中文界面文案）
     * @param plan 计划条目（今天 `day = 1` + 明天 `day = 2`；空 = 无缓存且兜底也失败）
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
        val tomorrowIndex = todayIndex + 1
        val out = ArrayList<TimelineEntry>(DAYS_IN_WEEK + plan.size)

        // 计划条目先按「目标日 → 周内槽位」归组：day<=1 → 今天格；day>=2 → 次日格。
        // 今天为周日时次日 = 下周一，越出本周表 → 该组整体不渲染（锚点不丢数据，
        // 只是本周这一屏放不下；次日新计划会重新生成，见 Problem 3 方案 C）。
        val itemsByDay = HashMap<Int, MutableList<TimelineItem>>()
        for (item in plan) {
            val idx = if (item.day <= PLAN_DAY_TODAY) todayIndex else tomorrowIndex
            if (idx > DAYS_IN_WEEK - 1) continue
            itemsByDay.getOrPut(idx) { ArrayList() } += item
        }

        for (dayIndex in 0 until DAYS_IN_WEEK) {
            val dow = dayIndex + 1
            val dayItems = itemsByDay[dayIndex].orEmpty()
            // 该天已有「训练」锚点 → 训练日行让位（见函数 KDoc 的抑制约定）。
            val hasTrainAnchor = dayItems.any { it.slot == PlanSlot.TRAIN }

            if (!hasTrainAnchor) {
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
            }

            for (item in dayItems) {
                val anchor = item.day > PLAN_DAY_TODAY
                out += TimelineEntry(
                    dayIndex = dayIndex,
                    sortKey = if (anchor) PlanSlot.sortKeyOf(item.slot) else item.time,
                    timeLabel = if (anchor) slotLabel(ctx, item.slot) else item.time,
                    type = item.type,
                    title = item.title,
                    detail = item.detail,
                    meta = metaOf(ctx, item),
                    source = TimelineSource.PLAN,
                    // 明天锚点不可记（见函数 KDoc）。
                    canLog = !anchor && (item.type == EVENT_MEAL || item.type == EVENT_EXERCISE),
                    done = false,
                    kcal = item.kcal,
                    day = item.day,
                )
            }
        }

        return out.sortedWith(compareBy({ it.dayIndex }, { it.sortKey }))
    }

    /** 时段键 → 展示名；未知时段返回空串（时间列照常 INVISIBLE，不崩）。 */
    private fun slotLabel(ctx: Context, slot: String): String {
        val res = PlanSlot.labelResOf(slot) ?: return ""
        return ctx.getString(res)
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
