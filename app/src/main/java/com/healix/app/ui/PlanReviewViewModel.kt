package com.healix.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.db.GoalDefaults
import com.healix.app.db.SettingsKeys
import com.healix.app.repo.parseFoodsJson
import com.healix.app.rules.FoodPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 计划页三个文字 Tab（计划 | 训练 | 回顾，规范 §4.3）。 */
enum class PlanTab { PLAN, TRAINING, REVIEW }

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
    /** 行动条三要素之二：大概多久（如「约 20 分钟」）；空串显示「——」。 */
    val duration: String = "",
    /** 行动条三要素之三：一句为什么是现在（F8）。空串不显示。 */
    val whyNow: String = "",
)

data class ReviewUiState(
    val kcalIn: Int = 0,
    val kcalOut: Int = 0,
    val weightKg: Double = 0.0,
    val content: String = "",
)

/** 训练 Tab UI 状态（规范 §9.5）。 */
data class TrainingUiState(
    val hasPlan: Boolean = false,
    val generating: Boolean = false,
    val failed: Boolean = false,
    /** ai | fallback —— fallback 时页顶显示「简化模式 · 本地生成」 */
    val source: String = TrainingPlanner.SOURCE_FALLBACK,
    val plan: TrainingPlan? = null,
    val sessionsGoal: Int = GoalDefaults.TRAIN_SESSIONS_PER_WEEK,
    val goalLabel: String = "",
    /** 本周已记录的训练日 dow 集合（按钮据此变「已记录」）。 */
    val completed: Set<Int> = emptySet(),
    val todayDow: Int = 1,
)

/**
 * 计划 / 训练 / 复盘 ViewModel。
 *
 * 训练 Tab 的职责：
 * - 周计划整周缓存（[TrainingPlanner.loadOrGenerate]）—— 控制 AI 成本的关键
 * - 「记一笔」直写（[logTraining]）+ 5 秒撤销（[undo]），与通知栏/首页同一套心智
 *
 * ⚠️ 本类原有的 plan / review 为**本地规则生成**（AI 未接入时的兜底，不能空白）。
 */
class PlanReviewViewModel(app: Application) : AndroidViewModel(app) {

    private val container = HealixApp.from(app)
    private val db = container.database
    private val planner = TrainingPlanner(app)

    private val _plan = MutableStateFlow(PlanUiState())
    val plan: StateFlow<PlanUiState> = _plan.asStateFlow()

    private val _review = MutableStateFlow(ReviewUiState())
    val review: StateFlow<ReviewUiState> = _review.asStateFlow()

    private val _tab = MutableStateFlow(PlanTab.PLAN)
    val tab: StateFlow<PlanTab> = _tab.asStateFlow()

    private val _training = MutableStateFlow(TrainingUiState())
    val training: StateFlow<TrainingUiState> = _training.asStateFlow()

    /** 撤销条载荷。**只有真的落库成功才发**（与首页/通知栏同一口径）。 */
    private val _undo = MutableSharedFlow<UndoPayload>(extraBufferCapacity = 4)
    val undo: SharedFlow<UndoPayload> = _undo.asSharedFlow()

    init {
        reload()
        loadTraining()
    }

    fun showTab(tab: PlanTab) {
        _tab.value = tab
    }

    /**
     * 重新生成（计划 Tab 的刷新按钮）。
     *
     * 训练 Tab：只重新**读缓存**，不触发模型调用 —— 整周缓存是成本纪律，
     * 要重新生成必须走空态里的「生成本周计划」按钮（[generateTraining]）。
     */
    fun refresh() {
        reload()
        loadTraining()
    }

    // ------------------------------------------------------------------
    // 训练 Tab
    // ------------------------------------------------------------------

    /** 读本周计划（缓存优先，不调模型）。 */
    private fun loadTraining() {
        viewModelScope.launch(Dispatchers.IO) {
            val goal = runCatching { planner.goalLabel() }.getOrDefault("")
            val sessions = runCatching { planner.sessionsGoal() }
                .getOrDefault(GoalDefaults.TRAIN_SESSIONS_PER_WEEK)
            val plan = runCatching { planner.loadOrGenerate(force = false) }.getOrNull()
            val completed = plan?.let {
                runCatching { planner.completedDows(it) }.getOrDefault(emptySet())
            } ?: emptySet()

            _training.value = _training.value.copy(
                hasPlan = plan != null,
                generating = false,
                failed = false,
                source = plan?.source ?: TrainingPlanner.SOURCE_FALLBACK,
                plan = plan,
                sessionsGoal = sessions,
                goalLabel = goal,
                completed = completed,
                todayDow = planner.todayDow(),
            )
        }
    }

