package com.healix.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.db.BodySignalEntity
import com.healix.app.db.EventEntity
import com.healix.app.db.GoalDefaults
import com.healix.app.db.GoalMetrics
import com.healix.app.db.PresetEntity
import com.healix.app.notify.EventText
import com.healix.app.notify.QuickInputService
import com.healix.app.net.NetworkStatus
import com.healix.app.parse.ParsedEvent
import com.healix.app.repo.EventRepository
import com.healix.app.repo.SOURCE_PRESET
import com.healix.app.repo.SubmitResult
import com.healix.app.rules.HealthAggregator
import com.healix.app.rules.HealthRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 主界面交互状态。
 *
 * - [Queued] 单独一态，因为必须显示预估秒数（规范 3.10）
 * - [Offline] 与 [NotConfigured] **必须分开** —— 两者需要用户做的动作不同：
 *   没网 → 检查 Wi-Fi / 移动数据；没配置 → 去设置页填 API Key。
 *   2026-10-03 前两者被混用（needsConfiguration 被映射成 Offline），
 *   加上 settings 键名分裂 bug，导致每次提交都显示"离线"。
 */
sealed interface MainUiState {
    data object Idle : MainUiState
    data object Parsing : MainUiState
    data class Queued(val seconds: Int) : MainUiState

    /** 设备无可用网络。原文已落 pending，联网后可在列表里点重试。 */
    data object Offline : MainUiState

    /** 有网络但用户还没配置模型服务。引导去设置页，不是错误。 */
    data object NotConfigured : MainUiState
}

/** 汇总区数据。缺口是**本地算术**，不调 AI（UI 设计方案 8.1）。 */
data class MainSummary(
    val kcalIn: Int = 0,
    val kcalOut: Int = 0,
    val target: Int = 2500,
    val gap: Int = 2500,
)

/**
 * 首页多维状态行（设计规范系统 §9.2）。
 *
 * **两态互斥，恒定 48dp** —— 有信号时不是新增一行，而是原地升级。
 * 这样高度恒定、记录列表不被挤压，也不会出现"提示越积越多"的告警墙。
 */
sealed interface HomeStatus {

    /** 摘要态（默认）：`运动 2/3 · 睡眠 7.2h · 体重 58.2kg` */
    data class Summary(val text: String) : HomeStatus

    /** 信号态（有未读 `body_signals`）：`连续 3 天睡眠不足 · 今晚提前 1 小时熄灯` */
    data class Signal(val ruleId: String, val text: String) : HomeStatus

    /** 全部维度无数据。仍可点，进入状态页看空状态（规范 §9.2）。 */
    data object Empty : HomeStatus
}

/**
 * 撤销条的载荷（规范 §9.6，PRD §15.7 的「撤得回」）。
 *
 * 只在本条记录**真的落库成功**后才发 —— 通知栏路径也是这个口径。
 * 失败（未识别）不发撤销条：那条记录已经在列表里以「未识别 · 点此补充」呈现，
 * 并且带着重试入口，再叠一个"撤销"会让用户以为它写进去了。
 */
