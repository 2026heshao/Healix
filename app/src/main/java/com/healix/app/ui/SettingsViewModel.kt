package com.healix.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healix.app.HealixApp
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

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = HealixApp.from(app)
    private val db = container.database
    private val settings = db.settingsDao()

    private val _values = MutableStateFlow(SettingsValues())
    val values: StateFlow<SettingsValues> = _values.asStateFlow()

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

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
