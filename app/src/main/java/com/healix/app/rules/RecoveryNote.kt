package com.healix.app.rules

import android.content.Context
import com.healix.app.R
import com.healix.app.db.EventEntity

/**
 * 训练日条目的「恢复度」一行注记（v8 需求 9 功能 1；借鉴 Fitbod / Hevy）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么它值得做
 * ══════════════════════════════════════════════════════════════════════════
 * Fitbod 按"已记录的训练"给每个肌群算恢复度，48–72 小时内不重复高强度；Hevy 用
 * 组数×次数×重量做渐进超负荷统计。**两者都不需要传感器**，而 Healix 已经有
 * [MuscleRecovery]（按 `events(type=exercise)` 算肌群与恢复度）—— 这是纯粹把
 * **已有的本地计算**端到用户面前：**0 AI、0 新表、0 新键**。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 合规边界（PRD A4 / §5.7，硬约束）
 * ══════════════════════════════════════════════════════════════════════════
 * 输出**只有可以从记录里逐字核对的事实**：「哪个肌群、多久以前、恢复百分之几、
 * 其余哪些肌群恢复到位」。**不出现** 1RM / 力量总分 / 综合评分 / 训练建议口吻
 * （"你应该…"）—— 恢复度只用于排序提示，不构成训练建议。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 结构：纯计算 / 渲染分离
 * ══════════════════════════════════════════════════════════════════════════
 * [factOf] 是**纯函数**（无 Context、无 IO），因此可以在 `testDebugUnitTest` 里
 * 逐条断言边界（见 `app/src/test/.../RecoveryNoteTest.kt`）；[of] 只是把事实贴上
 * 文案（项目禁止在 Kotlin 里硬编码中文界面文案，故取文案必须过 `Context`）。
 * 两者分开，才能让"边界对不对"这件事**有 CI 证据**，而不是只靠读代码。
 */
object RecoveryNote {

    /** 低于此值视为「未恢复」。与训练 prompt 的 50% 阈值同口径（`TrainingPlanner`）。 */
    const val LOW_RECOVERY = 50

    private const val MS_PER_HOUR = 3_600_000L

    /** 24 小时：`>=` 它就用「N 天前」，否则用「N 小时前」。 */
    private const val HOURS_PER_DAY = 24L

    /** 「可练」肌群的连接符（固定 6 个肌群，最多 5 个候选，不需要换行退让）。 */
    private const val READY_SEPARATOR = "/"

    /**
     * 一条恢复度事实（纯数据）。
     *
     * @property muscle 涉及的肌群里**恢复度最低**的那个（它决定这一行的口径）
     * @property hoursAgo 距上次练该肌群的小时数（已 `coerceAtLeast(0)`，防时钟回拨出负数）
     * @property recovery [muscle] 的 0–100 恢复度
     * @property ready 恢复到位（`>= [LOW_RECOVERY]`）**且不属于当天计划肌群**的候选；
     *   仅在 [recovery]` < [LOW_RECOVERY]` 时才可能非空，否则恒为空列表
     */
    data class Fact(
        val muscle: String,
        val hoursAgo: Long,
        val recovery: Int,
        val ready: List<String>,
    )

    /**
     * 纯计算：算出这一行要说的事实；**给不出可核对事实时返回 `null`**。
     *
     * 返回 `null` 的三种情形（都属于"没有可比的历史"，不是错误）：
     * 1. 训练日标题 / 动作里**抓不出肌群**（[MuscleRecovery.musclesOf] 返回空）；
     * 2. 该训练日涉及的肌群**一条历史都没有** —— 这一条尤其重要：从没记录过的人
     *    [MuscleRecovery.recoveryOf] 恒返回 100，把它渲染出来就是拿"没数据"
     *    冒充"恢复得很好"；
     * 3. 给出肌群却查不到最近时间戳（理论上不会，防御性返回）。
     *
     * @param title 训练日标题，如 `推（胸/肩/三头）`
     * @param detail 训练日动作串（`day.itemsLine()`），与 [title] 一起送关键词匹配
     * @param history 就近训练史（调用方按时间窗裁剪，见 `PlanReviewViewModel`）
     * @param now 当前时间戳（由调用方取一次，保证同一屏内所有条目同口径）
     */
    fun factOf(
        title: String,
        detail: String,
        history: List<EventEntity>,
        now: Long,
    ): Fact? {
        val muscles = MuscleRecovery.musclesOf(detail, title)
        if (muscles.isEmpty()) return null

        // 只统计**有历史**的肌群：没记录过的肌群恢复度恒 100，把它算进「最低值」
        // 会把"胸 6 小时前练过、还没恢复"稀释成"整体恢复良好"。
        val tracked = muscles.filter { MuscleRecovery.lastTsOf(it, history) != null }
        if (tracked.isEmpty()) return null

        val least = tracked.minByOrNull { MuscleRecovery.recoveryOf(it, history, now) } ?: return null
        val recovery = MuscleRecovery.recoveryOf(least, history, now)
        val lastTs = MuscleRecovery.lastTsOf(least, history) ?: return null
        val hoursAgo = (now - lastTs).coerceAtLeast(0L) / MS_PER_HOUR

        // 只有"未恢复"时才需要给候选清单；已恢复的肌群再列一遍是噪音。
        val ready = if (recovery >= LOW_RECOVERY) {
            emptyList()
        } else {
            MuscleRecovery.MUSCLES.filter { muscle ->
                muscle !in muscles && MuscleRecovery.recoveryOf(muscle, history, now) >= LOW_RECOVERY
            }
        }
        return Fact(muscle = least, hoursAgo = hoursAgo, recovery = recovery, ready = ready)
    }

    /**
     * 组装一行恢复度注记；**无法给出可核对事实时返回空串**（调用方据此隐藏整行）。
     *
     * 输出形态：
     * - 未恢复：`胸 上次 1 天前 · 恢复 12% · 可练 背/腿`
     * - 已恢复：`胸 上次 3 天前 · 恢复 88%`
     * - 24 小时内练过：`胸 上次 6 小时前 · 恢复 12% · 可练 背/腿`
     */
    fun of(
        ctx: Context,
        title: String,
        detail: String,
        history: List<EventEntity>,
        now: Long,
    ): String {
        val fact = factOf(title, detail, history, now) ?: return ""
        val ago = if (fact.hoursAgo >= HOURS_PER_DAY) {
            ctx.getString(R.string.recovery_ago_days, (fact.hoursAgo / HOURS_PER_DAY).toInt())
        } else {
            ctx.getString(R.string.recovery_ago_hours, fact.hoursAgo.toInt())
        }
        val base = ctx.getString(R.string.plan_recovery_note, fact.muscle, ago, fact.recovery)
        return if (fact.ready.isEmpty()) {
            base
        } else {
            ctx.getString(R.string.plan_recovery_alt, base, fact.ready.joinToString(READY_SEPARATOR))
        }
    }
}
