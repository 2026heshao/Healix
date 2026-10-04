package com.healix.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.db.GoalDefaults
import com.healix.app.db.SettingsKeys
import com.healix.app.parse.DEFAULT_DAY_START_HOUR
import com.healix.app.parse.dayKeyOf
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
    val items: List<TimelineItem> = emptyList(),
    val note: String = "",
    val gapLeft: Int = 0,
    /** ai | fallback */
    val source: String = TrainingPlanner.SOURCE_FALLBACK,
    val generatedAt: Long = 0L,
    /** 正在调 AI 重排（按钮置「更新中」并禁用）。 */
    val updating: Boolean = false,
    /** 更新失败（保留旧计划或已走兜底）。 */
    val failed: Boolean = false,
    /** 展示的是缓存里的上一版（更新失败时据此区分文案：有旧版 vs 刚建的兜底）。 */
    val fromCache: Boolean = false,
    /** 抽取桶配额不足（不调网，保留旧计划，仅提示）。 */
    val quotaExhausted: Boolean = false,
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
 * - 训练 Tab：周计划整周缓存（[TrainingPlanner.loadOrGenerate]）—— 控制 AI 成本的关键
 * - 「记一笔」直写（[logTraining]）+ 5 秒撤销（[undo]），与通知栏/首页同一套心智
 * - **计划 Tab：单条时间轴**。打开只读缓存（[PlanGenerator.loadCached]，force=false，
 *   绝不调 AI）；用户点「更新」才调模型（[updatePlan]），失败有缓存保留、无缓存兜底。
 */
class PlanReviewViewModel(app: Application) : AndroidViewModel(app) {

    private val container = HealixApp.from(app)
    private val db = container.database
    private val planner = TrainingPlanner(app)
    private val generator = PlanGenerator(app)

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
     * 重新读本地数据（计划 Tab 的「刷新」按钮）。
     *
     * - 计划 Tab：重读缓存 / 本地兜底（**不调 AI**）。
     * - 训练 Tab：只重新**读缓存**，不触发模型调用 —— 整周缓存是成本纪律，
     *   要重新生成必须走空态里的「生成本周计划」按钮（[generateTraining]）。
     */
    fun refresh() {
        reload()
        loadTraining()
    }

    // ------------------------------------------------------------------
    // 计划 Tab（时间轴）
    // ------------------------------------------------------------------

    /**
     * 打开计划 Tab 的数据加载：**只读缓存（force=false，绝不调 AI）**。
     * 无缓存 → 本地时间轴兜底（不落库、不调网）。
     */
    private fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            val summary = runCatching { TodaySummary.build(getApplication()) }.getOrNull()
                ?: return@launch

            // ── 复盘：本地聚合（AI 未接入时用已有数字 + 说明）────────────
            _review.value = ReviewUiState(
                kcalIn = summary.kcalIn,
                kcalOut = summary.kcalOut,
                weightKg = summary.weightKg,
                content = buildLocalReview(summary),
            )

            // ── 计划：只读缓存；无缓存走本地时间轴兜底 ──────────────────
            val gapLeft = if (summary.gap > 0) summary.gap else 0
            val key = runCatching { generator.todayKey() }.getOrNull()
            val cached = key?.let { runCatching { generator.loadCached(it) }.getOrNull() }
            val result = cached ?: runCatching { generator.localTimeline(summary) }.getOrNull()
            _plan.value = if (result == null) {
                PlanUiState(gapLeft = gapLeft)
            } else {
                PlanUiState(
                    items = result.items,
                    note = result.note,
                    gapLeft = gapLeft,
                    source = result.source,
                    generatedAt = result.generatedAt,
                    updating = false,
                    failed = false,
                )
            }
        }
    }

    /**
     * 点「更新」：调 AI 重排今日时间轴（**唯一的模型调用入口**）。
     *
     * 先过配额护栏（`PURPOSE_PLAN` 归入抽取桶）：不足则**不调网**、保留旧计划、
     * 仅置 [PlanUiState.quotaExhausted] 让 UI 提示。AI 失败且**有缓存**时保留缓存
     * （不清空），无缓存时 [PlanGenerator] 内部落本地兜底。
     */
    fun updatePlan() {
        viewModelScope.launch(Dispatchers.IO) {
            _plan.value = _plan.value.copy(updating = true, failed = false, quotaExhausted = false)

            if (!container.quotaGuard.canExtract()) {
                _plan.value = _plan.value.copy(updating = false, quotaExhausted = true)
                return@launch
            }

            val key = runCatching { generator.todayKey() }.getOrNull()
            val summary = runCatching { TodaySummary.build(getApplication()) }.getOrNull()
            if (key == null || summary == null) {
                _plan.value = _plan.value.copy(updating = false, failed = true)
                return@launch
            }

            val result = runCatching { generator.update(key, summary) }.getOrNull()
            if (result == null) {
                _plan.value = _plan.value.copy(updating = false, failed = true)
                return@launch
            }
            _plan.value = _plan.value.copy(
                items = result.items,
                note = result.note,
                source = result.source,
                generatedAt = result.generatedAt,
                updating = false,
                failed = result.failed,
                fromCache = result.fromCache,
                quotaExhausted = result.quotaExhausted,
            )
        }
    }

    /**
     * 计划 Tab 的「记一笔」：照着建议吃了/练了，点一下直接转成记录。
     * 这是全 App 摩擦最低的路径 —— 不用打字就完成记录。
     *
     * ⚠️ **仅 `type ∈ {meal, exercise}` 可记**（按钮也只对这两类显示）：
     *    `sleep`/`habit` 条目没有对应的事件语义，直写会造出 `sleepH = 0.0` 的
     *    **假睡眠数据**，污染 `TodaySummary.sleepH` 与 `HealthAggregator`。
     */
    fun logSuggestion(item: TimelineItem) {
        if (item.type != "meal" && item.type != "exercise") return
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val dayStart = db.settingsDao().get(SettingsKeys.DAY_START)?.toIntOrNull()
                ?: DEFAULT_DAY_START_HOUR
            val raw = "${item.title}（${item.detail}）"

            db.eventDao().insertIgnore(
                com.healix.app.db.EventEntity(
                    clientEventId = java.util.UUID.randomUUID().toString(),
                    ts = now,
                    dayKey = dayKeyOf(now, dayStart),
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
    // 复盘（本地聚合）
    // ------------------------------------------------------------------

    private fun buildLocalReview(s: TodaySummary): String = when {
        s.recordCount == 0 -> "今天还没有记录，无法复盘。去记一笔或点上方刷新。"
        s.gap <= 0 -> "今天摄入 ${s.kcalIn} kcal，达到目标 ${s.target} kcal，缺口已补上。"
        else -> "今天摄入 ${s.kcalIn} kcal，目标 ${s.target} kcal，还差 ${s.gap} kcal 未补上。"
    }
}