    /**
     * 生成 / 重新生成本周计划（**唯一会调模型的入口**）。
     *
     * AI 失败时 [TrainingPlanner] 内部会返回本地兜底计划（`source = fallback`），
     * 因此正常情况下 plan 一定非空、界面照常可用；只有兜底也失败（异常）时
     * 才进入 `failed` 态。
     */
    fun generateTraining() {
        viewModelScope.launch(Dispatchers.IO) {
            _training.value = _training.value.copy(generating = true, failed = false)

            val plan = runCatching { planner.loadOrGenerate(force = true) }.getOrNull()
            if (plan == null) {
                _training.value = _training.value.copy(generating = false, failed = true)
                return@launch
            }
            val completed = runCatching { planner.completedDows(plan) }.getOrDefault(emptySet())
            _training.value = _training.value.copy(
                generating = false,
                failed = false,
                hasPlan = true,
                source = plan.source,
                plan = plan,
                completed = completed,
                todayDow = planner.todayDow(),
            )
        }
    }

    /**
     * 「记一笔」：按计划原文**直写**一条 `exercise` 记录，不弹确认、不打字。
     *
     * 依据 PRD §15.6：写入由用户的**陈述**触发 → 直接写；由 AI 的**推测**触发
     * → 必须确认。这里是他自己点的按钮、内容是 App 给的，意图 100% 明确，
     * 所以与通知栏录入一样「直写 + 5 秒撤销」。**不要**和对话页 `propose_log` 合并。
     */
    fun logTraining(dow: Int) {
        val plan = _training.value.plan ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val day = plan.days.firstOrNull { it.dow == dow && !it.isRest } ?: return@launch
            val clientEventId = runCatching { planner.logPlanDay(day) }.getOrNull()
                ?: return@launch

            val completed = runCatching { planner.completedDows(plan) }
                .getOrDefault(emptySet())
            _training.value = _training.value.copy(completed = completed)

            _undo.tryEmit(
                UndoPayload(
                    clientEventId = clientEventId,
                    typeName = getApplication<Application>().getString(R.string.type_exercise),
                    valueText = day.title,
                    totalCount = 1,
                ),
            )
        }
    }

    /** 撤销一条刚写入的训练记录（物理删除 —— 刚写就反悔，不留软删痕迹）。 */
    fun undo(clientEventId: String) {
        val plan = _training.value.plan
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { db.eventDao().deleteByClientId(clientEventId) }
            if (plan != null) {
                val completed = runCatching { planner.completedDows(plan) }
                    .getOrDefault(emptySet())
                _training.value = _training.value.copy(completed = completed)
            }
        }
    }

    // ------------------------------------------------------------------
    // 计划 / 复盘（沿用既有本地规则实现）
    // ------------------------------------------------------------------

    private fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            val summary = TodaySummary.build(getApplication())

            // 行动条数据源（F8）：食物来自 F7 常吃池，器材/疼痛/作息来自 F6 画像
            val pool = runCatching { FoodPool.build(getApplication()) }.getOrDefault(emptyList())
            val gear = listFrom(db.settingsDao().get(SettingsKeys.PROFILE_GEAR))
            val pain = listFrom(db.settingsDao().get(SettingsKeys.PROFILE_PAIN))
            val bedTime = db.settingsDao().get(SettingsKeys.PROFILE_SLEEP_BED).orEmpty()

            // ── 计划：本地规则生成（AI 未接入时的兜底，不能空白）─────────
            val note = buildNote(summary)
            _plan.value = PlanUiState(
                items = buildLocalPlan(summary, pool, gear, pain, bedTime),
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

    /** JSON 数组字符串 → 列表（防御性，解析失败/空串给空列表）。 */
    private fun listFrom(json: String?): List<String> = parseFoodsJson(json.orEmpty())

    /**
     * 本地规则计划（功能补充 1.7）+ 行动条三要素（功能清单 2 F8）：
     * **做什么（用你有什么）+ 大概多久 + 一句为什么是现在**。
     * 「用你有什么」：食物取自 F7 常吃池、器材取自 F6 画像；
     * 疼痛部位（F9）存在时不给任何部位训练，改恢复性建议。
     */
    private fun buildLocalPlan(
        s: TodaySummary,
        pool: List<String>,
        gear: List<String>,
        pain: List<String>,
        bedTime: String,
    ): List<PlanItemUi> {
        if (s.hasIllness) {
            return listOf(
                PlanItemUi(
                    type = "meal",
                    title = "晚餐：清淡易消化",
                    detail = "小米粥 + 蒸蛋，避免油腻与生冷",
                    kcal = 400,
                    duration = "约 15 分钟",
                    whyNow = "今天有生病记录，先恢复再训练",
                ),
            )
        }

        if (s.gap <= 0) {
            return listOf(tailItem(gear, pain))
        }

        val items = mutableListOf<PlanItemUi>()

        if (s.gap >= 600) {
            items += PlanItemUi(
                type = "meal",
                title = "晚餐：主食 + 蛋白质",
                detail = mealDetail(pool, "熟米饭 200g + 鸡胸或牛肉 150g + 一份绿叶菜"),
                kcal = 650,
                duration = "约 20 分钟",
                whyNow = "还差 ${s.gap} kcal，这一顿补上一大半",
            )
        } else {
            items += PlanItemUi(
                type = "meal",
                title = "晚餐：正常一份主食",
                detail = mealDetail(pool, "面食或米饭一份 + 一个鸡蛋"),
                kcal = 450,
                duration = "约 15 分钟",
                whyNow = "缺口不大，一顿补齐",
            )
        }

        val remain = s.gap - items.sumOf { it.kcal }
        if (remain > 200) {
            items += PlanItemUi(
                type = "meal",
                title = "加餐：睡前补充",
                detail = mealDetail(pool, "蛋白粉 1 勺 + 香蕉 2 根"),
                kcal = 400,
                duration = "约 5 分钟",
                whyNow = "离睡眠还有几个小时，小份加餐好消化",
            )
        }

        // 睡眠行动条：近 3 日平均睡眠不足 6.5 小时才提（有数据才建议，不猜）
        if (s.sleepLast3.size >= 2 && s.sleepLast3.average() < 6.5) {
            items += PlanItemUi(
                type = "sleep",
                title = "睡：${bedTime.ifBlank { "定点" }} 前放下手机",
                detail = "今晚按目标就寝时间执行",
                kcal = 0,
                duration = "——",
                whyNow = "近 3 天平均只睡 ${trim(s.sleepLast3.average())} 小时",
            )
        }

        return items
    }

    /** 热量已达标时的收尾条目：疼痛（F9）→ 恢复；否则训练（器材来自 F6 画像）。 */
    private fun tailItem(gear: List<String>, pain: List<String>): PlanItemUi = when {
        pain.isNotEmpty() -> PlanItemUi(
            type = "sleep",
            title = "恢复：补水 + 早睡",
            detail = "疼痛/不适部位（${pain.joinToString("、")}）相关动作今天全部避开",
            kcal = 0,
            duration = "——",
            whyNow = "恢复优先于训练（疼痛避让）",
        )
        else -> PlanItemUi(
            type = "exercise",
            title = "练：力量训练 30 分钟",
            detail = if (gear.isEmpty()) {
                "深蹲 + 卧推，注意组间休息"
            } else {
                "用你有的器材：${gear.joinToString("、")}"
            },
            kcal = 200,
            duration = "约 30 分钟",
            whyNow = "今天热量已达标，正好安排训练",
        )
    }

    /**
     * 食物建议明细：常吃池非空时优先从池里选（F7「他吃过 = 他买得到」；
     * F8 验收要求行动条里的食物能在池子里找到来源），按平常的量，不编克数。
     * 池为空（记录太少）回落到既有建议并保持原样。
     */
    private fun mealDetail(pool: List<String>, fallback: String): String =
        if (pool.isEmpty()) fallback
        else "优先常吃：${pool.take(3).joinToString("、")}（按平常的量）"

    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.1f", v)

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
     * 计划 Tab 的「记一笔」：照着建议吃了，点一下直接转成记录（UI 设计方案 8.4）。
     * 这是全 App 摩擦最低的路径 —— 不用打字就完成记录。
     */
    fun logSuggestion(item: PlanItemUi) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val dayStart = db.settingsDao().get(com.healix.app.db.SettingsKeys.DAY_START)
                ?.toIntOrNull() ?: 4
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
