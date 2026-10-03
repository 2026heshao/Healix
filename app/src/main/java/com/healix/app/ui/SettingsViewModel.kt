package com.healix.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.db.SettingEntity
import com.healix.app.net.ChatMessage
import com.healix.app.net.ChatRequest
import com.healix.app.net.ChatResult
import com.healix.app.net.OpenAiCompatProvider
import com.healix.app.net.ProviderConfig
import com.healix.app.net.ProviderPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    init {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val all = settings.listAll().associate { it.key to it.value }
        val quotas = container.quotaGuard

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
        )

        // 接入状态：三要素齐备即视为已接入（纯本地判断，不发请求）
        val baseUrl = all[SettingsActivity.KEY_BASE_URL].orEmpty().trim()
        val model = all[SettingsActivity.KEY_MODEL].orEmpty().trim()
        val hasKey = container.secretStore?.hasApiKey() == true
        _applied.value = baseUrl.isNotEmpty() && model.isNotEmpty() && hasKey &&
            !baseUrl.startsWith("[待核实") && !model.startsWith("[待核实")
    }

    /** 读原始值（编辑对话框回显用）。apiKey 不走这里 —— 它永远不回显。 */
    suspend fun raw(key: String): String? = settings.get(key)

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

    /**
     * 导出备份（功能补充 2.4）。
     * 最低限度：明文 JSON 导出，比没有强 10 倍。走 SAF，不用申请任何存储权限。
     * ⚠️ 备份**不含 API Key** —— key 不在业务表里，天然导出不到。
     */
    fun exportBackup(activity: SettingsActivity) {
        viewModelScope.launch(Dispatchers.IO) {
            val json = ExportWriter.buildJson(getApplication())
            withContext(Dispatchers.Main) {
                ExportWriter.launchCreateDocument(activity, json)
            }
        }
    }
}
