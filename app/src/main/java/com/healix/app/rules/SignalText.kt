package com.healix.app.rules

import com.healix.app.R

/**
 * 规则 id → 预警文案资源 id 的映射 + 统一格式化入口。
 *
 * 这里**不是**把中文硬编码进 Kotlin —— 映射的是「ruleId 选哪条 string 资源」，
 * 文案本体全部在 `strings.xml`（硬约束 0.8 的例外条款正是指这种「资源 id 映射」）。
 *
 * 双套文案（设计规范 §9.8）：`signal_short_*` 用于首页状态行（≤24 汉字、单行），
 * `signal_full_*` 用于状态详情页「身体」段（三段式、≤2 行）。
 */
object SignalText {

    /**
     * 全部规则 id（11 条，顺序固定 `H1..H8, T1..T3`），供状态页与测试遍历。
     * 顺序即优先级展示顺序无关 —— 真正的排序由 [HealthRules.evaluate] 负责。
     */
    val ALL_RULES: List<String> = listOf(
        "H1", "H2", "H3", "H4", "H5", "H6", "H7", "H8",
        "T1", "T2", "T3",
    )

    /** ruleId → `signal_short_*` 资源 id；未知 ruleId 回落 `signal_short_h3`（记录断档，最中性）。 */
    fun shortRes(ruleId: String): Int = when (ruleId) {
        "H1" -> R.string.signal_short_h1
        "H2" -> R.string.signal_short_h2
        "H3" -> R.string.signal_short_h3
        "H4" -> R.string.signal_short_h4
        "H5" -> R.string.signal_short_h5
        "H6" -> R.string.signal_short_h6
        "H7" -> R.string.signal_short_h7
        "H8" -> R.string.signal_short_h8
        "T1" -> R.string.signal_short_t1
        "T2" -> R.string.signal_short_t2
        "T3" -> R.string.signal_short_t3
        else -> R.string.signal_short_h3
    }

    /** ruleId → `signal_full_*` 资源 id；未知 ruleId 回落 `signal_full_h3`。 */
    fun fullRes(ruleId: String): Int = when (ruleId) {
        "H1" -> R.string.signal_full_h1
        "H2" -> R.string.signal_full_h2
        "H3" -> R.string.signal_full_h3
        "H4" -> R.string.signal_full_h4
        "H5" -> R.string.signal_full_h5
        "H6" -> R.string.signal_full_h6
        "H7" -> R.string.signal_full_h7
        "H8" -> R.string.signal_full_h8
        "T1" -> R.string.signal_full_t1
        "T2" -> R.string.signal_full_t2
        "T3" -> R.string.signal_full_t3
        else -> R.string.signal_full_h3
    }

    /**
     * 统一格式化入口：args 为空走 `getString(res)`，否则走 `getString(res, *args)`。
     *
     * ⚠️ **参数类型必须与资源占位符严格对应**，传错会在运行时抛
     * `IllegalFormatConversionException`（例如给 `%1$d` 传了 String / Double）：
     *   - `%1$s` → 传 `String`
     *   - `%1$d` → 传 `Int`
     *
     * 本工程约定（见契约 §3.1 的 args 表）：
     *   - `T1`：资源是 `%1$s`，args = `listOf(String)`（已格式化、保留 1 位的 kg）
     *   - `T2`：资源是 `%1$s`，args = `listOf(String)`（保留 1 位的小时）
     *   - `T3`：资源是 `%1$d`，args = `listOf(Int)`（illnessCount30）
     *   - `H1..H8`：无参数（args 为空）
     */
    fun format(context: android.content.Context, res: Int, args: List<Any>): String =
        if (args.isEmpty()) {
            context.getString(res)
        } else {
            context.getString(res, *args.toTypedArray())
        }
}
