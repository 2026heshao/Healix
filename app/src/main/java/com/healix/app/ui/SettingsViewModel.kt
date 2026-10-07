package com.healix.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.db.EventEntity
import com.healix.app.db.GoalEntity
import com.healix.app.db.GoalMetrics
import com.healix.app.db.GoalSlots
import com.healix.app.db.GoalTypes
import com.healix.app.db.ReminderEntity
import com.healix.app.db.SettingEntity
import com.healix.app.db.SettingsKeys
import com.healix.app.db.ensureActiveGoal
import com.healix.app.net.ChatMessage
import com.healix.app.net.ChatRequest
import com.healix.app.net.ChatResult
import com.healix.app.net.ErrKind
import com.healix.app.net.OpenAiCompatProvider
import com.healix.app.net.ProviderConfig
import com.healix.app.net.ProviderPresets
import com.healix.app.parse.dayKeyOf
import com.healix.app.parse.dayStartHourOf
import com.healix.app.repo.EventRepository
import com.healix.app.repo.ORIGIN_USER
import com.healix.app.repo.PROMPT_VER_NONE
import com.healix.app.repo.SOURCE_APP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** 设置页全部可见值。一次 collect 完，避免十几个 Flow 各自订阅。 */
data class SettingsValues(
    val provider: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val hasApiKey: Boolean = false,
    /** 今日抽取类实际调用次数（配额口径改造后只读展示，上限不可配置）。 */
    val usedExtractToday: Int = 0,
    /** 今日对话类实际调用次数。 */
    val usedChatToday: Int = 0,
    val retry: Int = 5,
    val retryDelay: Double = 1.5,
    val height: Int = 0,
    val weight: Double = 0.0,
    val age: Int = 0,
    val activity: String = "1.2",
    val dayStart: Int = 4,
    /** 用户背景（自由文本）。空 = 未填写，走原 prompt 路径。 */
    val background: String = "",
    /** 自由文本目标（「我的目标」，settings 键 GOAL_STATEMENT）。空 = 未填写。 */
    val goalStatement: String = "",
    /**
     * 文本型自定义次目标（settings 键 [SettingsKeys.CUSTOM_GOAL_TEXT]，清单3 R1）。
     * 空 = 未使用。⚠️ 刻意不进任何 AI prompt 读取集合（见 SettingsKeys 处注释）。
     */
    val customGoalText: String = "",
    val debugSummary: String = "",
    /** 隐私：隐藏热量数字（settings 键 HIDE_KCAL）。 */
    val hideKcal: Boolean = false,
    /** 隐私：隐藏体重数字（settings 键 HIDE_WEIGHT）。 */
    val hideWeight: Boolean = false,
    /**
     * AI 可见资料范围总开关（settings 键 [SettingsKeys.AI_DATA_FULL]）。
     * 键不存在 = 开（默认 true）。checked = aiDataFull 本身（不取反，
     * 与 hideKcal 的「隐藏取反显示」语义相反）。
     */
    val aiDataFull: Boolean = true,
    /**
     * AI 工具总开关（settings 键 [SettingsKeys.AI_TOOLS_ENABLED]，v0.3 B5）。
     * 键不存在 = 开（默认 true；判定口径 `!= "false"`）。
     */
    val aiToolsEnabled: Boolean = true,
    /** 写工具：拟改今日计划（[SettingsKeys.AI_TOOL_WRITE_PLAN]，v0.3 B6，默认开）。 */
    val aiToolWritePlan: Boolean = true,
    /** 写工具：拟删记录（[SettingsKeys.AI_TOOL_WRITE_RECORD]，v0.3 B6，默认开）。 */
    val aiToolWriteRecord: Boolean = true,
    /** 写工具：拟改目标（[SettingsKeys.AI_TOOL_WRITE_GOAL]，v0.3 B6，默认开）。 */
    val aiToolWriteGoal: Boolean = true,
    /** 写工具：拟改画像 / 资源清单（[SettingsKeys.AI_TOOL_WRITE_PROFILE]，P1，默认开）。 */
    val aiToolWriteProfile: Boolean = true,
    /** 写工具：拟改体格与运行设置（[SettingsKeys.AI_TOOL_WRITE_SETTINGS]，P1，默认开）。 */
    val aiToolWriteSettings: Boolean = true,
    /** 写工具：拟增减提醒（[SettingsKeys.AI_TOOL_WRITE_REMINDER]，P2，默认开）。 */
    val aiToolWriteReminder: Boolean = true,
    /**
     * 最近一条 events(type=body) 记录的体重（F5「当前体重」行）。
     * 0 = 从未记录，UI 显示「未记录」。读的是 events 表，不是 settings。
     */
    val latestWeightKg: Double = 0.0,
    /** 最近一条体重记录的 day_key（yyyy-MM-dd），用于「N 天前」相对时间。 */
    val latestWeightDayKey: String = "",
    // ── 结构化画像（F6，settings 表 profile_* 键）─────────────────────
    /** 忌口 / 过敏 / 不吃（硬约束段）。 */
    val profileAllergens: List<String> = emptyList(),
    /** 疼痛 / 不适部位（硬约束段，运动建议必须避开）。 */
    val profilePain: List<String> = emptyList(),
    /** 就餐场景（软背景段）。空 = 未固定。 */
    val profileScene: String = "",
    /** 就寝时间（软背景段，HH:mm）。空 = 未设置。 */
    val profileSleepBed: String = "",
    /** 起床时间（软背景段，HH:mm）。空 = 未设置。 */
    val profileSleepWake: String = "",
)