data class UndoPayload(
    val clientEventId: String,
    val typeName: String,
    val valueText: String,
    /** 一句话拆出多条时的总条数（含首条）。1 = 单条。 */
    val totalCount: Int,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container: HealixApp = HealixApp.from(app)
    private val db = container.database
    private val repo = container.eventRepository

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Idle)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /** 今日 day_key。跨零点时由 refresh() 重新计算。 */
    private val todayKey = MutableStateFlow(LocalDate.now().toString())

    val events: StateFlow<List<EventEntity>> = todayKey
        .flatMapLatest { day -> db.eventDao().observeByDay(day) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val todayCount: StateFlow<Int> = todayKey
        .flatMapLatest { day -> db.eventDao().observeCountByDay(day) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val presets: StateFlow<List<PresetEntity>> = db.presetDao()
        .observeTop(6)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 汇总：摄入 / 消耗 / 目标 / 缺口。目标来自设置页（BMR+盈余本地算）。 */
    val summary: StateFlow<MainSummary> = combine(
        todayKey.flatMapLatest { day -> db.eventDao().observeKcalIn(day) },
        todayKey.flatMapLatest { day -> db.eventDao().observeKcalOut(day) },
        targetKcalFlow(),
    ) { kcalIn, kcalOut, target ->
        MainSummary(kcalIn = kcalIn, kcalOut = kcalOut, target = target, gap = target - kcalIn + kcalOut)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainSummary())

    private fun targetKcalFlow(): StateFlow<Int> =
        db.settingsDao().observe(KEY_TARGET_KCAL)
            .map { it?.toIntOrNull() ?: GoalDefaults.TARGET_KCAL }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalDefaults.TARGET_KCAL)

    // ==================================================================
    // 多维状态行（设计规范系统 §9.2）
    // ==================================================================

    /** 目标值。`metric -> targetValue`，一处取全，避免每个维度各开一个 Flow。 */
    private val goalsData: StateFlow<Map<String, Double>> = db.goalDao()
        .observeActive()
        .map { list -> list.associate { it.metric to it.targetValue } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 隐私开关：隐藏体重数字。隐藏时该维度**整块不显示**（PRD §14.3）。 */
    private val hideWeight: StateFlow<Boolean> = db.settingsDao()
        .observe(KEY_HIDE_WEIGHT)
        .map { it == "true" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 隐私开关：隐藏热量数字。汇总区整块不显示。 */
    val hideKcal: StateFlow<Boolean> = db.settingsDao()
        .observe(KEY_HIDE_KCAL)
        .map { it == "true" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 今日未读信号（`body_signals`，由 [scanSignals] 落库、UNIQUE 去重）。 */
    private val signalsToday: StateFlow<List<BodySignalEntity>> = todayKey
        .flatMapLatest { day -> db.bodySignalDao().observeByDay(day) }
        .map { list -> list.filter { it.acknowledged == 0 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 状态行内容。**两态互斥**：有未读信号就整体切成信号态，不是加一行。
     *
     * 依赖 [events]（今日记录）而不是只在跨零点时重算 —— 记一笔、AI 回填、
     * 点预设都会改变今日记录，状态行必须跟着变。
     */
    val homeStatus: StateFlow<HomeStatus> = combine(
        events,
        todayKey,
        signalsToday,
        goalsData,
        hideWeight,
    ) { todayEvents, day, signals, goals, hideW ->
        val top = signals.minByOrNull { HealthRules.priorityOf(it.ruleId) }
        if (top != null) {
            // 信号态。文案直接用落库时格式化好的 _short（规范 §9.8：≤24 汉字）。
            HomeStatus.Signal(ruleId = top.ruleId, text = top.title)
        } else {
            val text = buildSummaryLine(todayEvents, day, goals, hideW)
            if (text.isEmpty()) HomeStatus.Empty else HomeStatus.Summary(text)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeStatus.Empty)

    /**
     * 拼摘要态文案：固定顺序 `运动 → 睡眠 → 体重`，用 ` · ` 连接。
     *
     * - **无数据的维度不参与拼接**（规范 §9.2），所以可能只有 1–2 项
     * - **不按数值排序**：顺序稳定比"智能排序"重要 —— 用户扫一眼就知道哪个位置是什么
     * - **200% 字号时只保留第 1 项**（规范 §9.10）：首页预算只剩 9dp，
     *   折行会把记录列表挤到 2 行以下，减少项数是"信息密度换可读性"的合理让步
     */
    private suspend fun buildSummaryLine(
        todayEvents: List<EventEntity>,
        day: String,
        goals: Map<String, Double>,
        hideW: Boolean,
    ): String {
        val app = getApplication<Application>()
        val items = mutableListOf<String>()

        // ① 运动 N/M —— 本周已完成次数 ÷ 每周目标次数
        val done = runCatching {
            val monday = LocalDate.parse(day)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
            db.eventDao().countByTypeInRange("exercise", monday, day)
        }.getOrDefault(0)
        val goalSessions = goals[GoalMetrics.SESSIONS_PER_WEEK]?.toInt()
            ?: GoalDefaults.TRAIN_SESSIONS_PER_WEEK
        items += app.getString(R.string.status_dim_training, done, goalSessions)

        // ② 睡眠 N.Nh —— 只取今日已记录的睡眠
        val sleep = todayEvents.firstOrNull { it.type == "sleep" && it.sleepH > 0 }?.sleepH
        if (sleep != null) {
            items += app.getString(R.string.status_dim_sleep, trimNumber(sleep))
        }

        // ③ 体重 N.Nkg —— 隐私开关打开时整块不显示，不是显示 ***
        val weight = todayEvents.firstOrNull { it.type == "body" && it.weightKg > 0 }?.weightKg
        if (weight != null && !hideW) {
            items += app.getString(R.string.status_dim_weight, trimNumber(weight))
        }

        if (items.isEmpty()) return ""
        val fontScale = app.resources.configuration.fontScale
        val kept = if (fontScale >= 1.5f) items.take(1) else items
        return kept.joinToString(app.getString(R.string.status_sep))
    }

    /** 去掉无意义的小数尾巴：58.0 → "58"，58.2 → "58.2"。 */
    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    // ==================================================================
    // 内联撤销条（规范 §9.6 / PRD §15.7）
    // ==================================================================

    private val _undo = MutableSharedFlow<UndoPayload>(extraBufferCapacity = 4)
    val undo: SharedFlow<UndoPayload> = _undo.asSharedFlow()

    /**
     * 撤销一笔：`repo.undo` 走软删除（30 天后由清理任务物理清除），
     * 与通知栏路径的 `UndoReceiver` 同一套语义。
     */
    fun undo(clientEventId: String) {
        viewModelScope.launch { repo.undo(clientEventId) }
    }

    /**
     * 左滑删除（规范 11.3）：与 [undo] 同一软删除管道 —— 删除即撤销，
     * 30 天后由清理任务物理清除，不新增第三种删除语义。
     */
    fun deleteEvent(clientEventId: String) {
        viewModelScope.launch { repo.undo(clientEventId) }
    }

    /**
     * 删除后的「撤销」（11.3 V3）：恢复软删除的记录。
     * ts / day_key 未动，observeByDay（ORDER BY ts DESC）回插后自然在原位。
     */
    fun restoreEvent(clientEventId: String) {
        viewModelScope.launch { repo.restore(clientEventId) }
    }

    /** 由 [SubmitResult] 组装撤销条载荷；失败或没有首条事件时返回 null。 */
    private fun undoPayloadOf(result: SubmitResult): UndoPayload? {
        if (!result.ok) return null
        val first = result.firstEvent ?: return null
        return UndoPayload(
            clientEventId = result.clientEventId,
            typeName = EventText.typeName(getApplication(), first.type),
            valueText = summaryValueText(first),
            totalCount = result.extraCount + 1,
        )
    }

    /** 把 [EventText.summarySuffix] 的口径渲染成文字，避免各页面各写一遍。 */
    private fun summaryValueText(event: ParsedEvent): String {
        val app = getApplication<Application>()
        return when (val v = EventText.summarySuffix(event)) {
            is EventText.SummaryValue.Kcal -> app.getString(R.string.summary_kcal, v.value)
            is EventText.SummaryValue.Weight ->
                app.getString(R.string.summary_weight, trimNumber(v.kg))
            is EventText.SummaryValue.Sleep ->
                app.getString(R.string.summary_sleep, trimNumber(v.hours))
            EventText.SummaryValue.None -> ""
        }
    }

    /**
     * 打开 App / 回到前台时跑一次规则扫描（PRD §7.4：不依赖后台定时器 ——
     * 国行 ROM 杀后台是既有结论）。
     *
     * 整个流程 **0 次 AI 调用**，纯本地规则。
     */
    fun scanSignals() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { HealthAggregator.scanAndPersist(getApplication()) }
        }
    }

    /**
     * 标记今日信号为已读（用户进了状态详情页就算看过了）。
     *
     * 已读之后状态行**必须切回摘要态** —— 不是永久停留在信号态，也不是
     * 把提示删掉（规范 §9.2「切回条件」）。`body_signals` 行本身保留，
     * 只是在状态页「身体」段仍能看到完整文案。
     */
    fun acknowledgeSignals() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { HealthAggregator.acknowledgeAll(getApplication()) }
        }
    }

    /**
     * 提交一条口语记录。立即 pending 入库（0ms 可见），后台补 AI 结果。
     *
     * ⚠️ 顺序很关键：**先落库再判网络**。
     * 用户的目标是"把这件事记下来"，不是"发一次 HTTP"。
     * 断网时原文已经进 pending，联网后重试即可 —— 数据永不丢。
     * 若先判网络就 return，用户的文字会丢，那是不可接受的。
     */
    fun submit(rawText: String) {
        _uiState.value = MainUiState.Parsing
        viewModelScope.launch {
            // 先落 pending（repo.submit 内部第一步就是 insertIgnore），
            // 因此这里即使在断网情况下调用也是安全的 —— 原文一定入库
            val result = repo.submit(rawText)
            _uiState.value = stateOf(result)
            undoPayloadOf(result)?.let { _undo.tryEmit(it) }
        }
    }

    /** 重试一条 pending / failed 记录。沿用原 clientEventId，幂等不新增行。 */
    fun retry(entity: EventEntity) {
        _uiState.value = MainUiState.Parsing
        viewModelScope.launch {
            // 重试前先看网络：没网就别白等 5 次退避（最坏 90 秒）
            if (!NetworkStatus.isOnline(getApplication())) {
                _uiState.value = MainUiState.Offline
                return@launch
            }
            val result = repo.retry(entity)
            _uiState.value = stateOf(result)
        }
    }

    /**
     * 把 SubmitResult 映射为 UI 状态。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 判定顺序（2026-10-03 修正）
     * ══════════════════════════════════════════════════════════════════════════
     * 1. `needsConfiguration` **优先**判断 —— 它由 [EventRepository.submit]
     *    在**本地**就已确定（读 settings 表发现没配置），根本没走到网络层。
     *    这种情况与网络无关，提示应为"去设置页填 key"。
     *
     * 2. 网络类失败（`network:` / `timeout:` 前缀）→ [MainUiState.Offline]。
     *    注意这与"未配置"是**两个不同的用户动作**：检查网络 vs 去填配置。
     *    旧实现把两者都映射成 Offline，是语义错配。
     *
     * 3. 限流单独一态 —— 规范 3.10 要求显示预估秒数，不能静默转圈。
     */
    private fun stateOf(result: SubmitResult): MainUiState = when {
        // 未配置：与网络无关，引导去设置页
        result.needsConfiguration -> MainUiState.NotConfigured

        result.ok -> MainUiState.Idle

        result.error?.startsWith("rate_limit") == true -> MainUiState.Queued(seconds = 2)

        // 网络类失败（DNS / TLS / 连接超时）→ 明确告知离线，
        // 而不是静默回到 Idle 让用户以为"记上了"
        result.error?.let { isNetworkError(it) } == true -> MainUiState.Offline

        else -> MainUiState.Idle
    }

    /**
     * 判断 error 字符串是否是网络类失败。
     *
     * EventRepository 写入的格式是 `"${kind.name.lowercase()}: ${message}"`，
     * 即 `network: ...` / `timeout: ...`。匹配前缀即可，不用 contains ——
     * 错误 message 里可能恰好出现 "network" 字样（如 TLS 报错），
     * 用前缀能避免误判。
     */
    private fun isNetworkError(error: String): Boolean =
        error.startsWith("network:") || error.startsWith("timeout:")

    /**
     * 重试当前所有"识别中 / 识别失败"的记录（提示条点击入口）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么取 events 而不是 listPending
     * ══════════════════════════════════════════════════════════════════════════
     * `EventDao.listPending()` 的 SQL 只筛 `parse_status = 'pending'`，
     * 而断网失败的记录会被 `markFailed()` 置为 `'failed'` —— 用 listPending
     * 会**漏掉全部失败记录**，而这些恰恰是用户最想重试的那批。
     *
     * 不新增 DAO 方法的理由：`events` 是已订阅的 StateFlow，
     * 当前日记录量在几十条量级，本地过滤零成本；
     * 而改 DB 层（加 @Query + 重跑 schema 校验）成本远高于收益。
     *
     * 串行而非并发：一次退避链最坏 ~90 秒，并发会让免费档模型立刻 429。
     * 逐条重试虽然慢，但每条的结论是真实的。
     */
    fun retryFailedPending() {
        viewModelScope.launch {
            if (!NetworkStatus.isOnline(getApplication())) {
                _uiState.value = MainUiState.Offline
                return@launch
            }

            val targets = events.value.filter {
                it.parseStatus == EventRepository.PARSE_PENDING ||
                    it.parseStatus == EventRepository.PARSE_FAILED
            }
            if (targets.isEmpty()) {
                _uiState.value = MainUiState.Idle
                return@launch
            }

            _uiState.value = MainUiState.Parsing

            var lastState: MainUiState = MainUiState.Idle
            for (entity in targets) {
                lastState = stateOf(repo.retry(entity))
                // 未配置就不要再逐条试 —— 全部会返回同一个结果
                if (lastState == MainUiState.NotConfigured) break
            }
            _uiState.value = lastState
        }
    }

    /**
     * 预设一键记录：不打字、不调 AI，直接以 done 入库（功能补充 2.1）。
     * 这是全 App 摩擦最低的路径 —— 价值高于任何一个 AI 功能。
     */
    fun logPreset(preset: PresetEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            // 日界线走唯一入口 dayStartHourOf（§1 收口）。
            val dayStart = com.healix.app.parse.dayStartHourOf(db.settingsDao().get(KEY_DAY_START))
            val clientEventId = java.util.UUID.randomUUID().toString()

            db.eventDao().insertIgnore(
                EventEntity(
                    clientEventId = clientEventId,
                    ts = now,
                    dayKey = com.healix.app.parse.dayKeyOf(now, dayStart),
                    rawText = preset.name,
                    type = "meal",
                    timeHint = "",
                    foods = preset.foodsJson.ifBlank { "[]" },
                    exercise = "",
                    amount = "",
                    kcal = preset.kcal,
                    symptom = "",
                    weightKg = 0.0,
                    sleepH = 0.0,
                    source = SOURCE_PRESET,
                    parseStatus = "done",
                    origin = "user",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            db.presetDao().bumpUsage(preset.id, now)

            // 预设是"全 App 摩擦最低的路径"，同样给 5 秒撤销
            // —— 与速记框、通知栏录入形成统一心智（规范 §9.6）。
            _undo.tryEmit(
                UndoPayload(
                    clientEventId = clientEventId,
                    typeName = EventText.typeName(getApplication(), "meal"),
                    valueText = if (preset.kcal > 0) {
                        getApplication<Application>().getString(R.string.summary_kcal, preset.kcal)
                    } else {
                        ""
                    },
                    totalCount = 1,
                ),
            )
        }
    }

    /**
     * 回到前台时调用：处理跨零点 + 刷新常驻通知副标题（被动监督三处之一）。
     * 不依赖后台定时器 —— 国行 ROM 会杀后台（总方案第十节）。
     */
    fun refresh() {
        val now = LocalDate.now().toString()
        if (todayKey.value != now) todayKey.value = now
        refreshNudgeSubtitle()
    }

    fun refreshNudgeSubtitle() {
        QuickInputService.refreshSubtitle(getApplication())
    }

    companion object {
        const val KEY_TARGET_KCAL = com.healix.app.db.SettingsKeys.TARGET_KCAL
        const val KEY_DAY_START = com.healix.app.db.SettingsKeys.DAY_START
        const val KEY_HIDE_KCAL = com.healix.app.db.SettingsKeys.HIDE_KCAL
        const val KEY_HIDE_WEIGHT = com.healix.app.db.SettingsKeys.HIDE_WEIGHT

        // 兜底默认值（目标摄入 2500 kcal、每周训练 3 次）已收敛到
        // `com.healix.app.db.GoalDefaults` —— 跨文件唯一来源，不要在这里重定义。
        // 顶层 data class 的默认参数解析不到本 companion，重定义会以
        // `Unresolved reference` 在编译期炸掉（CI #21 实况）。
    }
}
