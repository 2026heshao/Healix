package com.healix.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.agent.AgentOutcome
import com.healix.app.agent.HealthAgent
import com.healix.app.agent.LogProposal
import com.healix.app.db.ChatMessageEntity
import com.healix.app.db.EventEntity
import com.healix.app.db.PresetEntity
import com.healix.app.net.NetworkStatus
import com.healix.app.repo.ProfileContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
 *
 * ## 微扩展（2026-10-04）
 * - **会话日期切换（B）**：[selectedDate] 驱动 [messages] 的 flatMapLatest；
 *   发送/落库永远写**今天**（[todayKey]），历史会话只读 —— 输入条在 Activity 侧禁用。
 * - **预设快捷条（C）**：与记录页同一 `presets` 数据源；点击 = 直接落库一条
 *   done 事件 + bumpUsage（0 AI 调用），结果经 [presetToast] 回传给 Activity Toast。
 * - **失败重试（D）**：只有**模型调用失败**（ChatEngine 返回 Degraded）才置
 *   [retryAvailable]，未配置/断网/配额不置 —— 重试救不了那些，提示条不该骗人点。
 *   重试 = 用上一条 user 消息重跑管线，**不重复落库 user 行**
 *   （`withoutTrailingDuplicate` 保证发给模型的历史里它只出现一次）。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val container = HealixApp.from(app)
    private val db = container.database
    private val repo = container.eventRepository
    private val quota = container.quotaGuard

    /**
     * 今天。发送、落库、上下文会话都以它为准（历史会话只读）。
     *
     * ⚠️ P0-B 修复（2026-10-05）：此前是**构造期字段快照**
     * （`private val todayKey = LocalDate.now().toString()`），VM 活多久，
     * "今天"就冻结在哪天 —— 夜里 00:00 后继续对话，消息仍落库到昨天的
     * `session_date`。现改为**使用点现取函数**：`persist` / `executeChat` /
     * `sessionDate` 全部调用当刻日期。
     */
    private fun todayKey(): String = LocalDate.now().toString()

    /**
     * 记忆"上一个今天"（P0-B 跨零点顺延判定用）。
     *
     * `isToday` 是 `stateIn` 缓存，值不变则**不会**重发 → 跨零点 UI 不会自动
     * 恢复可用。用本字段记住上一天的串：`todayKey() != lastKnownToday`
     * 说明跨了零点，若用户此前仍停留在"旧今天"视图则顺延到今天并刷新
     * `lastKnownToday`。触发点：[refreshToday]（ChatActivity.onResume）+ [send]
     * 起始守卫（零点后首次发送即自愈，杜绝误拦）。
     */
    private var lastKnownToday: String = LocalDate.now().toString()

    /** 当前查看的会话日期（微扩展 B）。切到过去 = 只读历史。 */
    private val _selectedDate = MutableStateFlow(lastKnownToday)
    val selectedDate: StateFlow<String> = _selectedDate.asStateFlow()

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** 上一条 user 消息（重试用）。null = 本会话还没发过。 */
    private var lastUserText: String? = null

    /** 只有"模型调用失败"才置 true（见类注释 D 段）。发送开始/成功时复位。 */
    private val _retryAvailable = MutableStateFlow(false)
    val retryAvailable: StateFlow<Boolean> = _retryAvailable.asStateFlow()

    /** 查看的会话是否为今天。false 时 Activity 禁用输入条与发送键。 */
    val isToday: StateFlow<Boolean> = _selectedDate
        .map { it == todayKey() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val messages: StateFlow<List<ChatMessageEntity>> =
        _selectedDate
            .flatMapLatest { day -> db.chatMessageDao().observeSession(day) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 有消息的会话日期（倒序，微扩展 B 的菜单数据源）。 */
    val sessionDates: StateFlow<List<String>> = db.chatMessageDao().observeSessionDates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 预设快捷条（微扩展 C）：与记录页同一数据源。 */
    val presets: StateFlow<List<PresetEntity>> = db.presetDao()
        .observeTop(6)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 预设落库结果（已格式化的 Toast 文案；失败文案见 [R.string.preset_log_failed]）。 */
    private val _presetToast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val presetToast: SharedFlow<String> = _presetToast.asSharedFlow()

    /**
     * propose_log 拟稿（S3–S4 agent）：Activity 收到后弹确认框。
     * 确认 → [confirmProposal] 走完整抽取链；取消 → 什么都不发生。
     */
    private val _proposal = MutableSharedFlow<LogProposal>(extraBufferCapacity = 4)
    val proposal: SharedFlow<LogProposal> = _proposal.asSharedFlow()

    /** 拟稿确认后的落库结果 Toast（复用预设同一组文案资源）。 */
    private val _proposalToast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val proposalToast: SharedFlow<String> = _proposalToast.asSharedFlow()

    /** 切换查看的会话日期（yyyy-MM-dd）。 */
    fun selectSession(date: String) {
        _selectedDate.value = date
    }

    /**
     * 跨零点顺延（P0-B）：回到前台时调用（ChatActivity.onResume）。
     *
     * 仅当"用户仍停留在旧今天"时才把 [selectedDate] 顺延到今天 ——
     * 停在历史会话（用户主动切过去看的）不受影响。顺延会触发 [isToday] /
     * [messages] 随 `_selectedDate` 重订阅，输入条即时恢复可用。
     *
     * 不做定时器：onResume 是"用户回到前台"这一真实动作的锚点，覆盖
     * "夜里放着不动、早上再点开"的绝大多数场景；极冷的"停在页面里跨零点"
     * 由 [send] 起始守卫兜底。
     */
    fun refreshToday() {
        val today = todayKey()
        if (today == lastKnownToday) return
        if (_selectedDate.value == lastKnownToday) _selectedDate.value = today
        lastKnownToday = today
    }

    fun send(text: String) {
        // P0-B 跨零点自愈：零点后首次动作即把"旧今天"顺延到今天，
        // 保证下面的守卫绝不会误拦用户当下的发送。
        val today = todayKey()
        if (today != lastKnownToday) {
            if (_selectedDate.value == lastKnownToday) _selectedDate.value = today
            lastKnownToday = today
        }
        // 历史会话只读（微扩展 B）：发送永远只发生在"今天"视图
        if (_selectedDate.value != today) return
        lastUserText = text
        _retryAvailable.value = false
        _uiState.value = ChatUiState.Thinking
        viewModelScope.launch(Dispatchers.IO) {
            // 先落 user 行，再在同一条协程里跑管线 —— 顺序有保证，
            // recentForContext 一定能取到刚落的这句（F2 去重依赖它）
            persist("user", text)
            executeChat(text)
        }
    }

    /**
     * 重试上一次失败的请求（微扩展 D）。不重复落 user 行 —— 上一条还在库里，
     * `withoutTrailingDuplicate` 会把它从历史窗口里剔掉，避免同一句话发两遍。
     */
    fun retryLast() {
        val text = lastUserText ?: return
        _retryAvailable.value = false
        _uiState.value = ChatUiState.Thinking
        viewModelScope.launch(Dispatchers.IO) {
            executeChat(text)
        }
    }

    private suspend fun executeChat(text: String) {
        // 配额护栏：超出走本地提示，不消耗调用
        if (!quota.canChat()) {
            persistAssistant(getApplication<Application>().getString(R.string.quota_exhausted))
            _uiState.value = ChatUiState.QuotaExhausted
            return
        }

        val config = repo.loadProviderConfig()
        if (config == null) {
            // 未配置：与网络无关，引导去设置页
            persistAssistant(getApplication<Application>().getString(R.string.no_provider_config))
            _uiState.value = ChatUiState.Degraded
            return
        }

        // 断网：明确告知，而不是让请求白等 15 秒超时后再降级
        if (!NetworkStatus.isOnline(getApplication())) {
            persistAssistant(getApplication<Application>().getString(R.string.state_offline))
            _uiState.value = ChatUiState.Degraded
            return
        }

            // 知识库检索（F12，10.4 ②）：关键词打分，命中才注入，未命中不注入
            // （常态对话零噪声）。纯本地内存打分，毫秒级，不产生任何网络调用。
            val hits = container.knowledgeRepository.search(text)
            val knowledge = hits.joinToString("\n") { h ->
                "${h.docTitle}（第 ${h.pageNo} 页）：${h.content}"
            }

            // 结构化画像（F6）：硬约束段（忌口/疼痛/运动条件）+ 软背景段（场景/作息/
            // 手头食物/常备药物）统一由 ProfileContext.build 拼装 —— 与计划生成同源，
            // 避免两套口径漂移。输出逐字不变（直接进 system prompt，改字=改 AI 行为）。
            // 不给 ChatEngine.reply 增参 —— 避免与知识库侧的签名改动互相踩。
            val background = ProfileContext.build(db)

            // F2 去重后的历史窗口（agent 与单轮回退共用同一份）
            val history = db.chatMessageDao().recentForContext(todayKey(), 17)
                .withoutTrailingDuplicate(text)

            // ── S3–S4 第一级：有界工具循环 ────────────────────────────
            // 限步 4 / 墙钟 30s / 同参即停（见 HealthAgent）。任何失败都
            // 落回第二级单轮 ChatEngine（其内含第三级本地模板）。
            val outcome = HealthAgent(getApplication(), repo).run(
                config = config,
                sessionDate = todayKey(),
                userText = text,
                history = history,
                background = background,
                knowledge = knowledge,
            )
            when (outcome) {
                is AgentOutcome.Done ->
                    completeWithText(outcome.text, hits.firstOrNull(), outcome.toolsUsed)

                is AgentOutcome.ProposalPending -> {
                    persistAssistant(outcome.text)
                    _uiState.value = ChatUiState.Idle
                    _proposal.emit(outcome.proposal)
                }

                is AgentOutcome.RateLimited -> {
                    persistAssistant(outcome.message)
                    _uiState.value = ChatUiState.Queued(seconds = 4)
                }

                AgentOutcome.Failed -> {
                    // 第二级：单轮（无工具）。Degraded 时 reply.text 已是本地模板文案。
                    val reply = ChatEngine.reply(
                        context = getApplication(),
                        config = config,
                        sessionDate = todayKey(),
                        userText = text,
                        history = history,
                        background = background,
                        knowledge = knowledge,
                    )
                    // §4.1（2026-10-04 复核更正）：单轮回退路径此前不记 llm_calls，
                    // 补一条 —— 否则配额计数与设置页「今日对话调用」会漏掉这一档。
                    repo.recordChatCall(
                        model = config.model,
                        attempts = reply.attempts,
                        latencyMs = reply.latencyMs,
                        status = when (reply.state) {
                            ChatEngine.State.Ok -> com.healix.app.repo.EventRepository.STATUS_OK
                            else -> com.healix.app.repo.EventRepository.STATUS_RETRY_EXHAUSTED
                        },
                        httpCode = reply.httpCode,
                        inputTokens = reply.inputTokens,
                        outputTokens = reply.outputTokens,
                        errorHead = reply.errorHead,
                        // 对话链 prompt 版本 —— ChatEngine.systemPrompt 内容对应版本。
                        promptVer = PROMPT_VER_CHAT,
                    )
                    when (reply.state) {
                        ChatEngine.State.Ok -> completeWithText(reply.text, hits.firstOrNull())
                        ChatEngine.State.Queued -> {
                            persistAssistant(reply.text)
                            _uiState.value = ChatUiState.Queued(seconds = 4)
                        }
                        // 模型真失败了 → 这一种才给重试入口（未配置/断网在上面提前 return）
                        ChatEngine.State.Degraded -> {
                            persistAssistant(reply.text)
                            _uiState.value = ChatUiState.Degraded
                            _retryAvailable.value = true
                        }
                    }
                }
            }
    }

    /**
     * 正常文本回复收口：正文 → （[toolsUsed] > 0 时）查阅行 → 来源行（10.4 ①）+ 落库 + 状态复位。
     *
     * ⚠️ 末行元数据拼接顺序被 `ChatAdapter` 的解析依赖：`来源：` **恒为最后一行**，
     * `查阅：` 紧随其前 —— 渲染端从末尾最多剥两行。
     */
    private suspend fun completeWithText(
        text: String,
        source: com.healix.app.repo.KnowledgeHit?,
        toolsUsed: Int = 0,
    ) {
        val app = getApplication<Application>()
        // §4.5 工具使用可见性：agent 成功且真的问过数据时，末行加轻角标（零 DB 列）
        val withBadge = if (toolsUsed > 0) {
            text + "\n" + app.getString(R.string.chat_tool_badge, toolsUsed)
        } else {
            text
        }
        val replyText = if (source != null) {
            withBadge + "\n" + app.getString(
                R.string.knowledge_source, source.docTitle, source.pageNo,
            )
        } else {
            withBadge
        }
        persistAssistant(replyText)
        _uiState.value = ChatUiState.Idle
    }

    /**
     * 用户确认拟稿（agent propose_log）：走完整抽取链（与「记一笔」同管道），
     * source = ai_suggestion。
     *
     * §4.2 回执闭环：**成功**由本地模板回一条助理消息（零 token，不再发成功
     * Toast，避免双反馈）；**失败**仍经 [proposalToast] 提示（行为不变）。
     */
    fun confirmProposal(proposal: LogProposal) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                val result = repo.submit(
                    proposal.rawText,
                    source = com.healix.app.repo.SOURCE_AI_SUGGESTION,
                )
                if (result.ok) {
                    // 回执口径：只提「已记下 + 本周运动还差 N 次 / 已达标」，
                    // 不提 kcal 数字（规避隐私开关 HIDE_KCAL 的边界）。
                    val s = TodaySummary.build(app)
                    val gap = s.goalSessionsWeek - s.exerciseCountThisWeek
                    val receipt = if (gap > 0) {
                        app.getString(R.string.proposal_receipt_gap, proposal.rawText, gap)
                    } else {
                        app.getString(R.string.proposal_receipt_done, proposal.rawText)
                    }
                    persistAssistant(receipt)
                } else {
                    _proposalToast.emit(app.getString(R.string.preset_log_failed))
                }
            } catch (_: Exception) {
                _proposalToast.emit(app.getString(R.string.preset_log_failed))
            }
        }
    }

    /**
     * 预设一键记录（微扩展 C）：与记录页 [MainViewModel.logPreset] 同语义 ——
     * 不打字、不调 AI，直接以 done 入库 + bumpUsage。
     * 区别：聊天页没有撤销条，结果用 Toast 反馈（经 [presetToast]）。
     */
    fun logPreset(preset: PresetEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                val now = System.currentTimeMillis()
                val dayStart = com.healix.app.parse.dayStartHourOf(
                    db.settingsDao().get(com.healix.app.db.SettingsKeys.DAY_START),
                )
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
                        source = com.healix.app.repo.SOURCE_PRESET,
                        parseStatus = "done",
                        origin = "user",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                db.presetDao().bumpUsage(preset.id, now)
                _presetToast.emit(app.getString(R.string.preset_logged, preset.name))
            } catch (_: Exception) {
                _presetToast.emit(app.getString(R.string.preset_log_failed))
            }
        }
    }

    // ⚠️ 必须 suspend：表达式体 `fun x() = persist(...)` 是「非 suspend 调 suspend」，
    // 本地检查器抓不到这个形态，CI #31 实证 —— 别改回非 suspend 单行函数。
    private suspend fun persistAssistant(content: String) {
        persist("assistant", content)
    }

    private suspend fun persist(role: String, content: String) {
        try {
            db.chatMessageDao().insert(
                ChatMessageEntity(
                    sessionDate = todayKey(),
                    role = role,
                    content = content,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        } catch (_: Exception) {
            // DB 异常 → 对话不落库但功能不中断（9.2 组件表失败处理）
        }
    }

    /**
     * 从末尾（created_at 最新的一端）起找第一条 content 与 [currentText] 相同的
     * 记录并剔除。历史列表为正序（最旧在前），剔除的即列表中的匹配项——
     * 只删一条匹配，用户连发相同内容时更早的那几条是真实历史，必须保留。
     */
    private fun List<ChatMessageEntity>.withoutTrailingDuplicate(currentText: String): List<ChatMessageEntity> {
        val idx = indexOfLast { it.content == currentText }
        return if (idx == -1) this else toMutableList().apply { removeAt(idx) }
    }
}
