package com.healix.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.db.GoalDefaults
import com.healix.app.db.SettingEntity
import com.healix.app.db.SettingsKeys
import com.healix.app.net.NetworkStatus
import com.healix.app.parse.dayKeyOf
import com.healix.app.parse.dayStartHourOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 计划页可见 Tab（v8 需求 7 收敛为两个）。
 *
 * 原「训练」Tab 已并入「计划」——训练日与今日计划条目**同轴渲染**（见 [TimelineMerger]）。
 */
enum class PlanTab { PLAN, REVIEW }

/**
 * 计划页 UI 状态（v8 需求 7：单条统一时间轴）。
 *
 * `entries` 为空且 `hasTraining = false` 时，页面上只剩一条低调的「生成本周计划」入口。
 */
data class PlanUiState(
    /** 统一时间轴（本周 7 天训练日 + 今日计划），已按 `(dayIndex, sortKey)` 排序。 */
    val entries: List<TimelineEntry> = emptyList(),
    /** 计划底部一句话重点（来自 `plan_json.note`）。 */
    val note: String = "",
    /** 还差多少 kcal 达标（`<= 0` 显示「已达标」）。 */
    val gapLeft: Int = 0,
    /** ai | fallback */
    val source: String = TrainingPlanner.SOURCE_FALLBACK,
    val generatedAt: Long = 0L,
    /** 自动重排在途（页顶提示，非按钮态）。 */
    val updating: Boolean = false,
    /** 自动重排失败（保留旧计划或已走兜底）。 */
    val failed: Boolean = false,
    /** 展示的是缓存里的上一版（失败时据此区分文案：有旧版 vs 刚建的兜底）。 */
    val fromCache: Boolean = false,
    /** 抽取桶配额不足（不调网，保留旧计划，仅提示）。 */
    val quotaExhausted: Boolean = false,
    /** 本周训练计划是否已生成（false → 时间轴尾部给「生成本周计划」低调文字入口）。 */
    val hasTraining: Boolean = false,
    /** 正在生成本周训练计划（文字入口置「生成中」并禁用）。 */
    val generatingTraining: Boolean = false,
    /** 训练计划生成失败（本地兜底也失败）。 */
    val trainingFailed: Boolean = false,
    /** 本周训练日数（非休息日）—— 汇总行「本周 N 练」。 */
    val trainingSessions: Int = 0,
    /** 本周已完成训练次数 —— 汇总行「已完成 M」。 */
    val trainingDone: Int = 0,
    /** 主目标展示名（增重 / 减重 / 保持），训练空态文案用。 */
    val goalLabel: String = "",
    /** 每周训练次数目标（兜底 [GoalDefaults.TRAIN_SESSIONS_PER_WEEK]）。 */
    val sessionsGoal: Int = GoalDefaults.TRAIN_SESSIONS_PER_WEEK,
    /** 本周训练重点一句话（`TrainingPlan.note`，空串不显示）—— 原训练 Tab 页脚迁移至此。 */
    val trainingFocus: String = "",
    /** 今日 ISO dow（1 = 周一 … 7 = 周日），用于「今天」标记与日头渲染。 */
    val todayDow: Int = 1,
)

data class ReviewUiState(
    val kcalIn: Int = 0,
    val kcalOut: Int = 0,
    val weightKg: Double = 0.0,
    val content: String = "",
)

