package com.healix.app.rules

import com.healix.app.db.EventEntity

/**
 * 肌群恢复度模型（PRD §4.2 A4）。
 *
 * 纯本地纯函数：**不引用任何 Android API、不碰 Context、不做 IO、零 AI 成本**。
 * 恢复度完全由「你自己记录的历史」推算，不需要传感器、也不需要动作库。
 *
 * 恢复窗口取 **48 小时**：PRD A4 说明抗阻训练同一肌群需 48–72h 恢复
 * （Fitbod 称依据 Schoenfeld 2010）。这里取区间**下限 48h**，是保守选择 ——
 * 宁可早一点认为肌群可用，也不把用户长期锁在「未恢复」里。
 *
 * ⚠️ 硬边界（PRD A4 / §5.7）：明确不做 1RM、mStrength、Overall Strength Score
 * 之类的综合评分；恢复度只用于「今天该练哪个肌群」的排序提示，不构成训练建议。
 */
object MuscleRecovery {

    /** 6 个可跟踪肌群（与训练计划 `title` 的用词对齐）。顺序即 [summary] 的输出顺序。 */
    val MUSCLES: List<String> = listOf("胸", "背", "腿", "肩", "手臂", "核心")

    /**
     * 肌群 → 关键词表。
     *
     * 来源：训练计划 `title`（以及用户自由输入的 `raw_text` / `exercise`）里
     * 常见的中文动作词。只做**子串匹配**，命中即认为练了该肌群；
     * **抓不到就返回空列表，绝不猜**（猜错的代价是给用户排错肌群，比不排更糟）。
     */
    private val KEYWORDS: Map<String, List<String>> = mapOf(
        "胸" to listOf("胸", "卧推", "俯卧撑", "夹胸"),
        "背" to listOf("背", "引体", "划船", "下拉", "硬拉"),
        "腿" to listOf("腿", "深蹲", "蹲", "腿举", "弓步"),
        "肩" to listOf("肩", "推举", "侧平举", "飞鸟"),
        "手臂" to listOf("手臂", "二头", "三头", "弯举", "臂屈伸"),
        "核心" to listOf("核心", "腹", "平板", "卷腹"),
    )

    /**
     * 从自由的 `raw_text` / `exercise` 里抓肌群关键词。
     * 返回顺序固定为 [MUSCLES] 顺序；抓不到返回空列表（不猜）。
     */
    fun musclesOf(rawText: String, exercise: String): List<String> {
        val text = "$rawText $exercise"
        return MUSCLES.filter { muscle ->
            KEYWORDS.getValue(muscle).any { keyword -> text.contains(keyword) }
        }
    }

    /**
     * 0–100 恢复度。未练过的肌群 = 100（新手友好）。
     *
     * 只考虑 `type == "exercise"` 且 `deletedAt == null`（未软删）的记录，
     * 取该肌群最近一次命中的 `ts`：
     *   `hours = (now - ts) / 3600_000.0`
     *   `recovery = ((hours / 48.0) * 100).toInt().coerceIn(0, 100)`
     *
     * 找不到记录 → 100。`toInt()` 向零截断，恰好 24h → 50，≥48h → 100。
     */
    fun recoveryOf(muscle: String, history: List<EventEntity>, now: Long): Int {
        val lastTs = history
            .filter { it.type == "exercise" && it.deletedAt == null }
            .filter { musclesOf(it.rawText, it.exercise).contains(muscle) }
            .maxOfOrNull { it.ts }
            ?: return 100
        val hours = (now - lastTs) / 3_600_000.0
        return ((hours / 48.0) * 100).toInt().coerceIn(0, 100)
    }

    /**
     * 供 prompt / 状态页使用的一行摘要，例：`胸 100% · 背 45% · 腿 100%`。
     * 只列 [MUSCLES]（固定 6 个，顺序不变），用 `" · "` 连接。
     */
    fun summary(history: List<EventEntity>, now: Long): String =
        MUSCLES.joinToString(" · ") { muscle -> "$muscle ${recoveryOf(muscle, history, now)}%" }
}
