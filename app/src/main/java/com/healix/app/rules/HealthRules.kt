package com.healix.app.rules

import kotlin.math.abs
import kotlin.math.round

/** 一条命中的规则。文案由 [SignalText] 按 ruleId 映射资源，args 交给 getString 格式化。 */
data class HealthSignal(
    /** H1..H8 | T1..T3 */
    val ruleId: String,
    /** info | notice | alert */
    val level: String,
    /** 越小越优先（首页状态行只显示第一条） */
    val priority: Int,
    /** 交给 getString 的参数；无参规则为空列表 */
    val args: List<Any> = emptyList(),
    /** 已格式化的 _short 文案（首页直接用，避免重复取资源） */
    val shortDisplay: String = "",
    /** 已格式化的 _full 文案 */
    val fullDisplay: String = "",
)

/** 规则求值的全部输入。**由调用方聚合好**，本层不做任何 IO、不碰 DB、不调 AI。 */
data class HealthSnapshot(
    val dayKey: String = "",
    /** 当天从 0:00 起的分钟数（H3 判定 20:00） */
    val nowMinutes: Int = 0,
    /** 近 3 个有记录日的睡眠小时（升序，最多 3 个） */
    val sleepLast3: List<Double> = emptyList(),
    val exerciseCount7: Int = 0,
    val exerciseMinutes7: Int = 0,
    /** 近 3 日每天的 meal 记录数（升序） */
    val mealsLast3: List<Int> = emptyList(),
    /** 近 3 日的热量缺口（升序） */
    val gapLast3: List<Int> = emptyList(),
    /** 近 14 日体重（升序，只含有记录的日） */
    val weights14: List<Double> = emptyList(),
    /** 近 7 日首尾差（正 = 涨） */
    val weightDelta7: Double = 0.0,
    /** 近 14 日首尾差 */
    val weightDelta14: Double = 0.0,
    val sleepAvgCur7: Double = 0.0,
    val sleepAvgPrev7: Double = 0.0,
    val isWeightLossGoal: Boolean = false,
    /** 0 = 算不出 */
    val bmi: Double = 0.0,
    val illnessCount30: Int = 0,
    val recordCountToday: Int = 0,
    /** 已格式化的 _short（首页直显） */
    val messages: List<String> = emptyList(),
    /** 已格式化的 _full（状态页直显） */
    val messagesFull: List<String> = emptyList(),
)

/**
 * 规则层：把聚合好的 [HealthSnapshot] 求值成一组 [HealthSignal]。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 本层 **0 次 AI 调用**、0 次 IO、不碰 DB、不起协程 —— 纯本地常量规则。
 * 依据 PRD §5.1 的成本设计：免费档模型 429 密集（已实测），而预警每天都要跑，
 * 绝不能把预警挂在 AI 上。规则结果只有在用户主动点「展开说说」时，才会交给 AI
 * 说人话（走对话配额，可选）。
 * ══════════════════════════════════════════════════════════════════════
 *
 * ⚠️ **硬规则 R2「单次不报警」**：所有触发条件都必须包含「连续 N 天 / 近 N 日」窗口，
 * 单日数据一律不得触发预警。条件逐条对应 PRD §5.2（B1 习惯红线）/ §5.3（B2 趋势异常）。
 *
 * ⚠️ 每条规则**独立判断，不做 else-if 短路**：允许同时命中多条，由调用方取舍
 * （首页状态行只取排序后的第一条）。
 *
 * ⚠️ 硬边界（PRD §5.7）：只做生活方式提示，不做疾病推断 / 用药建议 / 概率数字。
 * H8 是全民安全条款 —— BMI 偏低 + 体重不涨时引导**就医**，而不是「再多吃点」。
 */
object HealthRules {

    /**
     * 规则 id → 优先级（设计规范 9.2）：就医 > 睡眠 > 运动 > 缺口 > 记录。
     * 数值越小越优先；[HealthSignal.priority] 与之一致。
     */
    fun priorityOf(ruleId: String): Int = when (ruleId) {
        "H8", "T3" -> 10 // 就医安全项 / 生病频次
        "T1" -> 15       // 体重异常下降
        "H1", "T2" -> 20 // 睡眠
        "H2", "H7" -> 30 // 运动
        "H5", "H6" -> 40 // 热量缺口 / 体重停滞
        "H3", "H4" -> 50 // 记录断档 / 三餐
        else -> Int.MAX_VALUE
    }