/**
 * 计划 / 复盘 ViewModel（v8 需求 7 重构）。
 *
 * ── 与旧版的差别 ───────────────────────────────────────────────────
 * - **去掉「训练」Tab 与手动「更新」「刷新」**：计划与训练合并为一条按周铺开的
 *   统一时间轴（[TimelineMerger]），本地重算 **0 AI**，随数据变化自动刷新。
 * - **AI 重排改后台自动**（[autoRerankIfDue]）：同一 `day_key` 至多触发 1 次，
 *   门槛 = 无缓存或缓存 > 12h + provider 可用 + 在线 + 配额允许；触发前先落
 *   [SettingsKeys.PLAN_AUTO_RERANK_DAY] 标记（防并发/重复）。
 *
 * ── 成本纪律（不变式保持）─────────────────────────────────────────
 * 打开页面 / 数据变化 / 记一笔 / 跨日回前台，全部走**本地**路径（0 AI）。
 * 唯一会调模型的自动路径是 [autoRerankIfDue]，一次 provider 往返**恰好 1 行**
 * `llm_calls`（由 `PlanGenerator.update()` 的移位埋点写法保证）。
 *
 * ── 生命周期 ─────────────────────────────────────────────────────
 * 由 `PlanReviewFragment` 以 `ViewModelProvider(this)`（**Fragment 作用域**）持有 →
 * 二级页退出即 `onCleared()`，`viewModelScope` 取消在途自动重排，避免退后台空跑。
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

    /** 撤销条载荷。**只有真的落库成功才发**（与首页/通知栏同一口径）。 */
    private val _undo = MutableSharedFlow<UndoPayload>(extraBufferCapacity = 4)
    val undo: SharedFlow<UndoPayload> = _undo.asSharedFlow()

    init {
        reload()
        observeToday()
        autoRerankIfDue()
    }

    fun showTab(tab: PlanTab) {
        _tab.value = tab
    }

    companion object {
        /** 自动重排的"陈旧"阈值：超过 12 小时未重新生成才允许后台重排。 */
        private const val AUTO_RERANK_INTERVAL_MS = 12L * 60 * 60 * 1000
    }

    // ------------------------------------------------------------------
    // 本地重算（0 AI）
    // ------------------------------------------------------------------

    /** 本地全量重算（0 AI）。供数据变化 / 页面重建调用，也是 init 的首帧数据来源。 */
    fun reload() {
        viewModelScope.launch(Dispatchers.IO) { computeAndEmit() }
    }

    /**
     * 今日记录数 Room Flow → 数据一变就**本地**重算（0 AI）。
     *
     * 训练日的「已记录」态、缺口行、复盘统计都靠这条触发刷新；用户无需任何手动刷新。
     * ⚠️ 只观察**今日** day_key；跨零点后本 Flow 的键会过期，由 [reload]（页面重建 /
     *    回前台）兜住 —— 计划页是二级页，每次进入都会重建并重新订阅。
     */
    private fun observeToday() {
        viewModelScope.launch(Dispatchers.IO) {
            val key = runCatching { generator.todayKey() }.getOrNull() ?: return@launch
            runCatching {
                db.eventDao().observeCountByDay(key).collect { computeAndEmit() }
            }
        }
    }

    /**
     * 读齐本地数据 → 计算统一时间轴与复盘，**不触碰**在途标志位。
     *
     * ⚠️ 只 `copy()` 数据字段，**绝不整对象重建**：整对象重建会与 [autoRerankIfDue]
     *    在途的 `updating` 写入竞争（在途守卫失效）。`updating` / `generatingTraining`
     *    / `trainingFailed` 一律不在本方法里写。
     */
    private suspend fun computeAndEmit() {
        val summary = runCatching { TodaySummary.build(getApplication()) }.getOrNull()
            ?: return

        // ── 复盘：本地聚合（无 AI 时用已有数字 + 说明）────────────────
        _review.value = ReviewUiState(
            kcalIn = summary.kcalIn,
            kcalOut = summary.kcalOut,
            weightKg = summary.weightKg,
            content = buildLocalReview(summary),
        )

        // ── 今日计划：只读缓存（force=false，绝不调 AI）；无缓存走本地时间轴兜底 ──
        val gapLeft = if (summary.gap > 0) summary.gap else 0
        val key = runCatching { generator.todayKey() }.getOrNull()
        val cached = key?.let { runCatching { generator.loadCached(it) }.getOrNull() }
        val result = cached ?: runCatching { generator.localTimeline(summary) }.getOrNull()

        // ── 本周训练：缓存优先（force=false，不调模型）──────────────────
        val training = runCatching { planner.loadOrGenerate(force = false) }.getOrNull()
        val completed = training?.let {
            runCatching { planner.completedDows(it) }.getOrDefault(emptySet())
        } ?: emptySet()
        val sessionsGoal = runCatching { planner.sessionsGoal() }
            .getOrDefault(GoalDefaults.TRAIN_SESSIONS_PER_WEEK)
        val goalLabel = runCatching { planner.goalLabel() }.getOrDefault("")

        // ── 合并统一时间轴（纯 UI 层，0 AI）────────────────────────────
        val todayDow = planner.todayDow()
        val entries = TimelineMerger.merge(
            ctx = getApplication(),
            plan = result?.items.orEmpty(),
            training = training,
            completedDows = completed,
            todayDow = todayDow,
        )

        _plan.value = _plan.value.copy(
            entries = entries,
            note = result?.note.orEmpty(),
            gapLeft = gapLeft,
            source = result?.source ?: _plan.value.source,
            generatedAt = result?.generatedAt ?: 0L,
            failed = false,
            fromCache = cached != null,
            quotaExhausted = !container.quotaGuard.canExtract(),
            hasTraining = training != null,
            trainingSessions = training?.days?.count { !it.isRest } ?: 0,
            trainingDone = completed.size,
            goalLabel = goalLabel,
            sessionsGoal = sessionsGoal,
            trainingFocus = training?.note.orEmpty(),
            todayDow = todayDow,
        )
    }

    // ------------------------------------------------------------------
    // 后台自动重排（≤ 1 次 / day_key；一次往返恰好 1 行 llm_calls）
    // ------------------------------------------------------------------

    /**
     * 需求 7：后台自动重排今日计划（**唯一会自动调模型**的入口）。
     *
     * 全部门槛满足才调（任一不满足 → 静默跳过，保持本地缓存/兜底）：
     * 1. 同一 `day_key` 未自动触发过（[SettingsKeys.PLAN_AUTO_RERANK_DAY] ≠ 今日）；
     * 2. 无缓存，或缓存 `generatedAt` 距今 > 12 小时；
     * 3. `provider` 已配置且可用，且当前在线；
     * 4. 抽取桶配额允许（`QuotaGuard.canExtract()`）。
     *
     * ⚠️ 触发**前**先落标记（`put(PLAN_AUTO_RERANK_DAY, todayKey)`）：先落标记再调网，
     *    防并发/重复触发；即使后续调网失败也不再重试（日节流，代价可控）。
     * ⚠️ 用 [NetworkStatus.isOnline] 先挡断网：离线调 `update()` 会白等整条退避链。
     */
    private fun autoRerankIfDue() {
        viewModelScope.launch(Dispatchers.IO) {
            val key = runCatching { generator.todayKey() }.getOrNull() ?: return@launch
            if (db.settingsDao().get(SettingsKeys.PLAN_AUTO_RERANK_DAY) == key) return@launch

            val cached = runCatching { generator.loadCached(key) }.getOrNull()
            val stale = cached == null ||
                System.currentTimeMillis() - cached.generatedAt > AUTO_RERANK_INTERVAL_MS
            if (!stale) return@launch

            if (!container.quotaGuard.canExtract()) return@launch

            val config = runCatching { container.eventRepository.loadProviderConfig() }.getOrNull()
            if (config == null || !config.isUsable() || !NetworkStatus.isOnline(getApplication())) {
                return@launch
            }

            // 先落标记再调网（防并发/重复触发；本日不再自动重排）。
            runCatching {
                db.settingsDao().put(
                    SettingEntity(key = SettingsKeys.PLAN_AUTO_RERANK_DAY, value = key),
                )
            }

            val summary = runCatching { TodaySummary.build(getApplication()) }.getOrNull()
                ?: return@launch

            _plan.value = _plan.value.copy(updating = true, failed = false)
            val result = runCatching { generator.update(key, summary) }.getOrNull()
            if (result == null) {
                _plan.value = _plan.value.copy(updating = false, failed = true)
                return@launch
            }
            // 重算时间轴（读刚落库的缓存），再把本次重排的结果标志位盖回去。
            computeAndEmit()
            _plan.value = _plan.value.copy(
                updating = false,
                failed = result.failed,
                fromCache = result.fromCache,
            )
        }
    }

    // ------------------------------------------------------------------
    // 写：记一笔（计划条目 / 训练日）+ 撤销
    // ------------------------------------------------------------------

    /**
     * 计划条目的「记一笔」：照着建议吃了/练了，点一下直接转成记录。
     * 这是全 App 摩擦最低的路径 —— 不用打字就完成记录。
     *
     * ⚠️ **仅 `type ∈ {meal, exercise}` 可记**（按钮也只对这两类显示）：
     *    `sleep`/`habit` 条目没有对应的事件语义，直写会造出 `sleepH = 0.0` 的
     *    **假睡眠数据**，污染 `TodaySummary.sleepH` 与 `HealthAggregator`。
     */
    fun logSuggestion(entry: TimelineEntry) {
        if (entry.type != "meal" && entry.type != "exercise") return
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            // 日界线走唯一入口 dayStartHourOf（§1 收口）。
            val dayStart = dayStartHourOf(db.settingsDao().get(SettingsKeys.DAY_START))
            // 口径 (a)：行为不变 —— kcal 照写（`entry.kcal`）。注意它可能来自**兜底计划的
            // 估算**（见 PlanGenerator.localTimeline 的 KDoc）；点「记一笔」前来源行已标注估算。
            val raw = "${entry.title}（${entry.detail}）"

            db.eventDao().insertIgnore(
                com.healix.app.db.EventEntity(
                    clientEventId = java.util.UUID.randomUUID().toString(),
                    ts = now,
                    dayKey = dayKeyOf(now, dayStart),
                    rawText = raw,
                    type = entry.type,
                    timeHint = "",
                    foods = "[]",
                    exercise = if (entry.type == "exercise") entry.title else "",
                    amount = entry.detail,
                    kcal = entry.kcal,
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
            computeAndEmit()
        }
    }

    /**
     * 训练条目的「记一笔」：按计划原文**直写**一条 `exercise` 记录，不弹确认、不打字。
     *
     * 依据 PRD §15.6：写入由用户的**陈述**触发 → 直接写；由 AI 的**推测**触发
     * → 必须确认。这里是他自己点的按钮、内容是 App 给的，意图 100% 明确，
     * 所以与通知栏录入一样「直写 + 5 秒撤销」。
     *
     * @param dow ISO 星期（1 = 周一 … 7 = 周日）；由 `TimelineEntry.dayIndex + 1` 得来。
     */
    fun logTraining(dow: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val training = runCatching { planner.loadOrGenerate(force = false) }.getOrNull()
                ?: return@launch
            val day = training.days.firstOrNull { it.dow == dow && !it.isRest } ?: return@launch
            val clientEventId = runCatching { planner.logPlanDay(day) }.getOrNull()
                ?: return@launch

            _undo.tryEmit(
                UndoPayload(
                    clientEventId = clientEventId,
                    typeName = getApplication<Application>().getString(R.string.type_exercise),
                    valueText = day.title,
                    totalCount = 1,
                ),
            )
            computeAndEmit()
        }
    }

    /** 撤销一条刚写入的记录（物理删除 —— 刚写就反悔，不留软删痕迹）。 */
    fun undo(clientEventId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { db.eventDao().deleteByClientId(clientEventId) }
            computeAndEmit()
        }
    }

    // ------------------------------------------------------------------
    // 生成本周训练计划（唯一会调模型的**用户**入口）
    // ------------------------------------------------------------------

    /**
     * 生成 / 重新生成本周训练计划。
     *
     * AI 失败时 [TrainingPlanner] 内部会返回本地兜底计划（`source = fallback`），
     * 因此正常情况下 plan 一定非空、界面照常可用；只有兜底也失败（异常）时
     * 才进入 `trainingFailed` 态。
     */
    fun generateTraining() {
        // 在途守卫：**同步**置位 + 同步判。本方法只由主线程按钮点击调用，两次点击在
        // 主线程上天然串行，故无竞态。绝不能把置位留在协程里（Dispatchers.IO）—— UI
        // 要等一次调度才置灰按钮，落在同一帧内的第二次点击会再发起一次训练 AI 调用。
        if (_plan.value.generatingTraining) return
        _plan.value = _plan.value.copy(
            generatingTraining = true,
            trainingFailed = false,
            quotaExhausted = false,
        )

        viewModelScope.launch(Dispatchers.IO) {
            if (!container.quotaGuard.canExtract()) {
                _plan.value = _plan.value.copy(generatingTraining = false, quotaExhausted = true)
                return@launch
            }

            val plan = runCatching { planner.loadOrGenerate(force = true) }.getOrNull()
            if (plan == null) {
                _plan.value = _plan.value.copy(generatingTraining = false, trainingFailed = true)
                return@launch
            }
            _plan.value = _plan.value.copy(generatingTraining = false, trainingFailed = false)
            computeAndEmit()
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
