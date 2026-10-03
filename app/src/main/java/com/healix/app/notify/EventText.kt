package com.healix.app.notify

import com.healix.app.HealixApp
import com.healix.app.parse.ParsedEvent

/**
 * 事件类型 → 中文标签 + 通知文案格式化。
 *
 * 全 App 用户可见文本走 strings.xml，但这里需要的是"根据 type 选哪条 string"的映射，
 * 不是一个固定字符串 —— 用 when 分支调 getString，而不是把中文硬编码在 Kotlin 里。
 *
 * 设计规范 2.1 的类型名：饮食 / 运动 / 体重 / 睡眠 / 生病 / 其他。
 */
object EventText {

    /** type → 资源 id。未知类型回落 other。 */
    fun typeLabelRes(type: String): Int = when (type) {
        "meal" -> com.healix.app.R.string.type_meal
        "exercise" -> com.healix.app.R.string.type_exercise
        "body" -> com.healix.app.R.string.type_body
        "sleep" -> com.healix.app.R.string.type_sleep
        "illness" -> com.healix.app.R.string.type_illness
        else -> com.healix.app.R.string.type_other
    }

    /**
     * 一条事件的热量展示口径：
     * - meal / exercise → 显示 kcal
     * - body → 显示体重
     * - sleep → 显示时长
     * - 其它 → 不显示数值
     *
     * 这样通知副标题不会出现"已记录 体重 · 约 0 kcal"这种荒谬文案。
     */
    fun summarySuffix(event: ParsedEvent): SummaryValue = when (event.type) {
        "meal" -> SummaryValue.Kcal(event.kcal)
        "exercise" -> SummaryValue.Kcal(event.kcal)
        "body" -> if (event.weightKg > 0) SummaryValue.Weight(event.weightKg)
        else SummaryValue.None
        "sleep" -> if (event.sleepH > 0) SummaryValue.Sleep(event.sleepH)
        else SummaryValue.None
        else -> SummaryValue.None
    }

    /** 通知副标题的数值部分。 */
    sealed interface SummaryValue {
        data class Kcal(val value: Int) : SummaryValue
        data class Weight(val kg: Double) : SummaryValue
        data class Sleep(val hours: Double) : SummaryValue
        data object None : SummaryValue
    }

    // ---------------------------------------------------------------------
    // 以下为 UI 层便捷入口（主界面 / 列表项）。
    // 类型色值取自设计规范 2.1 的「事件类型标识」表，属于设计令牌而非配色任性。
    // ---------------------------------------------------------------------

    /** type → 中文标签（需要 Context 取资源）。 */
    fun typeName(context: android.content.Context, type: String): String =
        context.getString(typeLabelRes(type))

    /** type → 6px 圆点色。深色模式下由 values-night/colors.xml 自动覆盖。 */
    fun typeColor(context: android.content.Context, type: String): Int =
        androidx.core.content.ContextCompat.getColor(
            context,
            when (type) {
                "meal" -> com.healix.app.R.color.type_meal
                "exercise" -> com.healix.app.R.color.type_exercise
                "body" -> com.healix.app.R.color.type_body
                "sleep" -> com.healix.app.R.color.type_sleep
                "illness" -> com.healix.app.R.color.type_illness
                else -> com.healix.app.R.color.type_other
            },
        )

    /**
     * 列表项的第二行摘要。按类型口径给不同内容，避免"体重 · 约 0 kcal"这类荒谬文案。
     * 无可用信息时返回 null（UI 隐藏该行，不留空占位）。
     */
    fun summary(context: android.content.Context, event: com.healix.app.db.EventEntity): String? {
        // ⚠️ 千万不要在这里写 `val R = com.healix.app.R`：
        //    局部名字 R 会**遮蔽**掉生成的 R 类，于是 `R.string.xxx`
        //    变成「在一个 Class 引用上取 string」——编译报
        //      Classifier 'class R : Any' does not have a companion object,
        //      so it cannot be used as an expression
        //      Unresolved reference 'string'
        //    （实测 CI 就是被这个坑挂住的。）
        return when (event.type) {
            "meal", "exercise" -> {
                val base = if (event.kcal > 0) {
                    context.getString(com.healix.app.R.string.summary_kcal, event.kcal)
                } else {
                    null
                }
                when {
                    base != null && event.amount.isNotBlank() ->
                        context.getString(
                            com.healix.app.R.string.summary_kcal_amount, event.kcal, event.amount,
                        )
                    base != null -> base
                    event.amount.isNotBlank() -> event.amount
                    else -> null
                }
            }
            "body" -> if (event.weightKg > 0) {
                context.getString(com.healix.app.R.string.summary_weight, trimNumber(event.weightKg))
            } else null

            "sleep" -> if (event.sleepH > 0) {
                context.getString(com.healix.app.R.string.summary_sleep, trimNumber(event.sleepH))
            } else null

            "illness" -> event.symptom.ifBlank { null }
            else -> null
        }
    }

    /** 去掉无意义的小数尾巴：58.0 → "58"，58.2 → "58.2"。 */
    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}

/**
 * 便捷：从 [HealixApp] 取通知 id / channel id，避免各处写错字面量。
 * （保持在 notify 包内，HealixApp 的常量是唯一来源。）
 */
internal val QUICK_INPUT_NOTIFICATION_ID: Int get() = HealixApp.NOTIFICATION_ID_QUICK_INPUT
internal const val CHANNEL_QUICK_INPUT: String = HealixApp.CHANNEL_ID_QUICK_INPUT
