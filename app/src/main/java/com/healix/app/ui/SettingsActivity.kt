package com.healix.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.widget.doAfterTextChanged
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

/**
 * 设置页（设计规范系统 4.4）。
 *
 * 关键约束：
 * - API Key 默认掩码显示，点开才可编辑（禁止明文常驻屏幕）
 * - 「测试连通性」结果就地显示不弹 Toast（结果需要能被反复查看）
 * - apiKey 存 EncryptedSharedPreferences，**settings 表里没有它**
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var vm: SettingsViewModel

    /** 背景项：最近一次已落库的内容，用于避免重复写入。 */
    private var lastSavedBackground: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = SettingsViewModel(HealixApp.from(this))

        binding.btnBack.setOnClickListener { finish() }

        // ── 模型服务 ──────────────────────────────────────────────
        setupRow(binding.rowProvider, R.string.provider) { chooseProvider() }
        setupRow(binding.rowBaseUrl, R.string.base_url) { editText(KEY_BASE_URL, R.string.base_url) }
        setupRow(binding.rowModel, R.string.model_name) { editText(KEY_MODEL, R.string.model_name) }
        setupRow(binding.rowApiKey, R.string.api_key) { editApiKey() }

        binding.btnTest.setOnClickListener { runConnectivityTest() }

        // ── 调用限制 ──────────────────────────────────────────────
        setupRow(binding.rowExtractQuota, R.string.setting_daily_quota) { editInt(KEY_QUOTA, R.string.setting_daily_quota) }
        setupRow(binding.rowChatQuota, R.string.setting_chat_quota) { editInt(KEY_CHAT_QUOTA, R.string.setting_chat_quota) }
        setupRow(binding.rowRetry, R.string.setting_retry) { editInt(KEY_RETRY, R.string.setting_retry) }
        setupRow(binding.rowRetryDelay, R.string.setting_retry_delay) { editDecimal(KEY_RETRY_DELAY, R.string.setting_retry_delay) }

        // ── 个人 ─────────────────────────────────────────────────
        setupRow(binding.rowHeight, R.string.setting_height) { editInt(KEY_HEIGHT, R.string.setting_height) }
        setupRow(binding.rowWeight, R.string.setting_weight) { editDecimal(KEY_WEIGHT, R.string.setting_weight) }
        setupRow(binding.rowAge, R.string.setting_age) { editInt(KEY_AGE, R.string.setting_age) }
        setupRow(binding.rowActivity, R.string.setting_activity) { chooseActivity() }
        setupRow(binding.rowTargetKcal, R.string.setting_target_kcal) { editInt(KEY_TARGET_KCAL, R.string.setting_target_kcal) }
        setupRow(binding.rowDayStart, R.string.setting_day_start) { editInt(KEY_DAY_START, R.string.setting_day_start) }

        setupBackground()

        // ── 数据 ─────────────────────────────────────────────────
        setupRow(binding.rowExport, R.string.export_backup) { vm.exportBackup(this) }

        // ── 调试 ─────────────────────────────────────────────────
        setupRow(binding.rowDebugSummary, R.string.group_debug) {
            startActivity(Intent(this, DebugActivity::class.java))
        }

        observe()
    }

    /** 给 include 出来的行设标签与点击。箭头只在可点行显示。 */
    private fun setupRow(
        row: com.healix.app.databinding.RowSettingValueBinding,
        labelRes: Int,
        onClick: () -> Unit,
    ) {
        row.label.setText(labelRes)
        row.chevron.visibility = View.VISIBLE
        row.root.setOnClickListener { onClick() }
    }

    /**
     * 背景项（自由文本，多行）。
     *
     * 与其它设置项的区别：它是一个**常驻输入框**而不是弹窗 ——
     * 背景往往是几行零散信息，弹窗里写长文本体验差（且弹窗会被软键盘顶变形）。
     *
     * 保存时机：**失焦**（onFocusChange）+ **返回键退出前**（onPause 兜底）。
     * 不做 TextWatcher 实时写库 —— 每次按键写一次 SQLite 是无谓的 IO。
     *
     * 上限 [BACKGROUND_MAX] 字：超过就硬截断。
     * 截断必须在**输入时**做，不能只在保存时做 —— 否则用户看到 2500 字打进去、
     * 存下来只剩 2000，属于静默丢数据（违反"不丢用户数据"的项目约束）。
     */
    private fun setupBackground() {
        val edit = binding.editBackground

        // 输入时即截断 + 实时更新字数（CharSequence 长度按 UTF-16，中文 1 字 = 1）
        edit.filters = arrayOf(
            android.text.InputFilter.LengthFilter(BACKGROUND_MAX)
        )
        edit.doAfterTextChanged { text ->
            binding.backgroundCount.text = getString(
                R.string.setting_background_count,
                text?.length ?: 0,
                BACKGROUND_MAX,
            )
        }
        // 首帧先把计数显示为已有内容的长度（否则显示 0/N 与实际不符）
        binding.backgroundCount.text = getString(
            R.string.setting_background_count, edit.text.length, BACKGROUND_MAX,
        )

        // 失焦保存
        edit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveBackground()
        }
    }

    /** 把输入框内容落库。内容没变则不写（避免无谓 IO 与 StateFlow 抖动）。 */
    private fun saveBackground() {
        val text = binding.editBackground.text.toString().trim()
        if (text == lastSavedBackground) return
        lastSavedBackground = text
        vm.put(KEY_BACKGROUND, text)
    }

    override fun onPause() {
        // 兜底：用户直接按返回 / 切后台时不走失焦回调，这里补一次
        super.onPause()
        if (::binding.isInitialized) saveBackground()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.values.collect { v ->
                    binding.rowProvider.value.text = providerLabel(v.provider)
                    binding.rowBaseUrl.value.text = v.baseUrl.ifBlank { "—" }
                    binding.rowModel.value.text = v.model.ifBlank { "—" }
                    binding.rowApiKey.value.text = if (v.hasApiKey) MASK else getString(R.string.no_provider_config).let { "未设置" }
                    binding.rowExtractQuota.value.text = getString(R.string.unit_times, v.extractQuota)
                    binding.rowChatQuota.value.text = getString(R.string.unit_times, v.chatQuota)
                    binding.rowRetry.value.text = getString(R.string.unit_times, v.retry)
                    binding.rowRetryDelay.value.text = getString(R.string.unit_seconds, trim(v.retryDelay))
                    binding.rowHeight.value.text = if (v.height > 0) getString(R.string.unit_cm, v.height) else "—"
                    binding.rowWeight.value.text = if (v.weight > 0) getString(R.string.unit_kg, trim(v.weight)) else "—"
                    binding.rowAge.value.text = if (v.age > 0) getString(R.string.unit_years, v.age) else "—"
                    binding.rowActivity.value.text = activityLabel(v.activity)
                    binding.rowTargetKcal.value.text = getString(R.string.plan_item_kcal, "", v.targetKcal).trimStart(' ', '·')
                    binding.rowDayStart.value.text = getString(R.string.unit_hour_clock, v.dayStart)
                    binding.rowDebugSummary.value.text = v.debugSummary

                    // 背景：只在「用户没在编辑」时回填。
                    // 否则 vm.reload() 触发的回填会把用户正在敲的字覆写掉。
                    if (!binding.editBackground.hasFocus() &&
                        binding.editBackground.text.toString() != v.background
                    ) {
                        binding.editBackground.setText(v.background)
                        binding.editBackground.setSelection(v.background.length)
                        lastSavedBackground = v.background
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.testResult.collect { r ->
                    if (r == null) {
                        binding.testResult.visibility = View.GONE
                    } else {
                        binding.testResult.visibility = View.VISIBLE
                        binding.testResult.text = r.text
                        // 成功用 positive，失败用 negative —— 只出现在文字上
                        binding.testResult.setTextColor(
                            androidx.core.content.ContextCompat.getColor(
                                this@SettingsActivity,
                                if (r.ok) R.color.positive else R.color.negative,
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * SAF 回传：用户选完导出文件后由系统调用。
     *
     * 这里**必须**显式转发给 ExportWriter，否则用户选完路径后什么都不会发生
     * （pendingPayload 会一直挂在内存里，直到下次导出被覆盖）。
     */
    @Deprecated("startActivityForResult 仍可用，但系统推荐 ActivityResultLauncher；此处为最小改动保留")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (ExportWriter.onActivityResult(this, requestCode, resultCode, data?.data)) {
            val ok = resultCode == RESULT_OK
            android.widget.Toast.makeText(
                this,
                getString(if (ok) R.string.export_success else R.string.export_failed),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // ── 测试连通性（设置页唯一主按钮） ─────────────────────────────

    private fun runConnectivityTest() {
        binding.btnTest.isEnabled = false
        binding.testResult.visibility = View.VISIBLE
        binding.testResult.setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.text_2)
        )
        binding.testResult.text = getString(R.string.setting_testing)

        vm.testConnectivity {
            binding.btnTest.isEnabled = true
        }
    }

    // ── 编辑对话框（就地修改，不新开页面） ────────────────────────
    //
    // ⚠️ vm.raw(key) 是 suspend（要读 Room）。不能在 EditText.apply { } 里
    //    直接调用 —— 那是普通 lambda，不是协程。必须在 lifecycleScope 里
    //    先 await 拿到值，再构造对话框。下面三个函数统一按这个模式写。

    private fun editText(key: String, labelRes: Int) {
        lifecycleScope.launch {
            val current = vm.raw(key).orEmpty()
            val input = EditText(this@SettingsActivity).apply {
                setText(current)
                inputType = InputType.TYPE_CLASS_TEXT
                setSelection(text.length)
            }
            showDialog(labelRes, input) { vm.put(key, input.text.toString().trim()) }
        }
    }

    private fun editInt(key: String, labelRes: Int) {
        lifecycleScope.launch {
            val current = vm.raw(key).orEmpty()
            val input = EditText(this@SettingsActivity).apply {
                setText(current)
                inputType = InputType.TYPE_CLASS_NUMBER
                setSelection(text.length)
            }
            showDialog(labelRes, input) { vm.put(key, input.text.toString().trim()) }
        }
    }

    private fun editDecimal(key: String, labelRes: Int) {
        lifecycleScope.launch {
            val current = vm.raw(key).orEmpty()
            val input = EditText(this@SettingsActivity).apply {
                setText(current)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setSelection(text.length)
            }
            showDialog(labelRes, input) { vm.put(key, input.text.toString().trim()) }
        }
    }

    /** API Key 编辑：输入框掩码，不回显已存的 key（无法回显 —— 且刻意不提供读取原值的能力）。 */
    private fun editApiKey() {
        val input = EditText(this).apply {
            hint = "粘贴你的 API Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        showDialog(R.string.api_key, input) {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) vm.saveApiKey(text)
        }
    }

    private fun showDialog(labelRes: Int, input: EditText, onOk: () -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(labelRes)
            .setView(container)
            // ⚠️ AlertDialog.Builder 没有「只传文案」的单参 setPositiveButton。
            //    必须显式给 OnClickListener；不关心点击时传 null 会被 Kotlin
            //    判为「无法推断用哪个重载」，要写成带类型的 lambda。
            .setPositiveButton(R.string.confirm) { _: android.content.DialogInterface, _: Int ->
                onOk()
            }
            .setNegativeButton(R.string.cancel, null as android.content.DialogInterface.OnClickListener?)
            .show()
    }

    private fun chooseProvider() {
        // ⚠️ setItems 只接受 Array<CharSequence>，不接受 List<String>。
        //    vm.providerNames() 返回 List<String>，直接传会报
        //    「None of the following candidates is applicable」，
        //    并连锁导致后续 setNegativeButton 也解析失败（返回类型未定）。
        val presets = vm.providerNames().toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.provider)
            .setItems(presets) { _, which -> vm.selectProvider(which) }
            .setNegativeButton(R.string.cancel, null as android.content.DialogInterface.OnClickListener?)
            .show()
    }

    private fun chooseActivity() {
        val labels = resources.getStringArray(R.array.activity_levels)
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_activity)
            .setItems(labels) { _, which -> vm.put(KEY_ACTIVITY, ACTIVITY_VALUES[which]) }
            .setNegativeButton(R.string.cancel, null as android.content.DialogInterface.OnClickListener?)
            .show()
    }

    private fun providerLabel(id: String): String = when (id) {
        "zhipu" -> "智谱 GLM"
        "deepseek" -> "DeepSeek"
        "openrouter" -> "OpenRouter"
        "siliconflow" -> "SiliconFlow"
        else -> getString(R.string.setting_custom)
    }

    private fun activityLabel(value: String): String {
        val labels = resources.getStringArray(R.array.activity_levels)
        val idx = ACTIVITY_VALUES.indexOf(value)
        return labels.getOrElse(idx) { labels[0] }
    }

    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    companion object {
        const val MASK = "••••••••"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_PROVIDER = "provider"
        const val KEY_QUOTA = "daily_quota"
        const val KEY_CHAT_QUOTA = "chat_quota"
        const val KEY_RETRY = "retry_max"
        const val KEY_RETRY_DELAY = "retry_base_seconds"
        const val KEY_HEIGHT = "height_cm"
        const val KEY_WEIGHT = "weight_kg"
        const val KEY_AGE = "age"
        const val KEY_ACTIVITY = "activity_factor"
        const val KEY_TARGET_KCAL = "target_kcal"
        const val KEY_DAY_START = "day_start_hour"

        /** 用户背景（自由文本）。空 = 未填写，AI prompt 走无背景的原路径。 */
        const val KEY_BACKGROUND = "user_background"

        /**
         * 背景字数上限。
         * 2000 字中文约 2000-2600 token，对免费档是可控的开销；
         * 再长会挤占今日摘要与历史窗口的预算。
         */
        const val BACKGROUND_MAX = 2000

        /** 活动系数（总方案第五节 BMR 公式）。 */
        val ACTIVITY_VALUES = listOf("1.2", "1.375", "1.55", "1.725")
    }
}
