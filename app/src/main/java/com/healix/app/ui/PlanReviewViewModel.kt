package com.healix.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healix.app.HealixApp
import com.healix.app.parse.normalizeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 计划页 UI 状态。items 为空时降级为 note 纯文本。 */
data class PlanUiState(
    val items: List<PlanItemUi> = emptyList(),
    val note: String = "",
    val gapLeft: Int = 0,
    val source: String = "fallback",
)

data class PlanItemUi(
    val type: String,
    val title: String,
    val detail: String,
    val kcal: Int,
)

data class ReviewUiState(
    val kcalIn: Int = 0,
    val kcalOut: Int = 0,
    val weightKg: Double = 0.0,
    val content: String = "",
)

/**
 * 计划 / 复盘 ViewModel。
 *
 * ⚠️ 范围边界（重要）：
 * 本类当前实现的是**本地规则生成的计划与复盘**，对应功能补充 1.7 的降级路径：
 * "断网也能完成记录 → 查看 → 缺口计算 → 导出 全流程，只是没有复盘建议。"
 *
 * AI 生成的计划 / 复盘（含 prompt、输出 schema、每日 3–4 次调用预算）
 * 属于执行计划 P4，将复用 `ChatEngine` 的单轮调用模式实现 —— 现在不接，
 * 是为了避免"计划 prompt 还没定稿就先把调用埋进 ViewModel"。
 * 调用点已经预留：`refresh()` 里标注了接入位置。
 */
class PlanReviewViewModel(app: Application) : AndroidViewModel(app) {

    private val container = HealixApp.from(app)
    private val db = container.database

    private val _plan = MutableStateFlow(PlanUiState())
    val plan: StateFlow<PlanUiState> = _plan.asStateFlow()

    private val _review = MutableStateFlow(ReviewUiState())
    val review: StateFlow<ReviewUiState> = _review.asStateFlow()

    private val _showPlan = MutableStateFlow(true)

    init {
        reload()
    }

    fun showPlan(show: Boolean) {
        _showPlan.value = show
    }

    /**
     * 重新生成。
     *
     * 接入点（P4）：此处应调 AI 生成结构化建议，写入 daily_plans；
     * AI 失败或未配置时回落到本地规则 —— 也就是当前实现的行为。
     * **不能显示空白**（UI 设计方案 8.3）。
     */
    fun refresh() {
        reload()
    }

    private fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            val summary = TodaySummary.build(getApplication())

            // ── 计划：本地规则生成（AI 未接入时的兜底，不能空白）─────────
            val note = buildNote(summary)
            _plan.value = PlanUiState(
                items = buildLocalPlan(summary),
                note = note,
                gapLeft = if (summary.gap > 0) summary.gap else 0,
                source = "fallback",
            )

            // ── 复盘：本地聚合（AI 未接入时用已有数字 + 说明）────────────
            _review.value = ReviewUiState(
                kcalIn = summary.kcalIn,
                kcalOut = summary.kcalOut,
                weightKg = summary.weightKg,
                content = buildLocalReview(summary),
            )
        }
    }

    /**
     * 本地规则计划（功能补充 1.7）。
     * 缺口大时给主食+蛋白，缺口小时给加餐，生病时改清淡饮食（UI 设计方案 8.2 第 3 条）。
     */
    private fun buildLocalPlan(s: TodaySummary): List<PlanItemUi> {
        if (s.hasIllness) {
            return listOf(
                PlanItemUi(
                    type = "meal",
                    title = "晚餐：清淡易消化",
                    detail = "小米粥 + 蒸蛋，避免油腻与生冷",
                    kcal = 400,
                ),
            )
        }

        if (s.gap <= 0) {
            return listOf(
                PlanItemUi(
                    type = "exercise",
                    title = "运动：力量训练 30 分钟",
                    detail = "深蹲 + 卧推，注意组间休息",
                    kcal = 200,
                ),
            )
        }

        val items = mutableListOf<PlanItemUi>()

        if (s.gap >= 600) {
            items += PlanItemUi(
                type = "meal",
                title = "晚餐：米饭 + 蛋白质",
                detail = "熟米饭 200g + 鸡胸或牛肉 150g + 一份绿叶菜",
                kcal = 650,
            )
        } else {
            items += PlanItemUi(
                type = "meal",
                title = "晚餐：正常一份主食",
                detail = "面食或米饭一份 + 一个鸡蛋",
                kcal = 450,
            )
        }

        val remain = s.gap - items.sumOf { it.kcal }
        if (remain > 200) {
            items += PlanItemUi(
                type = "meal",
                title = "加餐：睡前补充",
                detail = "蛋白粉 1 勺 + 香蕉 2 根",
                kcal = 400,
            )
        }

        return items
    }

    private fun buildNote(s: TodaySummary): String = when {
        s.hasIllness -> "今天记录了不适，计划已改为清淡饮食，暂不安排高强度运动。"
        s.recordCount == 0 -> "今天还没有记录，下面按默认目标给出建议。"
        s.gap <= 0 -> "今天已达标，可以安排一次力量训练。"
        s.gap >= 1500 -> "缺口较大，建议分成晚餐和加餐两次补上。"
        else -> "按当前缺口给出了具体数量和热量，照着吃即可。"
    }

    private fun buildLocalReview(s: TodaySummary): String = when {
        s.recordCount == 0 -> "今天还没有记录，无法复盘。去记一笔或点上方刷新。"
        s.gap <= 0 -> "今天摄入 ${s.kcalIn} kcal，达到目标 ${s.target} kcal，缺口已补上。"
        else -> "今天摄入 ${s.kcalIn} kcal，目标 ${s.target} kcal，还差 ${s.gap} kcal 未补上。"
    }

    /**
     * 「记一笔」：照着建议吃了，点一下直接转成记录（UI 设计方案 8.4）。
     * 这是全 App 摩擦最低的路径 —— 不用打字就完成记录。
     */
    fun logSuggestion(item: PlanItemUi) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val dayStart = db.settingsDao().get("day_start_hour")?.toIntOrNull() ?: 4
            val raw = "${item.title}（${item.detail}）"

            db.eventDao().insertIgnore(
                com.healix.app.db.EventEntity(
                    clientEventId = java.util.UUID.randomUUID().toString(),
                    ts = now,
                    dayKey = com.healix.app.parse.dayKeyOf(now, dayStart),
                    rawText = raw,
                    type = item.type,
                    timeHint = "",
                    foods = "[]",
                    exercise = if (item.type == "exercise") item.title else "",
                    amount = item.detail,
                    kcal = item.kcal,
                    symptom = "",
                    weightKg = 0.0,
                    sleepH = 0.0,
                    source = "ai_suggestion",
                    parseStatus = "done",
                    origin = "ai_suggestion",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            reload()
        }
    }
}
