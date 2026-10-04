package com.healix.app.ui

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.ActivitySettingsBinding
import com.healix.app.databinding.RowSettingValueBinding
import com.healix.app.db.GoalMetrics
import com.healix.app.db.ReminderEntity
import com.healix.app.db.SettingsKeys
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

        binding.btnApply.setOnClickListener { runApply() }
        binding.btnTest.setOnClickListener { runConnectivityTest() }

        // ── 调用限制 ──────────────────────────────────────────────
        // 配额口径改造（2026-10-05）：每日上限改为护栏内置常量（QuotaGuard.DEFAULT_*），
        // 不再提供设置项；这两行只读展示今日实际调用量，值在 observe() 回填。
        // 日界线（原「个人」组）上移到本组顶部：它决定配额日窗口与会话切日，
        // 是系统参数而非个人信息（P1 迁移决策 2）。
        setupRow(binding.rowDayStart, R.string.setting_day_start) {
            editInt(KEY_DAY_START, R.string.setting_day_start, 0..12)
        }
        binding.rowExtractQuota.label.setText(R.string.setting_today_extract)
        binding.rowChatQuota.label.setText(R.string.setting_today_chat)
        setupRow(binding.rowRetry, R.string.setting_retry) { editInt(KEY_RETRY, R.string.setting_retry) }
        setupRow(binding.rowRetryDelay, R.string.setting_retry_delay) { editDecimal(KEY_RETRY_DELAY, R.string.setting_retry_delay) }

        // ── 目标（goals 表）──────────────────────────────────────
        setupRow(binding.rowGoalPrimary, R.string.setting_primary_goal) { choosePrimaryGoal() }
        setupRow(binding.rowGoalWeight, R.string.setting_weight_goal) { editGoalWeight() }
        setupRow(binding.rowGoalTrain, R.string.setting_train_goal) { editGoalTrain() }
        setupRow(binding.rowGoalSleep, R.string.setting_sleep_goal) { editGoalSleep() }
        setupRow(binding.rowGoalWater, R.string.setting_water_goal) { editGoalWater() }

        // 目标组「依据提示」：仅首次打开该组时显示一次（规范 9.7）
        setupGoalSourceHint()

        // ── 提醒（reminders 表）──────────────────────────────────
        setupRow(binding.rowReminderAdd, R.string.reminder_add) { editReminder(null) }

        // ── 隐私（settings：HIDE_KCAL / HIDE_WEIGHT）──────────────
        setupRow(binding.rowHideKcal, R.string.setting_hide_kcal) { vm.toggleHide(KEY_HIDE_KCAL) }
        setupRow(binding.rowHideWeight, R.string.setting_hide_weight) { vm.toggleHide(KEY_HIDE_WEIGHT) }

        // v6（11.1）：「数据 / 调试 / 知识库」三组迁「我的」页，设置页回归纯配置。

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

    /** v6 11.2：二级页返回走 in_back 转场（覆盖返回键与手势返回）。 */
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.in_back, R.anim.out_back)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.values.collect { v ->
                    binding.rowProvider.value.text = providerLabel(v.provider)
                    binding.rowBaseUrl.value.text = v.baseUrl.ifBlank { "—" }
                    binding.rowModel.value.text = v.model.ifBlank { "—" }
                    binding.rowApiKey.value.text = if (v.hasApiKey) MASK else getString(R.string.value_not_set)
                    binding.rowExtractQuota.value.text = getString(R.string.unit_times, v.usedExtractToday)
                    binding.rowChatQuota.value.text = getString(R.string.unit_times, v.usedChatToday)
                    binding.rowRetry.value.text = getString(R.string.unit_times, v.retry)
                    binding.rowRetryDelay.value.text = getString(R.string.unit_seconds, trim(v.retryDelay))
                    binding.rowDayStart.value.text = getString(R.string.unit_hour_clock, v.dayStart)

                    // 隐私：右侧值文字即状态，点击切换（不引入 Switch，规范 9.7 ④）
                    binding.rowHideKcal.value.text =
                        getString(if (v.hideKcal) R.string.value_hidden else R.string.value_shown)
                    binding.rowHideWeight.value.text =
                        getString(if (v.hideWeight) R.string.value_hidden else R.string.value_shown)
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
        // 接入结果：与测试结果共用同一个展示位（避免两条状态文本打架）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.applyResult.collect { r ->
                    if (r == null) return@collect
                    binding.testResult.visibility = View.VISIBLE
                    binding.testResult.text = r.text
                    binding.testResult.setTextColor(
                        androidx.core.content.ContextCompat.getColor(
                            this@SettingsActivity,
                            if (r.ok) R.color.positive else R.color.negative,
                        )
                    )
                }
            }
        }
        // 接入状态条：常驻显示「已接入 / 未接入」，不靠弹窗
        // 同时订阅 values（取服务商/模型名）与 applied（取接入与否），
        // 保证两者永远同帧一致 —— 分开读 .value 会出现"名字已更新但状态没更新"的撕裂。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(vm.values, vm.applied) { v, on -> v to on }
                    .collect { (v, on) ->
                        binding.applyStatus.text = if (on) {
                            getString(
                                R.string.apply_status_on,
                                providerLabel(v.provider),
                                v.model,
                            )
                        } else {
                            getString(R.string.apply_status_off)
                        }
                    }
            }
        }
        // 目标：goals 表一次订阅，逐行格式化（规范 9.7 ①）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.goals.collect { list -> renderGoals(list) }
            }
        }
        // 提醒：reminders 表动态渲染（可增删，规范 9.7 ③）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.reminders.collect { list -> renderReminders(list) }
            }
        }
    }

    // ── 目标渲染与编辑 ────────────────────────────────────────────

    /**
     * 目标组「依据提示」：`来自膳食指南推荐量`，**仅在用户首次打开该组时显示一次**。
     *
     * 形式裁决：放**组标题下方一行**，而不是规范字面的「每项值下方」——
     * 体重/训练/睡眠/饮水的默认值同出一个来源（《中国居民膳食指南(2022)》），
     * 逐项重复 5 遍只是噪声。
     * 已读标记落 `settings`（`SettingsKeys.GOAL_SOURCE_SEEN`），跨启动只显示一次；
     * 先把提示设为可见、再落标记，保证用户至少真的看到过一眼。
     */
    private fun setupGoalSourceHint() {
        lifecycleScope.launch {
            if (vm.raw(SettingsKeys.GOAL_SOURCE_SEEN) == "true") return@launch
            binding.goalSourceHint.setText(R.string.setting_goal_source_dietary)
            binding.goalSourceHint.visibility = View.VISIBLE
            vm.put(SettingsKeys.GOAL_SOURCE_SEEN, "true")
        }
    }

    private fun renderGoals(list: List<com.healix.app.db.GoalEntity>) {
        val byMetric = list.associateBy { it.metric }

        val mode = byMetric[GoalMetrics.PRIMARY]?.targetValue?.toInt() ?: SettingsViewModel.GOAL_MODE_GAIN
        binding.rowGoalPrimary.value.text = when (mode) {
            SettingsViewModel.GOAL_MODE_LOSS -> getString(R.string.goal_loss)
            SettingsViewModel.GOAL_MODE_KEEP -> getString(R.string.goal_keep)
            else -> getString(R.string.goal_gain)
        }

        val weight = byMetric[GoalMetrics.WEIGHT_KG]?.targetValue ?: 0.0
        binding.rowGoalWeight.value.text =
            if (weight > 0) getString(R.string.unit_kg, trim(weight)) else getString(R.string.value_not_set)

        val sessions = byMetric[GoalMetrics.SESSIONS_PER_WEEK]?.targetValue?.toInt() ?: 0
        val minutes = byMetric[GoalMetrics.TRAIN_MINUTES_PER_WEEK]?.targetValue?.toInt() ?: 0
        binding.rowGoalTrain.value.text = getString(R.string.unit_train_goal, sessions, minutes)

        val sleepH = byMetric[GoalMetrics.SLEEP_H]?.targetValue ?: 0.0
        binding.rowGoalSleep.value.text = getString(R.string.unit_hours, trim(sleepH))

        val water = byMetric[GoalMetrics.WATER_ML]?.targetValue?.toInt() ?: 0
        binding.rowGoalWater.value.text = getString(R.string.unit_ml, water)
    }

    /** 当前目标值（编辑弹窗回显用）。 */
    private fun currentTarget(metric: String, fallback: Double): Double =
        vm.goals.value.firstOrNull { it.metric == metric }?.targetValue ?: fallback

    private fun choosePrimaryGoal() {
        val labels = arrayOf(
            getString(R.string.goal_gain),
            getString(R.string.goal_loss),
            getString(R.string.goal_keep),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_primary_goal)
            .setItems(labels) { _, which -> vm.setPrimaryGoal(which) }
            .setNegativeButton(R.string.cancel, null as DialogInterface.OnClickListener?)
            .show()
    }

    private fun editGoalWeight() {
        val initial = trimOrEmpty(currentTarget(GoalMetrics.WEIGHT_KG, 0.0))
        showFieldDialog(
            R.string.setting_weight_goal,
            listOf(FieldSheet.FieldSpec(R.string.setting_weight, initial, NUMBER_DECIMAL)),
        ) { raw ->
            raw.firstOrNull()?.toDoubleOrNull()?.let { vm.setGoalTarget(GoalMetrics.WEIGHT_KG, it) }
        }
    }

    private fun editGoalTrain() {
        val sessions = trimOrEmpty(currentTarget(GoalMetrics.SESSIONS_PER_WEEK, 0.0))
        val minutes = trimOrEmpty(currentTarget(GoalMetrics.TRAIN_MINUTES_PER_WEEK, 0.0))
        showFieldDialog(
            R.string.setting_train_goal,
            listOf(
                FieldSheet.FieldSpec(R.string.goal_train_sessions_label, sessions, NUMBER_INT),
                FieldSheet.FieldSpec(R.string.goal_train_minutes_label, minutes, NUMBER_INT),
            ),
        ) { raw ->
            val s = raw.getOrNull(0)?.toIntOrNull()
            val m = raw.getOrNull(1)?.toIntOrNull()
            if (s != null && s > 0) vm.setGoalTarget(GoalMetrics.SESSIONS_PER_WEEK, s.toDouble())
            if (m != null && m > 0) vm.setGoalTarget(GoalMetrics.TRAIN_MINUTES_PER_WEEK, m.toDouble())
        }
    }

    private fun editGoalSleep() {
        val initial = trimOrEmpty(currentTarget(GoalMetrics.SLEEP_H, 0.0))
        showFieldDialog(
            R.string.setting_sleep_goal,
            listOf(FieldSheet.FieldSpec(R.string.unit_hour_plain, initial, NUMBER_DECIMAL)),
        ) { raw ->
            raw.firstOrNull()?.toDoubleOrNull()?.let { vm.setGoalTarget(GoalMetrics.SLEEP_H, it) }
        }
    }

    private fun editGoalWater() {
        val initial = trimOrEmpty(currentTarget(GoalMetrics.WATER_ML, 0.0))
        showFieldDialog(
            R.string.setting_water_goal,
            listOf(FieldSheet.FieldSpec(R.string.setting_water_goal, initial, NUMBER_INT)),
        ) { raw ->
            raw.firstOrNull()?.toIntOrNull()?.let { vm.setGoalTarget(GoalMetrics.WATER_ML, it.toDouble()) }
        }
    }

    // ── 提醒渲染与编辑 ────────────────────────────────────────────

    private fun renderReminders(list: List<ReminderEntity>) {
        val container = binding.reminderContainer
        container.removeAllViews()
        val now = System.currentTimeMillis()
        list.forEach { reminder ->
            val row = RowSettingValueBinding.inflate(layoutInflater, container, false)
            row.label.text = reminder.name
            row.chevron.visibility = View.VISIBLE
            row.value.text = getString(R.string.status_reminder_next, dateLabel(reminder.nextDueAt))
            // 到期或临期（≤7 天）：右侧日期用 accent（规范 9.7 ③）
            val due = reminder.nextDueAt - now <= 7L * DAY_MS
            row.value.setTextColor(ContextCompat.getColor(this, if (due) R.color.accent else R.color.text_2))
            row.root.setOnClickListener { reminderActions(reminder) }
            container.addView(row.root)
        }
    }

    /** 点一条提醒：标记完成（顺延）/ 编辑 / 删除。 */
    private fun reminderActions(reminder: ReminderEntity) {
        // ⚠️ setItems 只接受 Array<CharSequence>，不接受 List<String>（见 showDialog 注释）。
        // 「标记已完成」放首位：最常用动作在首项。
        val items = arrayOf<CharSequence>(
            getString(R.string.reminder_done),
            getString(R.string.edit),
            getString(R.string.delete),
        )
        AlertDialog.Builder(this)
            .setTitle(reminder.name)
            .setMessage(getString(R.string.status_reminder_next, dateLabel(reminder.nextDueAt)))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> vm.markReminderDone(reminder)
                    1 -> editReminder(reminder)
                    else -> vm.deleteReminder(reminder.id)
                }
            }
            .setNegativeButton(R.string.cancel, null as DialogInterface.OnClickListener?)
            .show()
    }

    /** 新增（existing == null）或编辑一条提醒：复用 ConfirmSheet 三行字段。 */
    private fun editReminder(existing: ReminderEntity?) {
        val specs = listOf(
            FieldSheet.FieldSpec(R.string.reminder_field_name, existing?.name.orEmpty(), InputType.TYPE_CLASS_TEXT),
            FieldSheet.FieldSpec(
                R.string.reminder_field_days,
                existing?.intervalDays?.toString().orEmpty(),
                NUMBER_INT,
            ),
            FieldSheet.FieldSpec(
                R.string.reminder_field_last,
                existing?.lastDoneAt?.let { dateLabel(it) }.orEmpty(),
                InputType.TYPE_CLASS_TEXT,
            ),
        )
        showFieldDialog(
            if (existing == null) R.string.reminder_add else R.string.edit,
            specs,
        ) { raw ->
            val name = raw.getOrNull(0).orEmpty()
            val days = raw.getOrNull(1)?.toIntOrNull() ?: 0
            val last = parseDate(raw.getOrNull(2).orEmpty())
            vm.saveReminder(existing, name, days, last)
        }
    }

    /**
     * 字段编辑弹窗（§5.1 弹窗统一）：委托 [FieldSheet] 底色容器载体，
     * 与个人信息页 / 预设管理页共用同一组件（不再用系统 AlertDialog，
     * 落实设计规范「无底色容器原则」）。
     *
     * B1：字段规格**统一用** [FieldSheet.FieldSpec]**（直接嵌套类，4 参含 maxLength，
     * 默认 0）**，删除了本文件原有的私有同名近重复类型与那次 `map` 转换；直接透传。
     * [onOk] 收到的字段顺序与 specs 一致。
     */
    private fun showFieldDialog(
        titleRes: Int,
        specs: List<FieldSheet.FieldSpec>,
        onOk: (List<String>) -> Unit,
    ) {
        val sheet = FieldSheet.newInstance(titleRes, specs)
        sheet.onResult = onOk
        sheet.show(supportFragmentManager, FieldSheet.TAG)
    }

    /** 毫秒时间戳 → `yyyy-MM-dd`（本地时区）。 */
    private fun dateLabel(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    /** `yyyy-MM-dd` → 当天 0 点的毫秒时间戳；空或解析失败返回 null（不猜）。 */
    private fun parseDate(s: String): Long? {
        if (s.isBlank()) return null
        return runCatching {
            LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun trimOrEmpty(v: Double): String =
        if (v <= 0.0) "" else trim(v)

    // ── 接入并启用（本节第一主按钮） ────────────────────────────────

    /**
     * 「接入并启用」：把用户填下的配置真正落到"可用"状态，并给出显式反馈。
     *
     * 与 [runConnectivityTest] 的差异只有一点，但对用户很关键：
     * 接入是**声明式的**（"我确认要用这套配置"），测试是**探测式的**（"看看通不通"）。
     * 两者共用一个结果展示位（testResult），避免设置页出现两条状态文本互相打架。
     */
    private fun runApply() {
        binding.btnApply.isEnabled = false
        binding.btnTest.isEnabled = false
        binding.testResult.visibility = View.VISIBLE
        binding.testResult.setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.text_2)
        )
        binding.testResult.text = getString(R.string.apply_applying)

        vm.applyProvider {
            binding.btnApply.isEnabled = true
            binding.btnTest.isEnabled = true
        }
    }

    // ── 测试连通性 ────────────────────────────────────────────────

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

    /**
     * 整数输入。`range != null` 时做范围校验：**越界或非数字则拒绝写入并提示**
     * （不静默夹取 —— 静默夹取会让用户以为填的值已生效，产生"设置没生效"的困惑）。
     *
     * ⚠️ `range` 默认 `null`（不校验），保持向后兼容 —— `rowRetry`（KEY_RETRY）等
     * 既有调用点行为不变。日界线（`day_start`）传 `0..12`，从**输入端**堵住越界，
     * 与读取端唯一夹取入口 `dayStartHourOf` 形成对称契约。
     */
    private fun editInt(key: String, labelRes: Int, range: IntRange? = null) {
        lifecycleScope.launch {
            val current = vm.raw(key).orEmpty()
            val input = EditText(this@SettingsActivity).apply {
                setText(current)
                inputType = InputType.TYPE_CLASS_NUMBER
                setSelection(text.length)
            }
            showDialog(labelRes, input) {
                val text = input.text.toString().trim()
                if (range != null) {
                    val value = text.toIntOrNull()
                    if (value == null || value !in range) {
                        showRangeRejected(labelRes, range)
                        return@showDialog
                    }
                }
                vm.put(key, text)
            }
        }
    }

    /** 数值输入越界时的拒绝提示（配合 editInt 的 range 校验）。 */
    private fun showRangeRejected(labelRes: Int, range: IntRange) {
        AlertDialog.Builder(this)
            .setTitle(labelRes)
            .setMessage(getString(R.string.setting_value_out_of_range, range.first, range.last))
            .setPositiveButton(R.string.confirm, null as DialogInterface.OnClickListener?)
            .show()
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

    private fun providerLabel(id: String): String = when (id) {
        "zhipu" -> "智谱 GLM"
        "deepseek" -> "DeepSeek"
        "openrouter" -> "OpenRouter"
        "siliconflow" -> "SiliconFlow"
        else -> getString(R.string.setting_custom)
    }

    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    companion object {
        const val MASK = "••••••••"

        // ⚠️ 键名一律从 SettingsKeys 取 —— 那里是唯一事实来源。
        //    2026-10-03 修过一次键名分裂 bug（见 SettingsKeys 头注释），
        //    此处保留 `KEY_*` 前缀是为了不改动几十个调用点，
        //    但值的来源必须收敛到 SettingsKeys。
        const val KEY_BASE_URL = SettingsKeys.BASE_URL
        const val KEY_MODEL = SettingsKeys.MODEL
        const val KEY_PROVIDER = SettingsKeys.PROVIDER
        const val KEY_RETRY = SettingsKeys.RETRY
        const val KEY_RETRY_DELAY = SettingsKeys.RETRY_DELAY
        const val KEY_HEIGHT = SettingsKeys.HEIGHT
        const val KEY_WEIGHT = SettingsKeys.WEIGHT
        const val KEY_AGE = SettingsKeys.AGE
        const val KEY_ACTIVITY = SettingsKeys.ACTIVITY
        const val KEY_TARGET_KCAL = SettingsKeys.TARGET_KCAL
        const val KEY_DAY_START = SettingsKeys.DAY_START

        /** 用户背景（自由文本）。空 = 未填写，AI prompt 走无背景的原路径。 */
        const val KEY_BACKGROUND = SettingsKeys.BACKGROUND

        // 隐私开关（SettingsKeys 是唯一事实来源；这里只做转发引用）
        const val KEY_HIDE_KCAL = SettingsKeys.HIDE_KCAL
        const val KEY_HIDE_WEIGHT = SettingsKeys.HIDE_WEIGHT

        /** 一天的毫秒数，用于提醒临期（≤7 天）判定。 */
        private const val DAY_MS = 24L * 60 * 60 * 1000

        private const val NUMBER_INT = InputType.TYPE_CLASS_NUMBER
        private const val NUMBER_DECIMAL =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
}