data class TestResult(val ok: Boolean, val text: String)

/**
 * 接入结果。「接入并启用」按钮的落地点。
 *
 * 与 [TestResult] 分开：测试是"看看通不通"，接入是"确认这个配置被采用"。
 * 两者文案与成功判据不同（接入还会把 provider 标记写库，便于 UI 常驻显示）。
 */
data class ApplyResult(val ok: Boolean, val text: String)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = HealixApp.from(app)
    private val db = container.database
    private val settings = db.settingsDao()

    private val _values = MutableStateFlow(SettingsValues())
    val values: StateFlow<SettingsValues> = _values.asStateFlow()

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    private val _applyResult = MutableStateFlow<ApplyResult?>(null)
    val applyResult: StateFlow<ApplyResult?> = _applyResult.asStateFlow()

    /**
     * v8 问题 2a：老用户的 `TARGET_KCAL` 是否**刚刚**被迁进 `goals` 表。
     *
     * 只用于驱动设置页「目标」栏的一次性提示 `热量目标现已移至此栏`。
     * 用 StateFlow 而不是"让 UI 去读 settings 标记"是为了**消除竞态** ——
     * 迁移跑在 `init` 的协程里，UI 若直接读标记，可能读到迁移完成前的那一刻。
     */
    private val _kcalMoved = MutableStateFlow(false)
    val kcalMoved: StateFlow<Boolean> = _kcalMoved.asStateFlow()

    /**
     * 是否已接入。
     *
     * 判据 = baseUrl + model + apiKey 三者齐备（即 [ProviderConfig.isUsable]）。
     * 这里**只做本地判断，不发网络请求** —— 状态条是常驻 UI，
     * 每次进设置页都发一次请求是不可接受的。
     * 真实连通性由「测试连通性」按钮显式验证。
     */
    private val _applied = MutableStateFlow(false)
    val applied: StateFlow<Boolean> = _applied.asStateFlow()

    // ── 目标（goals 表）─────────────────────────────────────────────
    // 直接订阅 DAO 的 Flow，而不是并进 SettingsValues：
    // 目标是**多行结构**（主目标 / 体重 / 训练 / 睡眠 / 饮水各一行），
    // 压成一个 data class 的字段会在增删维度时处处改签名。
    /** 当前 active 目标，按 is_primary DESC 排序。 */
    val goals: StateFlow<List<GoalEntity>> = db.goalDao().observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 已归档目标（v8 需求 4）。「添加目标」弹窗据此判断哪些槽位可恢复。
     * 只读订阅，写操作走 [archiveSlot] / [ensureSlotActive]。
     */
    val archivedGoals: StateFlow<List<GoalEntity>> = db.goalDao().observeArchived()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── 提醒（reminders 表）─────────────────────────────────────────
    /** 启用中的提醒，按到期日升序。 */
    val reminders: StateFlow<List<ReminderEntity>> = db.reminderDao().observeEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 规则库条数（v0.3 B4）：设置页「规则库」入口行的右侧值。 */
    val ruleCount: StateFlow<Int> = db.aiRuleDao().observeAll()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // v6（11.1）：知识库文档数入口已迁「我的」页（MineFragment.observeKnowledgeCount 直连 DAO），
    // 设置页不再展示，此 Flow 与 exportBackup() 一并移除。

    init {
        viewModelScope.launch {
            // ⚠️ v8 问题 2b：**不再预置任何默认目标**（原 `ensureGoalDefaultsIfEmpty()`
            //    已在全新安装时静默灌 6 行、含主目标=增重）—— 与 v8 需求 4 删默认提醒同款诉求。
            //    现在：主目标只由首启引导 [com.healix.app.ui.GoalSetupSheet] 落一行；
            //    其余槽位一律由用户「添加目标」显式创建。
            //    老用户设备上已存在的目标行**不删**（只停止预置行为）。
            // v8 问题 2a：把历史 settings 键 `TARGET_KCAL` 一次性迁成 `goals` 的 kcal 行，
            //    保证升级用户的热量目标不丢、且从目标栏可改。
            migrateLegacyKcalTarget()
            reload()
        }
    }

    /**
     * 老用户迁移（v8 问题 2a）：settings 键 `TARGET_KCAL` → `goals` 的 `kcal_daily` 行。
     *
     * 判据与幂等：
     * - 表里已有 `kcal_daily` 行（**任意状态**，含 archived）→ 视为"已收编"，直接跳过，
     *   绝不覆盖用户的新值；
     * - 旧键不存在 / 非数字 / ≤0（老用户从没设过）→ 不建行、不留提示。
     *
     * 迁移完成后写 [SettingsKeys.KCAL_TARGET_MIGRATED]，供设置页「目标」栏显示一次性
     * 提示 `热量目标现已移至此栏`（[SettingsKeys.KCAL_MOVE_HINT_SEEN] 记录是否已展示）。
     */
    private suspend fun migrateLegacyKcalTarget() {
        withContext(Dispatchers.IO) {
            if (db.goalDao().getByMetricAny(GoalMetrics.KCAL_DAILY) != null) return@withContext
            val legacy = settings.get(SettingsKeys.TARGET_KCAL)?.toIntOrNull() ?: return@withContext
            if (legacy <= 0) return@withContext
            val now = System.currentTimeMillis()
            // 补行（含 type 映射）走唯一来源 [ensureActiveGoal]，再把值换成老用户已设的数。
            ensureActiveGoal(db, GoalMetrics.KCAL_DAILY, now)
            db.goalDao().setTarget(GoalMetrics.KCAL_DAILY, legacy.toDouble(), now)
            // 数据已落到 goals 行 → **删除旧键**：从此 kcal 目标只有一个来源。
            //（`kcalTargetOf` 的"读旧键"分支只为覆盖本协程跑完之前的窗口期。）
            settings.remove(SettingsKeys.TARGET_KCAL)
            settings.put(SettingEntity(key = SettingsKeys.KCAL_TARGET_MIGRATED, value = "true"))
            _kcalMoved.value = true
        }
    }

    // ⚠️ v8 问题 2b：原 `ensureGoalDefaultsIfEmpty()`（全新安装静默灌 6 行默认目标，
    //    含主目标=增重）已**整体删除** —— 与 v8 需求 4 删默认提醒同款诉求。
    //    替代路径：主目标由首启引导 `GoalSetupSheet` 落一行（用户亲手选增重/减重/保持）；
    //    其余槽位由「添加目标」显式创建。下游消费方读不到行时回落 `GoalDefaults` 的机制
    //    **本就具备**（如 `kcalTargetOf` / `HomeGoal` / `trainProgress`），零消费方改动。
    //
    //    主目标的数值编码（`metric = primary` / `type = goal_mode` /
    //    `target_value` ∈ {0=增重 / 1=减重 / 2=保持}）说明见 [defaultGoal] 与本文件
    //    `PRIMARY` 分支；`HealthAggregator` 读 `getByMetric(PRIMARY).targetValue.toInt()`
    //    判 `isWeightLossGoal`。

    // ⚠️ 原 `defaultGoal(metric, weightTarget, now)`（本文件私有）已**整体上移**到
    //    `db/GoalEntities.kt` 的 file-private `defaultGoalRow(...)` —— 与「缺行补偿」
    //    [ensureActiveGoal] 同处一地（缺行补偿要在 MainViewModel / ChatViewModel 也用到，
    //    放在任何一个 ViewModel 里都会造成"第二份补行逻辑"）。

    private suspend fun reload() {
        val all = settings.listAll().associate { it.key to it.value }
        val quotas = container.quotaGuard
        val latestBody = loadLatestBodyWeight()

        _values.value = SettingsValues(
            provider = all[SettingsKeys.PROVIDER].orEmpty(),
            baseUrl = all[SettingsKeys.BASE_URL].orEmpty(),
            model = all[SettingsKeys.MODEL].orEmpty(),
            hasApiKey = container.secretStore?.hasApiKey() == true,
            usedExtractToday = quotas.usedExtractToday(),
            usedChatToday = quotas.usedChatToday(),
            retry = all[SettingsKeys.RETRY]?.toIntOrNull() ?: 5,
            retryDelay = all[SettingsKeys.RETRY_DELAY]?.toDoubleOrNull() ?: 1.5,
            height = all[SettingsKeys.HEIGHT]?.toIntOrNull() ?: 0,
            weight = all[SettingsKeys.WEIGHT]?.toDoubleOrNull() ?: 0.0,
            age = all[SettingsKeys.AGE]?.toIntOrNull() ?: 0,
            activity = all[SettingsKeys.ACTIVITY] ?: "1.2",
            dayStart = dayStartHourOf(all[SettingsKeys.DAY_START]),
            background = all[SettingsKeys.BACKGROUND].orEmpty(),
            goalStatement = all[SettingsKeys.GOAL_STATEMENT].orEmpty(),
            customGoalText = all[SettingsKeys.CUSTOM_GOAL_TEXT].orEmpty(),
            debugSummary = "今日 ${quotas.usedToday()} 次 · 失败 ${quotas.failedToday()}",
            hideKcal = all[SettingsKeys.HIDE_KCAL] == "true",
            hideWeight = all[SettingsKeys.HIDE_WEIGHT] == "true",
            // 判定口径与 ProfileContext.aiDataFull 同源：!= "false"（键不存在 = 开）
            aiDataFull = all[SettingsKeys.AI_DATA_FULL] != "false",
            // AI 工具与写权限（v0.3 B5/B6）：同为「键不存在 = 开」（D4）。
            aiToolsEnabled = all[SettingsKeys.AI_TOOLS_ENABLED] != "false",
            aiToolWritePlan = all[SettingsKeys.AI_TOOL_WRITE_PLAN] != "false",
            aiToolWriteRecord = all[SettingsKeys.AI_TOOL_WRITE_RECORD] != "false",
            aiToolWriteGoal = all[SettingsKeys.AI_TOOL_WRITE_GOAL] != "false",
            aiToolWriteProfile = all[SettingsKeys.AI_TOOL_WRITE_PROFILE] != "false",
            aiToolWriteSettings = all[SettingsKeys.AI_TOOL_WRITE_SETTINGS] != "false",
            aiToolWriteReminder = all[SettingsKeys.AI_TOOL_WRITE_REMINDER] != "false",
            latestWeightKg = latestBody?.weightKg ?: 0.0,
            latestWeightDayKey = latestBody?.dayKey.orEmpty(),
            profileAllergens = parseProfileList(all[SettingsKeys.PROFILE_ALLERGENS]),
            profilePain = parseProfileList(all[SettingsKeys.PROFILE_PAIN]),
            profileScene = all[SettingsKeys.PROFILE_SCENE].orEmpty(),
            profileSleepBed = all[SettingsKeys.PROFILE_SLEEP_BED].orEmpty(),
            profileSleepWake = all[SettingsKeys.PROFILE_SLEEP_WAKE].orEmpty(),
        )

        // 接入状态：三要素齐备即视为已接入（纯本地判断，不发请求）
        val baseUrl = all[SettingsKeys.BASE_URL].orEmpty().trim()
        val model = all[SettingsKeys.MODEL].orEmpty().trim()
        val hasKey = container.secretStore?.hasApiKey() == true
        _applied.value = baseUrl.isNotEmpty() && model.isNotEmpty() && hasKey &&
            !baseUrl.startsWith("[待核实") && !model.startsWith("[待核实")
    }

    /**
     * 二级页被重新展示时的公开刷新入口（v0.3 B3）。
     *
     * 只是私有 [reload] 的收口出口 —— **不**把 `reload` 直接改 public（外部只知"刷新"，
     * 不必知道内部是否重算 / 如何重算）。纯本地读库，0 AI。
     */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { reload() }
    }

    /**
     * 「当前体重」行（F5）：读最近一条 events(type=body) 记录。
     *
     * 复用现成的 [com.healix.app.db.EventDao.weightRowsInRange]（已过滤
     * weight_kg > 0 与软删），取升序结果的最后一条，不写新 SQL。
     * 窗口取 365 天：这是"最近一次量过"的展示位，不是统计口径。
     */
    private suspend fun loadLatestBodyWeight(): EventEntity? {
        val today = LocalDate.now()
        return runCatching {
            db.eventDao().weightRowsInRange(
                today.minusDays(365).toString(),
                today.toString(),
            ).lastOrNull()
        }.getOrNull()
    }

    /**
     * 写入一条当前体重记录（F5）。
     *
     * 走 events 表的现有插入链（[com.healix.app.db.EventDao.insertIgnore]，
     * 与「记一笔」落库同一条 EventDao 管道），source=app、parse_status=done
     * （数值已结构化，无需再过 AI 抽取），**不**往 settings 表写静态体重字段。
     * day_key 用与全 App 一致的日界线（默认 4:00）计算。
     */
    fun saveCurrentWeight(kg: Double) {
        if (kg <= 0.0) return
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            // 日界线走唯一入口 dayStartHourOf（§1 收口）。
            val dayStart = dayStartHourOf(settings.get(SettingsKeys.DAY_START))
            db.eventDao().insertIgnore(
                EventEntity(
                    clientEventId = java.util.UUID.randomUUID().toString(),
                    ts = now,
                    dayKey = dayKeyOf(now, dayStart),
                    rawText = "体重 ${kg} kg",
                    type = "body",
                    weightKg = kg,
                    source = SOURCE_APP,
                    parseStatus = com.healix.app.repo.EventRepository.PARSE_DONE,
                    origin = ORIGIN_USER,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            reload()
        }
    }

    /** 读原始值（编辑对话框回显用）。apiKey 不走这里 —— 它永远不回显。 */
    suspend fun raw(key: String): String? = settings.get(key)

    /** 画像 JSON 数组 → 列表（防御性：解析失败/空串给空列表，不抛异常）。 */
    private fun parseProfileList(json: String?): List<String> =
        runCatching { com.healix.app.repo.parseFoodsJson(json.orEmpty()) }.getOrDefault(emptyList())

    /** 写设置并刷新。空值等同清除该项。 */
    fun put(key: String, value: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (value.isBlank()) {
                settings.remove(key)
            } else {
                settings.put(SettingEntity(key = key, value = value))
            }
            reload()
        }
    }

    fun saveApiKey(key: String) {
        viewModelScope.launch {
            // 只写 EncryptedSharedPreferences，绝不落 SQLite（docs/security.md）
            container.secretStore?.saveApiKey(key)
            reload()
        }
    }

    // ── 目标（goals 表）─────────────────────────────────────────────

    /**
     * 设置主目标。`modeIndex` ∈ {0=增重 / 1=减重 / 2=保持}。
     *
     * `metric = GoalMetrics.PRIMARY` 这一行同时承载"主目标是哪个模式"，
     * `setPrimary` 把 is_primary=1 落到它、其余清 0。两步都要做：
     * setPrimary 管排序，setTarget 管取值，缺一会让首页大数字与训练处方不一致。
     *
     * ⚠️ 两个 DAO 都是 **UPDATE**（缺行静默 no-op）→ 先 [ensureActiveGoal] 补行。
     */
    fun setPrimaryGoal(modeIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            ensureActiveGoal(db, GoalMetrics.PRIMARY, now)
            db.goalDao().setPrimary(GoalMetrics.PRIMARY, now)
            db.goalDao().setTarget(GoalMetrics.PRIMARY, modeIndex.toDouble(), now)
        }
    }

    /**
     * 设置自定义主目标（`GOAL_MODE_CUSTOM`）：mode 落 `goals` 表，
     * 文本落 [SettingsKeys.GOAL_STATEMENT]（唯一自由文本目标键，AI prompt 同源读取）。
     * 空白文本拒写（无内容的自定义没有意义，调用端已先校验，这里兜底）。
     *
     * ⚠️ 同上：先 [ensureActiveGoal] 补行再写（缺行时 `setPrimary`/`setTarget` 是 no-op）。
     */
    fun setPrimaryGoalCustom(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            ensureActiveGoal(db, GoalMetrics.PRIMARY, now)
            db.goalDao().setPrimary(GoalMetrics.PRIMARY, now)
            db.goalDao().setTarget(GoalMetrics.PRIMARY, GOAL_MODE_CUSTOM.toDouble(), now)
            db.settingsDao().put(SettingEntity(SettingsKeys.GOAL_STATEMENT, trimmed))
        }
    }

    /**
     * 改某个目标值（热量 / 体重 / 训练次数 / 训练分钟 / 睡眠 / 饮水）。
     *
     * ⚠️ 2026-10-07 真机 bug 的修复点：`setTarget` 是 **UPDATE**，
     * `WHERE metric = ? AND status = 'active'` 匹配不到行时**静默 no-op** ——
     * 而设置页「热量摄入」栏的 kcal 行是**固定行**（恒可见/恒可点），
     * v8 问题 2b 之后新装机 `goals` 表却是空的 → 用户改热量目标"点了确认没反应"。
     * 因此写之前必须先 [ensureActiveGoal] 补行（缺则建 / 曾归档则恢复）。
     */
    fun setGoalTarget(metric: String, value: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            ensureActiveGoal(db, metric, now)
            db.goalDao().setTarget(metric, value, now)
        }
    }

    /**
     * 写/清文本型自定义次目标（清单3 R1）：文本落 [SettingsKeys.CUSTOM_GOAL_TEXT]，
     * 空白 = 清键（撤销恢复与左滑清除共用同一条写链）。
     * 写法对齐 [toggleHide]（IO 协程 put + reload），调用端已先校验非空，这里兜底。
     * 目标行本身不落 `goals` 表（文本型无数值，schema 零变更），只渲染于 UI。
     */
    fun setCustomGoalText(text: String) {
        val trimmed = text.trim()
        viewModelScope.launch(Dispatchers.IO) {
            if (trimmed.isEmpty()) {
                settings.remove(SettingsKeys.CUSTOM_GOAL_TEXT)
            } else {
                settings.put(SettingEntity(key = SettingsKeys.CUSTOM_GOAL_TEXT, value = trimmed))
            }
            reload()
        }
    }

    // ── 目标归档 / 恢复（v8 需求 4：左滑删除 + 添加目标）───────────────

    /**
     * 归档一个目标槽位（左滑删除的落地点）。
     *
     * **成组归档**：`Train` 槽位含 2 个 metric，必须一起归档，否则会出现
     * "删了次数、时长还在"的半个目标（见 [GoalSlots] 头注释）。
     * 归档 = `status='archived'`，**不物理删除**：`observeActive()` 立即不再返回它，
     * 但数据仍在表里，「添加目标」可原值恢复。
     *
     * 一次操作内所有行用**同一个 `now`**，避免 `updated_at` 毫秒差。
     */
    fun archiveSlot(slot: GoalSlots.Slot) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            slot.metrics.forEach { db.goalDao().archiveGoal(it, now) }
        }
    }

    /**
     * 让一个目标槽位重新生效（「添加目标」的落地点）。
     *
     * 逐 metric 判定，兼容两种来源：
     * - 表里已有该 metric 的行（曾归档）→ [GoalDao.restoreGoal] 改回 active，**保留原值**；
     * - 表里根本没有该 metric 的行（如用户跳过引导、或 v8 问题 2b 删掉默认种子后的
     *   全新安装）→ 用 [defaultGoal] 补一行（默认值）。
     *
     * 幂等：槽位已 active 时两步都不产生变化。
     */
    fun ensureSlotActive(slot: GoalSlots.Slot) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            // 逐 metric 的「缺行补偿」收敛到唯一来源 [ensureActiveGoal]（体重槽位默认值取
            // 用户已填 WEIGHT 的口径也在那里）。一次操作内所有行共用同一个 now。
            slot.metrics.forEach { ensureActiveGoal(db, it, now) }
        }
    }

    // ── 隐私（settings 表：HIDE_KCAL / HIDE_WEIGHT）─────────────────

    /**
     * 切换一个布尔隐私开关。`key` 只允许传 `SettingsKeys.HIDE_*`（键名纪律）。
     * 状态存 `"true"` / `"false"`，默认（键不存在）视为 `false`。
     *
     * ⚠️ v8 需求 6：设置页「隐私」组 UI 入口已移除，本方法**当前没有调用点**，
     * 但**刻意保留** —— 两个键与全部消费方（首页汇总区 / 状态详情页 / 对话系统提示）
     * 仍在生效，老用户设备上的既有取值继续沿用（架构设计 §Q3：不新增兜底入口）。
     * 将来若在别处（如「我的」页）恢复入口，直接调本方法即可，无需重建写入链。
     */
    fun toggleHide(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val hidden = settings.get(key) == "true"
            settings.put(SettingEntity(key = key, value = if (hidden) "false" else "true"))
            reload()
        }
    }

    /**
     * 切换「AI 可见资料范围」总开关（SettingsKeys.AI_DATA_FULL）。
     * 键不存在视为开 → 首次关闭写入 "false"，再次打开写回 "true"。
     * 写法镜像 [toggleHide]（IO 协程 put + reload）；刻意不并入 toggleHide ——
     * 后者的 KDoc 约束「key 只允许 HIDE_*」保持成立，避免语义扩散。
     */
    fun toggleAiDataFull() {
        viewModelScope.launch(Dispatchers.IO) {
            val full = settings.get(SettingsKeys.AI_DATA_FULL) != "false"
            settings.put(SettingEntity(key = SettingsKeys.AI_DATA_FULL, value = if (full) "false" else "true"))
            reload()
        }
    }

    /**
     * 切换一个 AI 工具权限开关（v0.3 B5/B6）。`key` ∈ {AI_TOOLS_ENABLED, AI_TOOL_WRITE_*}。
     *
     * 语义统一为「默认开」（决策 D4）：键不存在视为开 → 当前为开则写 `"false"`、否则写 `"true"`。
     * 写法镜像 [toggleAiDataFull]（IO 协程 put + reload）。
     *
     * ⚠️ 这只是**用户意图的持久化**；真正的拦截在 `HealthAgent` 执行层（纵深防御），
     *    关掉写权限后即使模型构造出写调用也会被拒（不产 draft）。
     */
    fun toggleAiSwitch(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val on = settings.get(key) != "false"
            settings.put(SettingEntity(key = key, value = if (on) "false" else "true"))
            reload()
        }
    }

    // ── 提醒（reminders 表）─────────────────────────────────────────

    /**
     * 新增 / 编辑一条提醒。
     *
     * `nextDueAt = (lastDoneAt ?: 今天) + intervalDays` —— "上次日期"一旦填了，
     * 到期日就由它推；没填则从今天起算（新提醒的自然语义）。
     * `existing == null` 表示新增（id=0 由 Room autoGenerate）。
     */
    fun saveReminder(
        existing: ReminderEntity?,
        name: String,
        intervalDays: Int,
        lastDoneAt: Long?,
    ) {
        if (name.isBlank() || intervalDays <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val base = lastDoneAt ?: now
            db.reminderDao().upsert(
                ReminderEntity(
                    id = existing?.id ?: 0,
                    name = name,
                    intervalDays = intervalDays,
                    lastDoneAt = lastDoneAt,
                    nextDueAt = base + intervalDays.toLong() * HealixDate.DAY_MS,
                    enabled = 1,
                    createdAt = existing?.createdAt ?: now,
                )
            )
        }
    }

    /** 标记已完成：按周期从今天顺延（PRD §5.4「点已完成 → 顺延」）。 */
    fun markReminderDone(reminder: ReminderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            db.reminderDao().markDone(reminder.id, now, now + reminder.intervalDays.toLong() * HealixDate.DAY_MS)
        }
    }

    fun deleteReminder(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { db.reminderDao().delete(id) }
    }

    /**
     * 撤销删除：把一条提醒**原样**插回（v8 需求 4 左滑删除的撤销落地点）。
     *
     * 与 [saveReminder] 的区别：后者会按"上次日期 + 周期"**重算** `nextDueAt`，
     * 用于用户编辑；本方法直接 `upsert` 整个快照 → id 与 `next_due_at` 与删除前**逐字一致**，
     * 撤销后行回到原位（排序键 `next_due_at` 不变）。
     */
    fun restoreReminder(reminder: ReminderEntity) {
        viewModelScope.launch(Dispatchers.IO) { db.reminderDao().upsert(reminder) }
    }

    fun providerNames(): List<String> =
        ProviderPresets.ordered().map { it.second.name }

    /**
     * 选预设服务商：自动填 baseUrl 与模型名。
     *
     * 预设已于 2026-10-03 对着官方文档核实并填入真实值（见 `LlmProvider.kt`
     * 的 `ProviderPresets` 头注释与 `docs/待核实清单.md` 第二节）。
     * 选中"自定义"时不覆盖用户已填内容。
     *
     * `[待核实` 前缀的兜底判断保留 —— 若将来官方变更导致预设需回退为占位，
     * 这条路径仍能给出明确提示而不是静默失败。
     */
    fun selectProvider(position: Int) {
        val entry = ProviderPresets.ordered().getOrNull(position) ?: return
        val (key, preset) = entry

        viewModelScope.launch(Dispatchers.IO) {
            settings.put(SettingEntity(SettingsKeys.PROVIDER, key))

            // 自定义不覆盖，保留用户手填的值
            if (key != ProviderPresets.DEFAULT_KEY) {
                settings.put(SettingEntity(SettingsKeys.BASE_URL, preset.baseUrl))
                settings.put(SettingEntity(SettingsKeys.MODEL, preset.model))
            }

            reload()

            _testResult.value = if (preset.baseUrl.startsWith("[待核实") ||
                preset.model.startsWith("[待核实")
            ) {
                TestResult(
                    ok = false,
                    text = "该服务的接口地址与模型名尚未核实，请对照官方文档填写后再测试",
                )
            } else {
                null
            }
        }
    }

    /**
     * 连通性测试：发一条 `hi`，显示耗时与错误码。
     *
     * 这是 S1 的验收点：设置页配好后点测试，应看到「连通 · 1.2s」。
     */
    fun testConnectivity(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val v = _values.value
            val key = container.secretStore?.apiKey()

            when {
                v.baseUrl.isBlank() || v.model.isBlank() -> {
                    _testResult.value = TestResult(false, "接口地址或模型名为空")
                    onDone()
                    return@launch
                }
                key.isNullOrBlank() -> {
                    _testResult.value = TestResult(false, "API Key 未填写")
                    onDone()
                    return@launch
                }
                v.baseUrl.startsWith("[待核实") || v.model.startsWith("[待核实") -> {
                    _testResult.value = TestResult(false, "当前是占位地址，请先填真实值")
                    onDone()
                    return@launch
                }
            }

            val config = ProviderConfig(baseUrl = v.baseUrl, model = v.model, apiKey = key)
            val provider = OpenAiCompatProvider(config)
            // 提成局部 request：既传给 provider.chat，也复用于埋点（拿 maxRetries 算 attempts）。
            val request = ChatRequest(
                messages = listOf(ChatMessage(role = "user", content = "hi")),
                timeoutMs = 15_000L,
                maxRetries = 1, // 测试不重试，快速给出真实结论
            )

            val started = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                provider.chat(request)
            }
            val elapsed = System.currentTimeMillis() - started
            val seconds = "%.1f".format(elapsed / 1000.0)

            // 埋点：真实请求落 llm_calls（purpose=test，不占配额，仅供统计/调试）。
            recordProviderRequest(config, request, result, elapsed)

            _testResult.value = when (result) {
                is ChatResult.Ok -> TestResult(true, "连通 · ${seconds}s")
                is ChatResult.Err -> {
                    val code = result.httpCode?.let { "HTTP $it · " }.orEmpty()
                    TestResult(false, "连接失败 · $code${seconds}s · ${result.message}")
                }
            }
            onDone()
        }
    }

    /**
     * 「接入并启用」。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 与「测试连通性」的区别（这是用户明确提出的诉求）
     * ══════════════════════════════════════════════════════════════════════════
     * 用户的原话是「AI 模型没有真实的接入按钮，配置无法生效」。拆开看是两件事：
     *
     *   a) **缺一个显式的落地点** —— 填完四个框后没有任何"确认采用"的动作，
     *      用户不知道配置到底生效没有。这是**心智模型**问题。
     *   b) **配置真的没生效** —— 那是 settings 键名分裂 bug（见 SettingsKeys
     *      头注释），已修。修完后 a) 仍然存在：界面上依旧没有"已接入"的反馈。
     *
     * 本方法同时解决 a)：
     *   1. 校验三要素齐备，缺哪项就明确点名（不说"配置无效"这种废话）
     *   2. 显式把 provider 预设 key 落库 —— 让"接入"产生一条可追溯的写入
     *   3. 立刻发一次真实请求验证（只重试 1 次，快速给结论）
     *   4. 成功 → 状态条变「已接入 · 服务商 · 模型名」；失败 → 保留原状态并说明原因
     *
     * 失败**不回滚**已写入的配置：用户可能只是当下网络不好，
     * 配置本身留着更方便，重试一次即可。
     */
    fun applyProvider(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val v = _values.value
            val key = container.secretStore?.apiKey()

            // ── 第 1 步：逐项校验，缺什么就说什么 ──────────────────────
            val missing = buildList {
                if (v.baseUrl.isBlank()) add(app.getString(R.string.apply_name_base_url))
                if (v.model.isBlank()) add(app.getString(R.string.apply_name_model))
                if (key.isNullOrBlank()) add(app.getString(R.string.apply_name_key))
            }
            if (missing.isNotEmpty()) {
                _applyResult.value = ApplyResult(
                    ok = false,
                    text = app.getString(R.string.apply_missing, missing.joinToString("、")),
                )
                onDone()
                return@launch
            }

            if (v.baseUrl.startsWith("[待核实") || v.model.startsWith("[待核实")) {
                _applyResult.value = ApplyResult(false, app.getString(R.string.apply_placeholder))
                onDone()
                return@launch
            }

            _applyResult.value = ApplyResult(true, app.getString(R.string.apply_applying))

            // ── 第 2 步：真实请求验证（不重试，快速给结论）───────────────
            val config = ProviderConfig(baseUrl = v.baseUrl, model = v.model, apiKey = key!!)
            val provider = OpenAiCompatProvider(config)
            // 提成局部 request：既传给 provider.chat，也复用于埋点（拿 maxRetries 算 attempts）。
            val request = ChatRequest(
                messages = listOf(ChatMessage(role = "user", content = "hi")),
                timeoutMs = 15_000L,
                maxRetries = 1,
            )

            val started = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                provider.chat(request)
            }
            val elapsed = System.currentTimeMillis() - started
            val seconds = "%.1f".format(elapsed / 1000.0)

            // 埋点：真实请求落 llm_calls（purpose=test，不占配额，仅供统计/调试）。
            recordProviderRequest(config, request, result, elapsed)

            _applyResult.value = when (result) {
                is ChatResult.Ok ->
                    ApplyResult(true, app.getString(R.string.apply_ok, "${seconds}s"))

                is ChatResult.Err -> {
                    val code = result.httpCode?.let { "HTTP $it · " }.orEmpty()
                    ApplyResult(false, app.getString(R.string.apply_fail, "$code${result.message}"))
                }
            }

            // 无论成败都重算状态条（成功时 hasApiKey 可能刚变 true）
            reload()
            onDone()
        }
    }

    /**
     * 设置页"真实请求"埋点（purpose = [com.healix.app.repo.PURPOSE_TEST]）。
     *
     * 「测试连通性」与「接入并启用」都发一次真实请求（消耗免费额度），此前**完全不计数**
     * → `llm_calls` 缺行、调试页数据不完整。这里补一条，**不占配额**
     * （PURPOSE_TEST 刻意不在 QuotaGuard 的 CALL_PURPOSES / CHAT_PURPOSES 里）。
     * 测试链无 system prompt → promptVer = [PROMPT_VER_NONE]。
     *
     * ⚠️ 埋点写入走 IO：`EventRepository.recordCall` 内部直连 `llmCallDao.insert`
     *    （suspend Room 方法，Room 自行切到 DB executor），这里仍显式
     *    `withContext(Dispatchers.IO)`，与调用点上下文解耦。埋点失败不影响主流程
     *    （repository 内部已 try/catch 吞掉）。
     *
     * attempts 口径与 plan 链一致：`ChatResult.Err.attempts` 可能为 0 →
     * 回落 `request.maxRetries + 1`；成功恒为 1。
     */
    private suspend fun recordProviderRequest(
        config: ProviderConfig,
        request: ChatRequest,
        result: ChatResult,
        latencyMs: Long,
    ) {
        val repo = container.eventRepository
        withContext(Dispatchers.IO) {
            when (result) {
                is ChatResult.Ok -> repo.recordTestCall(
                    model = config.model,
                    attempts = 1,
                    latencyMs = latencyMs,
                    status = EventRepository.STATUS_OK,
                    httpCode = 200,
                    inputTokens = result.usage.inputTokens,
                    outputTokens = result.usage.outputTokens,
                    promptVer = PROMPT_VER_NONE,
                )

                is ChatResult.Err -> repo.recordTestCall(
                    model = config.model,
                    attempts = if (result.attempts > 0) result.attempts else request.maxRetries + 1,
                    latencyMs = latencyMs,
                    status = when (result.kind) {
                        ErrKind.TIMEOUT -> EventRepository.STATUS_TIMEOUT
                        ErrKind.AUTH -> EventRepository.STATUS_HTTP_ERROR
                        else -> EventRepository.STATUS_RETRY_EXHAUSTED
                    },
                    httpCode = result.httpCode,
                    errorHead = result.message,
                    promptVer = PROMPT_VER_NONE,
                )
            }
        }
    }

    companion object {
        // ⚠️ 「一天的毫秒数」已收敛到唯一来源 [HealixDate.DAY_MS]（同包 object）——
        //    本类原先自带一份私有副本，与 UI 层那份并存 = 改一处漏一处。

        /** 主目标行（`metric = PRIMARY`）的 `type`。 */

        /**
         * 主目标编码：0=增重 / 1=减重 / 2=保持 / 3=自定义。
         *
         * ⚠️ 值的**唯一来源**是 [GoalTypes.MODE_*]（db 层）—— 这里只做转发，
         * 与 `SettingsFragment.KEY_*` 转发 `SettingsKeys` 同款。db 层的
         * `defaultGoalRow` 要造主目标默认行，而 db 不得反向引用 ui（分层倒挂）。
         */
        const val GOAL_MODE_GAIN = GoalTypes.MODE_GAIN
        const val GOAL_MODE_LOSS = GoalTypes.MODE_LOSS
        const val GOAL_MODE_KEEP = GoalTypes.MODE_KEEP

        /**
         * 主目标第 4 态：自定义（`target_value = 3`）。
         *
         * 自定义文本**复用** settings 键 [SettingsKeys.GOAL_STATEMENT]（原个人信息页
         * 「我的目标」自述）：该键已进 AI 计划 prompt（PlanGenerator「目标（用户自述）」）
         * 与首页目标流（MainViewModel.homeGoal.statement），合并后三处同源同值，
         * 不新增第二个自由文本键。
         */
        const val GOAL_MODE_CUSTOM = GoalTypes.MODE_CUSTOM

        // ⚠️ 默认目标值（膳食指南推荐量）不在本文件定义 —— 唯一来源是
        //    `com.healix.app.db.GoalDefaults`。这里曾有一份私有副本
        //    （名为 DEFAULT_TRAIN_SESSIONS），与 StatusDetailViewModel 的同值常量
        //    造成重复定义，已收敛。见 `db/GoalEntities.kt` 的 GoalDefaults 注释。
    }
}