    /**
     * 求值。返回**已按 priority 升序**的信号列表；同 priority 按 ruleId 字典序。
     * 无命中返回空列表。
     *
     * 需要 [android.content.Context] 是为了在返回前把 `shortDisplay` / `fullDisplay`
     * 用 [SignalText.format] 填好 —— 调用方（首页 / 状态页）拿到即可直显。
     */
    fun evaluate(context: android.content.Context, s: HealthSnapshot): List<HealthSignal> {
        val hits = mutableListOf<HealthSignal>()

        // ── B1 习惯红线（PRD §5.2）──────────────────────────────────────
        // H1 睡眠不足：近 3 日均睡不到 6h（且都有记录）
        if (s.sleepLast3.size == 3 && s.sleepLast3.all { it < 6.0 }) {
            hits += build(context, "H1", "notice")
        }
        // H2 久未运动：近 7 日 exercise 记录数 = 0
        if (s.exerciseCount7 == 0) {
            hits += build(context, "H2", "info")
        }
        // H3 记录断档：今日记录数 = 0 且已过 20:00
        if (s.recordCountToday == 0 && s.nowMinutes >= 20 * 60) {
            hits += build(context, "H3", "info")
        }
        // H4 三餐不规律：近 3 日中 ≥2 日 meal 记录 < 2
        if (s.mealsLast3.size == 3 && s.mealsLast3.count { it < 2 } >= 2) {
            hits += build(context, "H4", "info")
        }
        // H5 缺口持续：近 3 日热量缺口均 > 500 kcal
        if (s.gapLast3.size == 3 && s.gapLast3.all { it > 500 }) {
            hits += build(context, "H5", "notice")
        }
        // H6 体重停滞：近 14 日体重无上升（且 ≥4 次记录）
        if (s.weights14.size >= 4 && s.weightDelta14 <= 0.0) {
            hits += build(context, "H6", "notice")
        }
        // H7 运动量不足：近 7 日中等强度运动累计 < 150 分钟
        if (s.exerciseMinutes7 < 150) {
            hits += build(context, "H7", "notice")
        }
        // H8 体重与 BMI 安全项：BMI < 18.5 且近 14 日体重停滞或下降
        if (s.bmi > 0.0 && s.bmi < 18.5 && s.weights14.size >= 4 && s.weightDelta14 <= 0.0) {
            hits += build(context, "H8", "alert")
        }

        // ── B2 趋势异常（PRD §5.3）──────────────────────────────────────
        // T1 体重异常下降：7 日跌幅 > 2%（相对 14 日窗口的首个体重）且非「减重目标」。
        //    参考值取 weights14.first()；≤0 时用 1.0 兜底，避免阈值为 0/负导致任何
        //    微小波动都触发（真实体重不会 ≤0，此处纯防御）。
        if (!s.isWeightLossGoal && s.weights14.size >= 2) {
            val refRaw = s.weights14.first()
            val ref = if (refRaw > 0.0) refRaw else 1.0
            val threshold = ref * 0.02
            if (s.weightDelta7 <= -threshold) {
                hits += build(context, "T1", "alert", listOf(oneDecimal(abs(s.weightDelta7))))
            }
        }
        // T2 睡眠下滑：近 7 日均值比前 7 日低 1h 以上
        if (s.sleepAvgPrev7 > 0.0 && (s.sleepAvgPrev7 - s.sleepAvgCur7) >= 1.0) {
            hits += build(context, "T2", "notice", listOf(oneDecimal(s.sleepAvgPrev7 - s.sleepAvgCur7)))
        }
        // T3 生病频次偏高：近 30 日 illness 记录 ≥ 3 次
        if (s.illnessCount30 >= 3) {
            hits += build(context, "T3", "alert", listOf(s.illnessCount30))
        }

        return hits.sortedWith(compareBy({ it.priority }, { it.ruleId }))
    }

    /** 组装一条信号，并把两套文案就地格式化好。 */
    private fun build(
        context: android.content.Context,
        ruleId: String,
        level: String,
        args: List<Any> = emptyList(),
    ): HealthSignal = HealthSignal(
        ruleId = ruleId,
        level = level,
        priority = priorityOf(ruleId),
        args = args,
        shortDisplay = SignalText.format(context, SignalText.shortRes(ruleId), args),
        fullDisplay = SignalText.format(context, SignalText.fullRes(ruleId), args),
    )

    /** 保留 1 位小数（四舍五入）后再去掉无意义尾巴：1.234 → "1.2"，1.0 → "1"。 */
    private fun oneDecimal(value: Double): String = trimNumber(round(value * 10.0) / 10.0)

    /** 去掉无意义的小数尾巴：58.0 → "58"，58.2 → "58.2"（与 EventText 同口径）。 */
    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
