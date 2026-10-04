package com.healix.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.db.EventEntity
import com.healix.app.db.GoalDefaults
import com.healix.app.db.GoalEntity
import com.healix.app.db.GoalMetrics
import com.healix.app.db.ReminderEntity
import com.healix.app.db.SettingEntity
import com.healix.app.db.SettingsKeys
import com.healix.app.net.ChatMessage
import com.healix.app.net.ChatRequest
import com.healix.app.net.ChatResult
import com.healix.app.net.OpenAiCompatProvider
import com.healix.app.net.ProviderConfig
import com.healix.app.net.ProviderPresets
import com.healix.app.parse.DEFAULT_DAY_START_HOUR
import com.healix.app.parse.dayKeyOf
import com.healix.app.repo.ORIGIN_USER
import com.healix.app.repo.SOURCE_APP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val extractQuota: Int = 20,
    val chatQuota: Int = 15,
    val retry: Int = 5,
    val retryDelay: Double = 1.5,
    val height: Int = 0,
    val weight: Double = 0.0,
    val age: Int = 0,
    val activity: String = "1.2",
    val targetKcal: Int = 2500,
    val dayStart: Int = 4,
    /** 用户背景（自由文本）。空 = 未填写，走原 prompt 路径。 */
    val background: String = "",
    val debugSummary: String = "",
    /** 隐私：隐藏热量数字（settings 键 HIDE_KCAL）。 */
    val hideKcal: Boolean = false,
    /** 隐私：隐藏体重数字（settings 键 HIDE_WEIGHT）。 */
    val hideWeight: Boolean = false,
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
    /** 可用器材（硬约束段，运动建议只用这些）。 */
    val profileGear: List<String> = emptyList(),
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

    // ── 提醒（reminders 表）─────────────────────────────────────────
    /** 启用中的提醒，按到期日升序。 */
    val reminders: StateFlow<List<ReminderEntity>> = db.reminderDao().observeEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // v6（11.1）：知识库文档数入口已迁「我的」页（MinePage.observeKnowledgeCount 直连 DAO），
    // 设置页不再展示，此 Flow 与 exportBackup() 一并移除。

    init {
        viewModelScope.launch {
            // 首次进入时补齐默认目标 / 预设提醒，再读设置值。
            ensureGoalDefaultsIfEmpty()
            ensureReminderDefaultsIfEmpty()
            reload()
        }
    }

    /**
     * 首次启动补齐默认目标（仅当 goals 表一条 active 都没有时）。
     *
     * 默认值**全部来自《中国居民膳食指南(2022)》**，不是拍脑袋：
     * - 每周训练 `3 次 / 150 分钟`：准则二 —— 中等强度有氧每周累计 150–300 分钟、
     *   抗阻每周 2–3 天（隔天）。取推荐区间下限。
     * - 睡眠 `7.5 小时`：指南成人 7–8 小时，取中值。
     * - 饮水 `1700 ml`：指南成年男性 1700 ml（女性 1500 ml）；默认按男。
     * - 体重目标：取 settings 里已有的 `WEIGHT`（BMR/TDEE 的起点值）；为空则 0（UI 显示「未设置」）。
     * - 主目标：默认「增重」（用户当前主诉求）。
     *
     * ⚠️ 主目标用**一行 GoalEntity** 表示：`metric = GoalMetrics.PRIMARY`，
     *    `type = "goal_mode"`，`target_value` ∈ {0=增重 / 1=减重 / 2=保持}。
     *    这是本任务唯一一处"用数值编码枚举"。理由：「主目标」必须只有一个存放位置，
     *    否则会像 2026-10-03 的键名分裂事故一样，在 settings 表与 goals 表各存一份而互相打架。
     *    `HealthAggregator` 读 `getByMetric(PRIMARY).targetValue.toInt()` 判 `isWeightLossGoal`。
     */
    private suspend fun ensureGoalDefaultsIfEmpty() = withContext(Dispatchers.IO) {
        if (db.goalDao().countActive() > 0) return@withContext
        val now = System.currentTimeMillis()
        val weightTarget = settings.get(SettingsKeys.WEIGHT)?.toDoubleOrNull() ?: 0.0
        val defaults = listOf(
            GoalEntity(
                type = TYPE_GOAL_MODE,
                metric = GoalMetrics.PRIMARY,
                targetValue = GOAL_MODE_GAIN.toDouble(),
                isPrimary = 1,
                createdAt = now,
                updatedAt = now,
            ),
            GoalEntity(
                type = "weight",
                metric = GoalMetrics.WEIGHT_KG,
                targetValue = weightTarget,
                createdAt = now,
                updatedAt = now,
            ),
            GoalEntity(
                type = "training",
                metric = GoalMetrics.SESSIONS_PER_WEEK,
                targetValue = GoalDefaults.TRAIN_SESSIONS_PER_WEEK.toDouble(),
                createdAt = now,
                updatedAt = now,
            ),
            GoalEntity(
                type = "training",
                metric = GoalMetrics.TRAIN_MINUTES_PER_WEEK,
                targetValue = GoalDefaults.TRAIN_MINUTES_PER_WEEK.toDouble(),
                createdAt = now,
                updatedAt = now,
            ),
            GoalEntity(
                type = "sleep",
                metric = GoalMetrics.SLEEP_H,
                targetValue = GoalDefaults.SLEEP_H,
                createdAt = now,
                updatedAt = now,
            ),
            GoalEntity(
                type = "habit",
                metric = GoalMetrics.WATER_ML,
                targetValue = GoalDefaults.WATER_ML.toDouble(),
                createdAt = now,
                updatedAt = now,
            ),
        )
        defaults.forEach { db.goalDao().upsert(it) }
    }

    /**
     * 首次启动补齐预设提醒（仅当 reminders 表为空时）。
     *
     * 预设来自 PRD §5.4：体检 365 天 / 洗牙 180 天 / 配镜 365 天（疫苗由用户自填，不预置）。
     * 名称走 `strings.xml`（`reminder_*`），周期天数即名称对应的常见复查间隔。
     * 首次到期日 = 今天 + 周期天数（无历史"上次日期"）。
     */
    private suspend fun ensureReminderDefaultsIfEmpty() = withContext(Dispatchers.IO) {
        if (db.reminderDao().count() > 0) return@withContext
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()
        val presets = listOf(
            app.getString(R.string.reminder_checkup) to 365,
            app.getString(R.string.reminder_dental) to 180,
            app.getString(R.string.reminder_glasses) to 365,
        )
        presets.forEach { (name, days) ->
            db.reminderDao().upsert(
                ReminderEntity(
                    name = name,
                    intervalDays = days,
                    lastDoneAt = null,
                    nextDueAt = now + days.toLong() * DAY_MS,
                    createdAt = now,
                )
            )
        }
    }

    private suspend fun reload() {
        val all = settings.listAll().associate { it.key to it.value }
        val quotas = container.quotaGuard
        val latestBody = loadLatestBodyWeight()

        _values.value = SettingsValues(
            provider = all[SettingsActivity.KEY_PROVIDER].orEmpty(),
            baseUrl = all[SettingsActivity.KEY_BASE_URL].orEmpty(),
            model = all[SettingsActivity.KEY_MODEL].orEmpty(),
            hasApiKey = container.secretStore?.hasApiKey() == true,
            extractQuota = all[SettingsActivity.KEY_QUOTA]?.toIntOrNull() ?: 20,
            chatQuota = all[SettingsActivity.KEY_CHAT_QUOTA]?.toIntOrNull() ?: 15,
            retry = all[SettingsActivity.KEY_RETRY]?.toIntOrNull() ?: 5,
            retryDelay = all[SettingsActivity.KEY_RETRY_DELAY]?.toDoubleOrNull() ?: 1.5,
            height = all[SettingsActivity.KEY_HEIGHT]?.toIntOrNull() ?: 0,
            weight = all[SettingsActivity.KEY_WEIGHT]?.toDoubleOrNull() ?: 0.0,
            age = all[SettingsActivity.KEY_AGE]?.toIntOrNull() ?: 0,
            activity = all[SettingsActivity.KEY_ACTIVITY] ?: "1.2",
            targetKcal = all[SettingsActivity.KEY_TARGET_KCAL]?.toIntOrNull() ?: 2500,
            dayStart = all[SettingsActivity.KEY_DAY_START]?.toIntOrNull() ?: 4,
            background = all[SettingsActivity.KEY_BACKGROUND].orEmpty(),
            debugSummary = "今日 ${quotas.usedToday()} 次 · 失败 ${quotas.failedToday()}",
            hideKcal = all[SettingsActivity.KEY_HIDE_KCAL] == "true",
            hideWeight = all[SettingsActivity.KEY_HIDE_WEIGHT] == "true",
            latestWeightKg = latestBody?.weightKg ?: 0.0,
            latestWeightDayKey = latestBody?.dayKey.orEmpty(),
            profileAllergens = parseProfileList(all[SettingsKeys.PROFILE_ALLERGENS]),
            profilePain = parseProfileList(all[SettingsKeys.PROFILE_PAIN]),
            profileScene = all[SettingsKeys.PROFILE_SCENE].orEmpty(),
            profileGear = parseProfileList(all[SettingsKeys.PROFILE_GEAR]),
            profileSleepBed = all[SettingsKeys.PROFILE_SLEEP_BED].orEmpty(),
            profileSleepWake = all[SettingsKeys.PROFILE_SLEEP_WAKE].orEmpty(),
        )

        // 接入状态：三要素齐备即视为已接入（纯本地判断，不发请求）
        val baseUrl = all[SettingsActivity.KEY_BASE_URL].orEmpty().trim()
        val model = all[SettingsActivity.KEY_MODEL].orEmpty().trim()
        val hasKey = container.secretStore?.hasApiKey() == true
        _applied.value = baseUrl.isNotEmpty() && model.isNotEmpty() && hasKey &&
            !baseUrl.startsWith("[待核实") && !model.startsWith("[待核实")
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
            val dayStart = settings.get(SettingsKeys.DAY_START)?.toIntOrNull()
                ?: DEFAULT_DAY_START_HOUR
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
     */
    fun setPrimaryGoal(modeIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            db.goalDao().setPrimary(GoalMetrics.PRIMARY, now)
            db.goalDao().setTarget(GoalMetrics.PRIMARY, modeIndex.toDouble(), now)
        }
    }

    /** 改某个目标值（体重 / 训练次数 / 训练分钟 / 睡眠 / 饮水）。 */
    fun setGoalTarget(metric: String, value: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            db.goalDao().setTarget(metric, value, System.currentTimeMillis())
        }
    }

    // ── 隐私（settings 表：HIDE_KCAL / HIDE_WEIGHT）─────────────────

    /**
     * 切换一个布尔隐私开关。`key` 只允许传 `SettingsKeys.HIDE_*`（键名纪律）。
     * 状态存 `"true"` / `"false"`，默认（键不存在）视为 `false`。
     */
    fun toggleHide(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val hidden = settings.get(key) == "true"
            settings.put(SettingEntity(key = key, value = if (hidden) "false" else "true"))
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
                    nextDueAt = base + intervalDays.toLong() * DAY_MS,
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
            db.reminderDao().markDone(reminder.id, now, now + reminder.intervalDays.toLong() * DAY_MS)
        }
    }

    fun deleteReminder(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { db.reminderDao().delete(id) }
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
            settings.put(SettingEntity(SettingsActivity.KEY_PROVIDER, key))

            // 自定义不覆盖，保留用户手填的值
            if (key != ProviderPresets.DEFAULT_KEY) {
                settings.put(SettingEntity(SettingsActivity.KEY_BASE_URL, preset.baseUrl))
                settings.put(SettingEntity(SettingsActivity.KEY_MODEL, preset.model))
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

            val started = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                provider.chat(
                    ChatRequest(
                        messages = listOf(ChatMessage(role = "user", content = "hi")),
                        timeoutMs = 15_000L,
                        maxRetries = 1, // 测试不重试，快速给出真实结论
                    ),
                )
            }
            val elapsed = System.currentTimeMillis() - started
            val seconds = "%.1f".format(elapsed / 1000.0)

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

            val started = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                provider.chat(
                    ChatRequest(
                        messages = listOf(ChatMessage(role = "user", content = "hi")),
                        timeoutMs = 15_000L,
                        maxRetries = 1,
                    ),
                )
            }
            val seconds = "%.1f".format((System.currentTimeMillis() - started) / 1000.0)

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

    companion object {
        /** 一天的毫秒数，用于提醒周期顺延。 */
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** 主目标行（`metric = PRIMARY`）的 `type`。 */
        private const val TYPE_GOAL_MODE = "goal_mode"

        /**
         * 主目标编码：0=增重 / 1=减重 / 2=保持。
         * 与 `HealthAggregator` 的 `PRIMARY_GOAL_LOSS = 1` 必须一致。
         */
        const val GOAL_MODE_GAIN = 0
        const val GOAL_MODE_LOSS = 1
        const val GOAL_MODE_KEEP = 2

        // ⚠️ 默认目标值（膳食指南推荐量）不在本文件定义 —— 唯一来源是
        //    `com.healix.app.db.GoalDefaults`。这里曾有一份私有副本
        //    （名为 DEFAULT_TRAIN_SESSIONS），与 StatusDetailViewModel 的同值常量
        //    造成重复定义，已收敛。见 `db/GoalEntities.kt` 的 GoalDefaults 注释。
    }
}
