package com.healix.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.agent.AgentOutcome
import com.healix.app.agent.AgentProposal
import com.healix.app.agent.GoalChangeProposal
import com.healix.app.agent.HealthAgent
import com.healix.app.agent.LogProposal
import com.healix.app.agent.PlanChangeProposal
import com.healix.app.agent.RecordDeleteProposal
import com.healix.app.db.ChatMessageEntity
import com.healix.app.db.EventEntity
import com.healix.app.db.PresetEntity
import com.healix.app.db.SettingsKeys
import com.healix.app.net.NetworkStatus
import com.healix.app.net.ProviderConfig
import com.healix.app.parse.loadsLenient
import com.healix.app.repo.KnowledgeHit
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
import org.json.JSONObject

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
     * 拟稿（S3–S4 agent / v0.3 B6 泛化）：Activity 收到后弹确认框。
     * 确认 → [confirmProposal] 走对应写路径；取消 → [cancelProposal] 只回填 `approved=0`。
     *
     * 类型由 `LogProposal` 泛化为 `AgentProposal`（记录 / 计划 / 目标 / 删除四类草案）。
     */
    private val _proposal = MutableSharedFlow<AgentProposal>(extraBufferCapacity = 4)
    val proposal: SharedFlow<AgentProposal> = _proposal.asSharedFlow()

    /**
     * 记录软删成功后置位（v0.3 B6）：UI 收到后弹 [UndoBar] 提供 5 秒退回机会。
     * 只在「删除」这一路发；其它草案成功用回执消息，不给撤销位。
     */
    private val _undo = MutableSharedFlow<RecordDeleteProposal>(extraBufferCapacity = 4)
    val undo: SharedFlow<RecordDeleteProposal> = _undo.asSharedFlow()

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

    fun send(text: String): Boolean {
        // P0-B 跨零点自愈：零点后首次动作即把"旧今天"顺延到今天，
        // 保证下面的守卫绝不会误拦用户当下的发送。
        val today = todayKey()
        if (today != lastKnownToday) {
            if (_selectedDate.value == lastKnownToday) _selectedDate.value = today
            lastKnownToday = today
        }
        // 历史会话只读（微扩展 B）：发送永远只发生在"今天"视图。
        // ⚠️ 这是唯一一条"在 persist 之前就 return"的守卫，也是唯一会丢文本的路径 ——
        //    返回 false，让调用方保留输入原文、不清空（本项目 persist-first，
        //    走到下面的 persist 之后原文一定入库，回填反而会造成重复发送）。
        if (_selectedDate.value != today) return false
        lastUserText = text
        _retryAvailable.value = false
        _uiState.value = ChatUiState.Thinking
        viewModelScope.launch(Dispatchers.IO) {
            // 先落 user 行，再在同一条协程里跑管线 —— 顺序有保证，
            // recentForContext 一定能取到刚落的这句（F2 去重依赖它）
            persist("user", text)
            executeChat(text)
        }
        return true
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
            // 避免两套口径漂移。不给 ChatEngine.reply 增参 —— 避免与知识库侧的签名改动互相踩。
            //
            // 全量背景（2026-10-05）：画像/体格/目标（ProfileContext.build 内部受
            // AI_DATA_FULL 门控）+ 当前执行计划段（本文件拼装，同一门控）。
            // 非空段以空行连接；全空 → 空串（systemPrompt 各段空省略，零噪声）。
            val background = listOf(
                ProfileContext.build(db),
                buildPlanSection(),
            ).filter { it.isNotBlank() }.joinToString("\n\n")

            // F2 去重后的历史窗口（agent 与单轮回退共用同一份）
            val history = db.chatMessageDao().recentForContext(todayKey(), 17)
                .withoutTrailingDuplicate(text)

            // 用户自定义输出偏好规则（v0.3 B4）：IO 读库，拼成一段注入 system prompt。
            // 空 = 不注入（userRulesBlock 空段省略 → 输出与无规则时逐字节相同）。
            val userRules = loadUserRules()

            // ── AI 工具总开关（v0.3 B5，D4：默认开）──────────────────────
            // 关 = 退回单轮：**跳过 agent**（不调任何工具、不写 tool_calls），
            // 与降级链第二级同一出口 → `llm_calls` 恰好 1 行（验收 1）。
            val toolsEnabled = db.settingsDao().get(SettingsKeys.AI_TOOLS_ENABLED) != "false"
            if (!toolsEnabled) {
                replySingleTurn(config, text, history, background, knowledge, userRules, hits)
                return
            }

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
                userRules = userRules,
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

                AgentOutcome.Failed ->
                    // 第二级：单轮（无工具）。Degraded 时 reply.text 已是本地模板文案。
                    replySingleTurn(config, text, history, background, knowledge, userRules, hits)
            }
    }

    /**
     * 单轮回复（降级链第二级；v0.3 B5 起工具总开关关闭也走这里）。
     *
     * 组装 prompt → 调 [ChatEngine.reply] → **补记一次 `llm_calls`**（§4.1：单轮回退路径
     * 此前不记，会漏配额计数）→ 按状态落库 / 复位。Degraded 才给重试入口。
     *
     * 抽成方法是为了让「工具关闭退回单轮」与「agent 失败退回单轮」共用**同一段**逻辑 ——
     * 两处各抄一份必然漂移。
     */
    private suspend fun replySingleTurn(
        config: ProviderConfig,
        text: String,
        history: List<ChatMessageEntity>,
        background: String,
        knowledge: String,
        userRules: String,
        hits: List<KnowledgeHit>,
    ) {
        val reply = ChatEngine.reply(
            context = getApplication(),
            config = config,
            sessionDate = todayKey(),
            userText = text,
            history = history,
            background = background,
            knowledge = knowledge,
            userRules = userRules,
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

    /**
     * 读出生效中的用户规则（v0.3 B4），拼成一段文本供 system prompt 注入。
     *
     * 在 IO 线程读库（调用方 [executeChat] 已在 `Dispatchers.IO` 协程内）。
     * 空 / 读取异常 → 空串（`ChatEngine.systemPrompt` 空段省略 → 输出与无规则时逐字节相同）。
     * 只读 [com.healix.app.db.AiRuleDao.listEnabled]（`enabled = 1`，按 `sort_order` 升序）。
     */
    private suspend fun loadUserRules(): String =
        runCatching {
            db.aiRuleDao().listEnabled().joinToString("\n") { "· ${it.text}" }
        }.getOrDefault("")

    /**
     * 当前执行计划段（AI_DATA_FULL 门控；三条全空 → 空串）。
     *
     * 内容（2026-10-05，P0-5/P0-7）：本周训练计划里「今天 / 明天」那条 +
     * 今日计划备注。训练行经 [trainingPlanLineFor]（纯读缓存，绝不触网）；
     * 开关关闭或三条全空 → 空串（systemPrompt 空段省略，零噪声）。
     * ⚠️ 块体 `return`：挂起函数禁 `= expr` 转发（check_suspend_calls 纪律）。
     */
    private suspend fun buildPlanSection(): String {
        if (!ProfileContext.aiDataFull(db)) return ""
        val today = trainingPlanLineFor(getApplication(), 0)
        val tomorrow = trainingPlanLineFor(getApplication(), 1)
        val note = todayPlanNote()
        if (today == null && tomorrow == null && note == null) return ""
        return buildString {
            appendLine("【当前执行计划（本地生成的计划，事实参考）】")
            today?.let { appendLine("本周训练计划里\"今天\"那条：$it") }
            tomorrow?.let { appendLine("本周训练计划里\"明天\"那条：$it") }
            note?.let { appendLine("今日计划备注：$it") }
        }.trim()
    }

    /**
     * 今日计划备注：`daily_plans.planJson` 的 `note` 字段。防御式解析，
     * 任何异常 / null → null。日期键必经 [todayKey]（日界线纪律）。
     *
     * ⚠️ 用 opt() 而非 optString()：optString 对 JSON null 返回字面字符串
     *    "null"（PlanGenerator.parseTimelineJson 注释已记此坑）。取原生
     *    String 才安全。
     */
    private suspend fun todayPlanNote(): String? {
        return runCatching {
            val row = db.planDao().getPlan(todayKey()) ?: return null
            val json = row.planJson ?: return null
            val obj = loadsLenient(json) as? JSONObject ?: return null
            val n = obj.opt("note")
            (n as? String)?.trim()?.ifEmpty { null }
        }.getOrNull()
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
     * 用户确认拟稿（v0.3 B6 泛化）：按草案类型走各自的写路径，并回填 `tool_calls.approved = 1`。
     *
     * - [LogProposal]：走完整抽取链（source = ai_suggestion），**不变**；
     * - [PlanChangeProposal]：读改写 `daily_plans.plan_json`（[PlanChangeWriter]）；
     * - [GoalChangeProposal]：`GoalDao.setTarget`；
     * - [RecordDeleteProposal]：软删（[EventRepository.undo]），成功后置 [undo] 供退回。
     *
     * 成功一律由本地模板回一条助理消息（零 token，避免双反馈）；失败经 [proposalToast] 提示。
     */
    fun confirmProposal(proposal: AgentProposal) {
        viewModelScope.launch(Dispatchers.IO) {
            when (proposal) {
                is LogProposal -> confirmLog(proposal)
                is PlanChangeProposal -> confirmPlanChange(proposal)
                is GoalChangeProposal -> confirmGoalChange(proposal)
                is RecordDeleteProposal -> confirmRecordDelete(proposal)
            }
        }
    }

    /**
     * 用户取消拟稿：**不写任何业务数据**，只把对应 `tool_calls.approved` 回填为 0。
     * （确认/取消两条路径对称回填，审计里能看出每个拟稿的最终归宿。）
     */
    fun cancelProposal(proposal: AgentProposal) {
        viewModelScope.launch(Dispatchers.IO) {
            markApproved(proposal.callUid, 0)
        }
    }

    /** 撤回一次记录软删（[undo] 的 UndoBar 落点）：清 `deleted_at` 让记录回到原位。 */
    fun restoreDeleted(proposal: RecordDeleteProposal) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repo.restore(proposal.clientEventId) }
        }
    }

    /**
     * 记录草稿确认：走完整抽取链（与「记一笔」同管道），source = ai_suggestion。
     *
     * §4.2 回执闭环：**成功**由本地模板回一条助理消息（零 token，不再发成功 Toast）；
     * **失败**仍经 [proposalToast] 提示（行为不变）。
     */
    private suspend fun confirmLog(proposal: LogProposal) {
        val app = getApplication<Application>()
        try {
            val result = repo.submit(
                proposal.rawText,
                source = com.healix.app.repo.SOURCE_AI_SUGGESTION,
            )
            if (result.ok) {
                markApproved(proposal.callUid, 1)
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

    /**
     * 计划修改确认：读改写当日 `plan_json`（条目级补丁 / 备注，见 [PlanChangeWriter]）。
     *
     * ⚠️ 拟稿期（`HealthAgent.proposePlanChange`）已校验过一次；这里 [PlanChangeWriter.apply]
     * **重新读库定位**再落库 —— 拟稿到确认之间计划可能被重新生成，若此刻定位不到则返回
     * 失败（不写库、回执失败），避免把补丁落到错的条目上。
     */
    private suspend fun confirmPlanChange(proposal: PlanChangeProposal) {
        val app = getApplication<Application>()
        val result = runCatching {
            PlanChangeWriter.apply(app, db, proposal.date, proposal.op)
        }.getOrNull()
        when (result) {
            is PlanChangeWriter.Result.Ok -> {
                markApproved(proposal.callUid, 1)
                persistAssistant(app.getString(R.string.receipt_plan_applied, proposal.date))
            }

            is PlanChangeWriter.Result.Error -> _proposalToast.emit(result.message)

            // apply 内部已吞掉校验类异常；此处兜底 DB/IO 等意外
            null -> _proposalToast.emit(app.getString(R.string.receipt_apply_failed))
        }
    }

    /** 目标修改确认：更新既有 active 目标值（没有该目标行 → 失败）。 */
    private suspend fun confirmGoalChange(proposal: GoalChangeProposal) {
        val app = getApplication<Application>()
        val applied = runCatching {
            if (db.goalDao().getByMetric(proposal.metric) == null) {
                false
            } else {
                db.goalDao().setTarget(proposal.metric, proposal.value, System.currentTimeMillis())
                true
            }
        }.getOrDefault(false)
        if (applied) {
            markApproved(proposal.callUid, 1)
            persistAssistant(app.getString(R.string.receipt_goal_applied, proposal.summary))
        } else {
            _proposalToast.emit(app.getString(R.string.receipt_apply_failed))
        }
    }

    /** 记录删除确认：软删 + 置位 [undo]（UI 弹 UndoBar 给 5 秒退回机会）。 */
    private suspend fun confirmRecordDelete(proposal: RecordDeleteProposal) {
        val app = getApplication<Application>()
        val deleted = runCatching { repo.undo(proposal.clientEventId) }.getOrDefault(false)
        if (deleted) {
            markApproved(proposal.callUid, 1)
            persistAssistant(app.getString(R.string.receipt_record_deleted, proposal.summary))
            _undo.emit(proposal)
        } else {
            _proposalToast.emit(app.getString(R.string.receipt_apply_failed))
        }
    }

    /**
     * 回填 `tool_calls.approved`（确认 = 1 / 取消 = 0）。`callUid` 为空（老路径）时跳过。
     * 失败不影响主流程（审计回填是锦上添花）。
     */
    private suspend fun markApproved(callUid: String, approved: Int) {
        if (callUid.isBlank()) return
        runCatching { db.toolCallDao().markApproved(callUid, approved) }
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
