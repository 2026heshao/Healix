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
import com.healix.app.repo.parseFoodsJson
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

    /** 今天。发送、落库、上下文会话都以它为准（历史会话只读）。 */
    private val todayKey = LocalDate.now().toString()

    /** 当前查看的会话日期（微扩展 B）。切到过去 = 只读历史。 */
    private val _selectedDate = MutableStateFlow(todayKey)
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
        .map { it == todayKey }
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

    fun send(text: String) {
        // 历史会话只读（微扩展 B）：发送永远只发生在"今天"视图
        if (_selectedDate.value != todayKey) return
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
            // 手头食物/常备药物）拼进 background 通道（空值整段省略，沿用 systemPrompt
            // 的空省略先例）。不给 ChatEngine.reply 增参 —— 避免与知识库侧的签名改动互相踩。
            // 资源清单读端统一走 ResourceStore（sport 含旧 profile_gear 迁移兜底）。
            val allergens = parseFoodsJson(
                db.settingsDao().get(com.healix.app.db.SettingsKeys.PROFILE_ALLERGENS).orEmpty(),
            )
            val pain = parseFoodsJson(
                db.settingsDao().get(com.healix.app.db.SettingsKeys.PROFILE_PAIN).orEmpty(),
            )
            val sport = com.healix.app.repo.ResourceStore.sport(db)
            val foodsAtHand = com.healix.app.repo.ResourceStore.foods(db)
            val medsAtHand = com.healix.app.repo.ResourceStore.meds(db)
            val scene = db.settingsDao()
                .get(com.healix.app.db.SettingsKeys.PROFILE_SCENE).orEmpty()
            val bed = db.settingsDao()
                .get(com.healix.app.db.SettingsKeys.PROFILE_SLEEP_BED).orEmpty()
            val wake = db.settingsDao()
                .get(com.healix.app.db.SettingsKeys.PROFILE_SLEEP_WAKE).orEmpty()
            // 背景每次现读：设置页可能刚改过，缓存会让改动不生效
            val backgroundText = db.settingsDao().get(SettingsActivity.KEY_BACKGROUND).orEmpty()
            val background = buildString {
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
                //（说话方式段的优先级措辞）；药物仅作事实参考，
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
            }.trim()

            // F2 去重后的历史窗口（agent 与单轮回退共用同一份）
            val history = db.chatMessageDao().recentForContext(todayKey, 17)
                .withoutTrailingDuplicate(text)

            // ── S3–S4 第一级：有界工具循环 ────────────────────────────
            // 限步 4 / 墙钟 30s / 同参即停（见 HealthAgent）。任何失败都
            // 落回第二级单轮 ChatEngine（其内含第三级本地模板）。
            val outcome = HealthAgent(getApplication(), repo).run(
                config = config,
                sessionDate = todayKey,
                userText = text,
                history = history,
                background = background,
                knowledge = knowledge,
            )
            when (outcome) {
                is AgentOutcome.Done -> completeWithText(outcome.text, hits.firstOrNull())

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
                        sessionDate = todayKey,
                        userText = text,
                        history = history,
                        background = background,
                        knowledge = knowledge,
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

    /** 正常文本回复收口：来源行拼接（10.4 ①）+ 落库 + 状态复位。 */
    private suspend fun completeWithText(text: String, source: com.healix.app.repo.KnowledgeHit?) {
        val replyText = if (source != null) {
            text + "\n" + getApplication<Application>().getString(
                R.string.knowledge_source, source.docTitle, source.pageNo,
            )
        } else {
            text
        }
        persistAssistant(replyText)
        _uiState.value = ChatUiState.Idle
    }

    /**
     * 用户确认拟稿（agent propose_log）：走完整抽取链（与「记一笔」同管道），
     * source = ai_suggestion。成功与否都经 [proposalToast] 反馈。
     */
    fun confirmProposal(proposal: LogProposal) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                val result = repo.submit(
                    proposal.rawText,
                    source = com.healix.app.repo.SOURCE_AI_SUGGESTION,
                )
                val msg = if (result.ok) {
                    app.getString(R.string.preset_logged, proposal.rawText)
                } else {
                    app.getString(R.string.preset_log_failed)
                }
                _proposalToast.emit(msg)
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
                val dayStart = db.settingsDao()
                    .get(com.healix.app.db.SettingsKeys.DAY_START)?.toIntOrNull()
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
                    sessionDate = todayKey,
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
