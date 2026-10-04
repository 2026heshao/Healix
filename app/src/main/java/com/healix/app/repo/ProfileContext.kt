package com.healix.app.repo

import com.healix.app.db.AppDatabase
import com.healix.app.db.SettingsKeys

/**
 * 画像上下文 → system prompt 的 `background` 通道（硬约束段 + 软背景段）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么抽出这一处（消除重复代码 + 口径单一）
 * ══════════════════════════════════════════════════════════════════════════
 * 这段拼装原本内联在 `ChatViewModel.executeChat()`（229–293 行）。计划的硬约束/
 * 软背景段与对话**必须同源** —— 否则两处口径一旦漂移（比如对话绕开了忌口、
 * 计划却按忌口排），AI 行为会静默分叉，且编译期完全不可见。
 *
 * ⚠️ 输出必须与 `ChatViewModel` 原 229–293 行**逐字一致**：它直接进 system
 *    prompt，改一个字就是改 AI 行为。这里是"搬运"而非"重写"。
 *
 * ⚠️ 跨文件调用必须限定引用：`ProfileContext.build(db)`（见 `check_object_scope`）。
 */
object ProfileContext {

    /**
     * 拼装 background 通道文本。
     *
     * - 硬约束段：忌口/过敏/不吃、疼痛/不适（内嵌 F9 硬规则语义）、运动条件。
     * - 软背景段：就餐场景、作息（就寝/起床）、手头食物、常备药物、补充说明。
     *
     * 空值整段省略（沿用 `ChatEngine.systemPrompt` 的空省略先例）。
     * 资源清单读端统一走 [ResourceStore]（sport 含旧 profile_gear 迁移兜底）。
     *
     * @param db 已初始化的数据库实例。
     * @return 去首尾空白后的 background 文本（可能为空串）。
     */
    suspend fun build(db: AppDatabase): String {
        // 背景每次现读：设置页可能刚改过，缓存会让改动不生效
        val allergens = parseFoodsJson(
            db.settingsDao().get(SettingsKeys.PROFILE_ALLERGENS).orEmpty(),
        )
        val pain = parseFoodsJson(
            db.settingsDao().get(SettingsKeys.PROFILE_PAIN).orEmpty(),
        )
        val sport = ResourceStore.sport(db)
        val foodsAtHand = ResourceStore.foods(db)
        val medsAtHand = ResourceStore.meds(db)
        val scene = db.settingsDao()
            .get(SettingsKeys.PROFILE_SCENE).orEmpty()
        val bed = db.settingsDao()
            .get(SettingsKeys.PROFILE_SLEEP_BED).orEmpty()
        val wake = db.settingsDao()
            .get(SettingsKeys.PROFILE_SLEEP_WAKE).orEmpty()
        val backgroundText = db.settingsDao()
            .get(SettingsKeys.BACKGROUND).orEmpty()
        return buildString {
            // 硬约束段：只在有内容时出现；疼痛行内嵌 F9 硬规则语义。
            // 「【硬约束】」标记行与硬边界 2 的「硬约束段」措辞互相呼应。
            val hardHead = buildList {
                if (allergens.isNotEmpty()) {
                    add("- 忌口/过敏/不吃（饮食建议必须绕开）：${allergens.joinToString("、")}")
                }
                if (pain.isNotEmpty()) {
                    add(
                        "- 疼痛/不适部位（运动建议必须避开相关动作，" +
                            "优先恢复性建议——睡眠、补水）：${pain.joinToString("、")}",
                    )
                }
                if (sport.isNotEmpty()) {
                    add(
                        "- 运动条件（运动建议只用这些器材/场地，时段可用则优先）：\n" +
                            sport.lineSequence().map { "  $it" }.joinToString("\n"),
                    )
                }
            }
            if (hardHead.isNotEmpty()) {
                appendLine("【硬约束——必须遵守】")
                hardHead.forEach { appendLine(it) }
                appendLine()
            }
            // 软背景段：场景 / 作息 / 手头食物 / 常备药物 / 补充说明
            if (scene.isNotBlank()) appendLine("就餐场景：$scene")
            if (bed.isNotBlank() || wake.isNotBlank()) {
                append("作息：")
                if (bed.isNotBlank()) append("$bed 睡")
                if (bed.isNotBlank() && wake.isNotBlank()) append(" · ")
                if (wake.isNotBlank()) append("$wake 起")
                appendLine()
            }
            // 资源清单（白板式手动声明）：食物是推荐池且优先于自动常吃池
            //（说话方式段的优先级措辞）；药物仅作事实参考，
            // 行为边界（不给剂量/不推断疾病）由硬边界 3 承担。
            if (foodsAtHand.isNotBlank()) {
                appendLine("手头现成的食物（推荐优先从这里选）：")
                foodsAtHand.lineSequence().forEach { appendLine("  $it") }
            }
            if (medsAtHand.isNotBlank()) {
                appendLine("常备药物（仅作既有事实参考）：")
                medsAtHand.lineSequence().forEach { appendLine("  $it") }
            }
            append(backgroundText)
        }.trim()
    }
}
