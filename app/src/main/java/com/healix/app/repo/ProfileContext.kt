package com.healix.app.repo

import com.healix.app.db.AppDatabase
import com.healix.app.db.GoalMetrics
import com.healix.app.db.SettingsKeys
import java.time.LocalDate

/**
 * 画像上下文 → system prompt 的 `background` 通道（硬约束段 + 软背景段
 * + 体格段 + 目标组段）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么抽出这一处（消除重复代码 + 口径单一）
 * ══════════════════════════════════════════════════════════════════════════
 * 这段拼装原本内联在 `ChatViewModel.executeChat()`（229–293 行）。计划的硬约束/
 * 软背景段与对话**必须同源** —— 否则两处口径一旦漂移（比如对话绕开了忌口、
 * 计划却按忌口排），AI 行为会静默分叉，且编译期完全不可见。
 *
 * ⚠️ 2026-10-05 起新增体格段与目标组段：三链（对话 / 今日计划 / 周训练）共用，
 *    整体受总开关 [aiDataFull]（SettingsKeys.AI_DATA_FULL）门控 —— 开关关闭时
 *    build 返回空串，三条链路只用记录数据（今日数字 / 历史 / 工具结果）。
 *
 * ⚠️ 跨文件调用必须限定引用：`ProfileContext.build(db)`（见 `check_object_scope`）。
 */
object ProfileContext {

    /**
     * AI 可见资料范围总开关（SettingsKeys.AI_DATA_FULL）。键不存在 = 开。
     * 三链共用唯一判定入口；每次现读（设置页拨动后下次调用即生效）。
     */
    suspend fun aiDataFull(db: AppDatabase): Boolean {
        return db.settingsDao().get(SettingsKeys.AI_DATA_FULL) != "false"
    }

    /**
     * 拼装 background 通道文本。
     *
     * - 硬约束段：忌口/过敏/不吃、疼痛/不适（内嵌 F9 硬规则语义）、运动条件。
     * - 软背景段：就餐场景、作息（就寝/起床）、手头食物、常备药物、补充说明。
     * - 体格段：身高 / 体重（最近一条 body 记录）/ 年龄 / 活动系数。
     * - 目标组段：仅列出已设置项（goals 表非空行）+ 自定义次目标。
     *
     * 总开关关闭（[aiDataFull] == false）→ 直接返回空串：AI 只用记录数据，
     * 画像 / 体格 / 目标段全省略。空值整段省略（沿用 `ChatEngine.systemPrompt`
     * 的空省略先例）。资源清单读端统一走 [ResourceStore]
     * （sport 含旧 profile_gear 迁移兜底）。
     *
     * @param db 已初始化的数据库实例。
     * @return 去首尾空白后的 background 文本（可能为空串）。
     */
    suspend fun build(db: AppDatabase): String {
        // ── 总开关门控（2026-10-05）：关 = AI 只用记录数据，
        //    画像/体格/目标段全省略。判定口径唯一：!= "false"。
        if (!aiDataFull(db)) return ""

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
            //（说话方式段的优先级措辞）；药物仅作既有事实参考，
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

            // ── 体格段（2026-10-05）：来自个人资料设置与体重记录，事实参考类。
            //    每行仅在有数据时输出；标头仅在有行时出现（空则整段省略）。
            val bodyLines = buildList {
                db.settingsDao().get(SettingsKeys.HEIGHT)?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add("- 身高：$it cm") }
                // 体重：读最近一条 events(type=body)（weightRowsInRange 已过滤
                // weight_kg > 0 与软删），取升序结果最后一条 —— 与
                // SettingsViewModel.loadLatestBodyWeight 同口径（近 365 天）。
                // HIDE_WEIGHT == "true" 时整行省略（循 TodaySummary 先例，不写"已隐藏"）。
                if (db.settingsDao().get(SettingsKeys.HIDE_WEIGHT) != "true") {
                    val today = LocalDate.now()
                    val latestBody = runCatching {
                        db.eventDao().weightRowsInRange(
                            today.minusDays(365).toString(),
                            today.toString(),
                        ).lastOrNull()
                    }.getOrNull()
                    if (latestBody != null && latestBody.weightKg > 0.0) {
                        add("- 体重：${trimNumber(latestBody.weightKg)} kg（记录于 ${latestBody.dayKey}）")
                    }
                }
                db.settingsDao().get(SettingsKeys.AGE)?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add("- 年龄：$it 岁") }
                db.settingsDao().get(SettingsKeys.ACTIVITY)?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add("- 活动系数：$it") }
            }
            if (bodyLines.isNotEmpty()) {
                appendLine()
                appendLine("【体格资料（来自个人资料设置与体重记录，事实参考）】")
                bodyLines.forEach { appendLine(it) }
            }

            // ── 目标组段（2026-10-05）：仅列出已设置项（null 或 <=0 视为未设置，
            //    禁止用 GoalDefaults 兜底 —— 这里引用的是用户目标，不是排程操作数）。
            //    标头仅在有行时出现（空则整段省略）。
            val goalLines = buildList {
                db.goalDao().getByMetric(GoalMetrics.SESSIONS_PER_WEEK)?.targetValue
                    ?.takeIf { it > 0 }?.toInt()
                    ?.let { add("- 每周训练 $it 次") }
                db.goalDao().getByMetric(GoalMetrics.TRAIN_MINUTES_PER_WEEK)?.targetValue
                    ?.takeIf { it > 0 }?.toInt()
                    ?.let { add("- 每周训练时长 $it 分钟") }
                db.goalDao().getByMetric(GoalMetrics.SLEEP_H)?.targetValue
                    ?.takeIf { it > 0 }
                    ?.let { add("- 睡眠目标 ${trimNumber(it)} 小时") }
                db.goalDao().getByMetric(GoalMetrics.WATER_ML)?.targetValue
                    ?.takeIf { it > 0 }?.toInt()
                    ?.let { add("- 饮水目标 $it ml") }
                db.settingsDao().get(SettingsKeys.CUSTOM_GOAL_TEXT)?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add("- 自定义次目标：$it") }
            }
            if (goalLines.isNotEmpty()) {
                appendLine()
                appendLine("【目标（来自目标设置，仅列出已设置项）】")
                goalLines.forEach { appendLine(it) }
            }
        }.trim()
    }

    /** 去掉无意义的小数尾巴：7.0 → "7"，7.5 → "7.5"（参照 TrainingPlanner.trimNumber）。 */
    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
