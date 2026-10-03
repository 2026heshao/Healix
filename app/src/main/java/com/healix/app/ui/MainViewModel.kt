package com.healix.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healix.app.HealixApp
import com.healix.app.db.EventEntity
import com.healix.app.db.PresetEntity
import com.healix.app.notify.QuickInputService
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

/** 主界面交互状态。限流单独一态，因为必须显示预估秒数（规范 3.10）。 */
sealed interface MainUiState {
    data object Idle : MainUiState
    data object Parsing : MainUiState
    data class Queued(val seconds: Int) : MainUiState
    data object Offline : MainUiState
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

    /** 提交一条口语记录。立即 pending 入库（0ms 可见），后台补 AI 结果。 */
    fun submit(rawText: String) {
        _uiState.value = MainUiState.Parsing
        viewModelScope.launch {
            val result = repo.submit(rawText)
            _uiState.value = stateOf(result)
        }
    }

    /** 重试一条 pending / failed 记录。沿用原 clientEventId，幂等不新增行。 */
    fun retry(entity: EventEntity) {
        _uiState.value = MainUiState.Parsing
        viewModelScope.launch {
            val result = repo.retry(entity)
            _uiState.value = stateOf(result)
        }
    }

    /**
     * 把 SubmitResult 映射为 UI 状态。
     * 限流单独一态 —— 规范 3.10 要求显示预估秒数，不能静默转圈。
     */
    private fun stateOf(result: SubmitResult): MainUiState = when {
        result.needsConfiguration -> MainUiState.Offline
        result.ok -> MainUiState.Idle
        result.error?.startsWith("rate_limit") == true -> MainUiState.Queued(seconds = 2)
        else -> MainUiState.Idle
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
        const val KEY_TARGET_KCAL = "target_kcal"
        const val KEY_DAY_START = "day_start_hour"
        const val DEFAULT_TARGET_KCAL = 2500
    }
}
