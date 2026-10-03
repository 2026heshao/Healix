package com.healix.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healix.app.HealixApp
import com.healix.app.db.EventEntity
import com.healix.app.db.PresetEntity
import com.healix.app.notify.QuickInputService
import com.healix.app.net.NetworkStatus
import com.healix.app.repo.EventRepository
import com.healix.app.repo.SOURCE_PRESET
import com.healix.app.repo.SubmitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

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
            .map { it?.toIntOrNull() ?: DEFAULT_TARGET_KCAL }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_TARGET_KCAL)

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
            val dayStart = db.settingsDao().get(KEY_DAY_START)?.toIntOrNull()
                ?: com.healix.app.parse.DEFAULT_DAY_START_HOUR

            db.eventDao().insertIgnore(
                EventEntity(
                    clientEventId = java.util.UUID.randomUUID().toString(),
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
        const val DEFAULT_TARGET_KCAL = 2500
    }
}
