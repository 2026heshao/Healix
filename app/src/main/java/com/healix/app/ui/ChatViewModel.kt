package com.healix.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healix.app.HealixApp
import com.healix.app.db.ChatMessageEntity
import com.healix.app.net.NetworkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed interface ChatUiState {
    data object Idle : ChatUiState
    data object Thinking : ChatUiState
    data class Queued(val seconds: Int) : ChatUiState

    /** 配额耗尽：预期行为，不是错误（规范 3.10） */
    data object QuotaExhausted : ChatUiState

    /** 工具失败降级为本地模板回答 */
    data object Degraded : ChatUiState
}

/**
 * 对话页 ViewModel（功能补充 9.2 组件表）。
 *
 * ⚠️ 当前范围（S2）：组装上下文 + 调 provider + 消息落库。
 * 工具调用循环（max_steps=4 / 墙钟 30s / 重复调用即停）由 `HealthAgent` 承担，
 * 属 S3–S4。此处**不实现循环**，避免在工具集就位前引入不确定行为。
 *
 * 上下文策略（9.2）：系统提示 + 今日摘要（本地算术，不调 AI）+ 最近 16 条历史。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val container = HealixApp.from(app)
    private val db = container.database
    private val repo = container.eventRepository
    private val quota = container.quotaGuard

    private val sessionDate = LocalDate.now().toString()

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val messages: StateFlow<List<ChatMessageEntity>> =
        MutableStateFlow(sessionDate)
            .flatMapLatest { day -> db.chatMessageDao().observeSession(day) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun send(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            persist("user", text)
            _uiState.value = ChatUiState.Thinking

            // 配额护栏：超出走本地提示，不消耗调用
            if (!quota.canChat()) {
                persist("assistant", getApplication<Application>().getString(com.healix.app.R.string.quota_exhausted))
                _uiState.value = ChatUiState.QuotaExhausted
                return@launch
            }

            val config = repo.loadProviderConfig()
            if (config == null) {
                // 未配置：与网络无关，引导去设置页
                persist("assistant", getApplication<Application>().getString(com.healix.app.R.string.no_provider_config))
                _uiState.value = ChatUiState.Degraded
                return@launch
            }

            // 断网：明确告知，而不是让请求白等 15 秒超时后再降级
            if (!NetworkStatus.isOnline(getApplication())) {
                persist("assistant", getApplication<Application>().getString(com.healix.app.R.string.state_offline))
                _uiState.value = ChatUiState.Degraded
                return@launch
            }

            val reply = ChatEngine.reply(
                context = getApplication(),
                config = config,
                sessionDate = sessionDate,
                userText = text,
                history = db.chatMessageDao().recentForContext(sessionDate, 16),
                // 背景每次现读：设置页可能刚改过，缓存会让改动不生效
                background = db.settingsDao().get(SettingsActivity.KEY_BACKGROUND).orEmpty(),
            )

            persist("assistant", reply.text)
            _uiState.value = when (reply.state) {
                ChatEngine.State.Ok -> ChatUiState.Idle
                ChatEngine.State.Queued -> ChatUiState.Queued(seconds = 4)
                ChatEngine.State.Degraded -> ChatUiState.Degraded
            }
        }
    }

    private suspend fun persist(role: String, content: String) {
        try {
            db.chatMessageDao().insert(
                ChatMessageEntity(
                    sessionDate = sessionDate,
                    role = role,
                    content = content,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        } catch (_: Exception) {
            // DB 异常 → 对话不落库但功能不中断（9.2 组件表失败处理）
        }
    }
}
