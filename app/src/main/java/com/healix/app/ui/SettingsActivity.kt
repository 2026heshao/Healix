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
import androidx.core.widget.doAfterTextChanged
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.ActivitySettingsBinding
import com.healix.app.databinding.RowSettingValueBinding
import com.healix.app.databinding.RowSheetFieldBinding
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

        binding.btnApply.setOnClickListener { runApply() }
        binding.btnTest.setOnClickListener { runConnectivityTest() }

        // ── 调用限制 ──────────────────────────────────────────────
        setupRow(binding.rowExtractQuota, R.string.setting_daily_quota) { editInt(KEY_QUOTA, R.string.setting_daily_quota) }
        setupRow(binding.rowChatQuota, R.string.setting_chat_quota) { editInt(KEY_CHAT_QUOTA, R.string.setting_chat_quota) }
        setupRow(binding.rowRetry, R.string.setting_retry) { editInt(KEY_RETRY, R.string.setting_retry) }
        setupRow(binding.rowRetryDelay, R.string.setting_retry_delay) { editDecimal(KEY_RETRY_DELAY, R.string.setting_retry_delay) }

        // ── 个人 ─────────────────────────────────────────────────
        // 当前体重（F5 回归修正）：入口与存储分离 —— 点击弹数值输入，
        // 确认后写一条 events(type=body)（与「记一笔」同一条数据管道），
        // 不在 settings 存静态体重字段；体重目标仍在「目标」组。
        setupRow(binding.rowCurrentWeight, R.string.setting_current_weight) { editCurrentWeight() }
        setupRow(binding.rowHeight, R.string.setting_height) { editInt(KEY_HEIGHT, R.string.setting_height) }
        setupRow(binding.rowAge, R.string.setting_age) { editInt(KEY_AGE, R.string.setting_age) }
        setupRow(binding.rowActivity, R.string.setting_activity) { chooseActivity() }
        setupRow(binding.rowTargetKcal, R.string.setting_target_kcal) { editInt(KEY_TARGET_KCAL, R.string.setting_target_kcal) }
        setupRow(binding.rowDayStart, R.string.setting_day_start) { editInt(KEY_DAY_START, R.string.setting_day_start) }

        // ── 我的情况（结构化画像，F6）────────────────────────────
        // 硬约束（忌口/疼痛/器材）结构化，软背景（场景/作息/补充说明）轻量填写。
        // 标签编辑复用 ConfirmSheet 同款字段弹窗，顿号/逗号分隔输入。
        setupRow(binding.rowProfileAllergens, R.string.setting_profile_allergens) {
            editProfileTags(SettingsKeys.PROFILE_ALLERGENS, R.string.setting_profile_allergens)
        }
        setupRow(binding.rowProfilePain, R.string.setting_profile_pain) {
            editProfileTags(SettingsKeys.PROFILE_PAIN, R.string.setting_profile_pain)
        }
        setupRow(binding.rowProfileScene, R.string.setting_profile_scene) { chooseProfileScene() }
        setupRow(binding.rowProfileGear, R.string.setting_profile_gear) {
            editProfileTags(SettingsKeys.PROFILE_GEAR, R.string.setting_profile_gear)
        }
        setupRow(binding.rowProfileSleep, R.string.setting_profile_sleep) { editProfileSleep() }

        setupBackground()

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
                    binding.rowApiKey.value.text = if (v.hasApiKey) MASK else getString(R.string.no_provider_config).let { "未设置" }
                    binding.rowExtractQuota.value.text = getString(R.string.unit_times, v.extractQuota)
                    binding.rowChatQuota.value.text = getString(R.string.unit_times, v.chatQuota)
                    binding.rowRetry.value.text = getString(R.string.unit_times, v.retry)
                    binding.rowRetryDelay.value.text = getString(R.string.unit_seconds, trim(v.retryDelay))
                    binding.rowHeight.value.text = if (v.height > 0) getString(R.string.unit_cm, v.height) else "—"
                    binding.rowAge.value.text = if (v.age > 0) getString(R.string.unit_years, v.age) else "—"
                    // 当前体重行（F5）：有记录显示「值 · 相对时间」；从未记录显示灰色「未记录」
                    if (v.latestWeightKg > 0) {
                        binding.rowCurrentWeight.value.text = getString(
                            R.string.setting_current_weight_value,
                            trim(v.latestWeightKg),
                            agoLabel(v.latestWeightDayKey),
                        )
                        binding.rowCurrentWeight.value.setTextColor(
                            ContextCompat.getColor(this@SettingsActivity, R.color.text_2)
                        )
                    } else {
                        binding.rowCurrentWeight.value.text = getString(R.string.setting_weight_none)
                        binding.rowCurrentWeight.value.setTextColor(
                            ContextCompat.getColor(this@SettingsActivity, R.color.text_3)
                        )
                    }

                    // 我的情况（F6）：画像行右侧值；未填写显示「—」
                    binding.rowProfileAllergens.value.text = v.profileAllergens.joinToString("、").ifBlank { "—" }
                    binding.rowProfilePain.value.text = v.profilePain.joinToString("、").ifBlank { "—" }
                    binding.rowProfileScene.value.text = v.profileScene.ifBlank { "—" }
                    binding.rowProfileGear.value.text = v.profileGear.joinToString("、").ifBlank { "—" }
                    binding.rowProfileSleep.value.text = when {
                        v.profileSleepBed.isNotBlank() && v.profileSleepWake.isNotBlank() ->
                            getString(
                                R.string.setting_profile_sleep_value,
                                v.profileSleepBed,
                                v.profileSleepWake,
                            )
                        else -> listOf(v.profileSleepBed, v.profileSleepWake)
                            .firstOrNull { it.isNotBlank() } ?: "—"
                    }
                    binding.rowActivity.value.text = activityLabel(v.activity)
                    binding.rowTargetKcal.value.text = getString(R.string.plan_item_kcal, "", v.targetKcal).trimStart(' ', '·')
                    binding.rowDayStart.value.text = getString(R.string.unit_hour_clock, v.dayStart)

                    // 隐私：右侧值文字即状态，点击切换（不引入 Switch，规范 9.7 ④）
                    binding.rowHideKcal.value.text =
                        getString(if (v.hideKcal) R.string.value_hidden else R.string.value_shown)
                    binding.rowHideWeight.value.text =
                        getString(if (v.hideWeight) R.string.value_hidden else R.string.value_shown)

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

    /**
     * 「当前体重」编辑弹窗（F5）：复用 ConfirmSheet 同款字段行（[showFieldDialog]）。
     * 回显最近一条记录值；确认后经 [SettingsViewModel.saveCurrentWeight]
     * 写一条 events(type=body)，走与「记一笔」相同的数据管道。
     */
    private fun editCurrentWeight() {
        val initial = trimOrEmpty(vm.values.value.latestWeightKg)
        showFieldDialog(
            R.string.setting_current_weight,
            listOf(FieldSpec(R.string.setting_weight, initial, NUMBER_DECIMAL)),
        ) { raw ->
            raw.firstOrNull()?.toDoubleOrNull()?.let { vm.saveCurrentWeight(it) }
        }
    }

    private fun editGoalWeight() {
        val initial = trimOrEmpty(currentTarget(GoalMetrics.WEIGHT_KG, 0.0))
        showFieldDialog(
            R.string.setting_weight_goal,
            listOf(FieldSpec(R.string.setting_weight, initial, NUMBER_DECIMAL)),
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
                FieldSpec(R.string.goal_train_sessions_label, sessions, NUMBER_INT),
                FieldSpec(R.string.goal_train_minutes_label, minutes, NUMBER_INT),
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
            listOf(FieldSpec(R.string.unit_hour_plain, initial, NUMBER_DECIMAL)),
        ) { raw ->
            raw.firstOrNull()?.toDoubleOrNull()?.let { vm.setGoalTarget(GoalMetrics.SLEEP_H, it) }
        }
    }

    private fun editGoalWater() {
        val initial = trimOrEmpty(currentTarget(GoalMetrics.WATER_ML, 0.0))
        showFieldDialog(
            R.string.setting_water_goal,
            listOf(FieldSpec(R.string.setting_water_goal, initial, NUMBER_INT)),
        ) { raw ->
            raw.firstOrNull()?.toIntOrNull()?.let { vm.setGoalTarget(GoalMetrics.WATER_ML, it.toDouble()) }
        }
    }

    // ── 我的情况（结构化画像，F6）─────────────────────────────────

    /**
     * 标签类画像编辑（忌口 / 疼痛 / 器材）。
     * 复用 ConfirmSheet 同款字段弹窗（[showFieldDialog]），顿号/逗号分隔输入 ——
     * 功能清单 F6 明确不做花式 chip 编辑器。空输入 = 清空该字段
     * （[SettingsViewModel.put] 对空串执行 remove）。
     */
    private fun editProfileTags(key: String, labelRes: Int) {
        val v = vm.values.value
        val current = when (key) {
            SettingsKeys.PROFILE_ALLERGENS -> v.profileAllergens
            SettingsKeys.PROFILE_PAIN -> v.profilePain
            SettingsKeys.PROFILE_GEAR -> v.profileGear
            else -> emptyList()
        }
        showFieldDialog(
            labelRes,
            listOf(FieldSpec(labelRes, current.joinToString("、"), InputType.TYPE_CLASS_TEXT)),
        ) { raw ->
            val items = raw.getOrNull(0).orEmpty()
                .split("、", ",", "，", ";", "；", "/", "|")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            vm.put(key, listToJson(items))
        }
    }

    /** 就餐场景单选（F6：宿舍 / 食堂 / 外卖 / 自己做饭；首项「未固定」清空）。 */
    private fun chooseProfileScene() {
        val options = resources.getStringArray(R.array.profile_scenes)
        val items = arrayOf<CharSequence>(
            getString(R.string.setting_profile_scene_fixed),
            *options.map { it as CharSequence }.toTypedArray(),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_profile_scene)
            .setItems(items) { _, which ->
                vm.put(SettingsKeys.PROFILE_SCENE, if (which == 0) "" else options[which - 1])
            }
            .setNegativeButton(R.string.cancel, null as DialogInterface.OnClickListener?)
            .show()
    }

    /**
     * 作息两时间（F6 软背景）：就寝 / 起床，`HH:mm` 输入。
     * 清空输入 = 清除该项；非空但格式不合法的项跳过（不猜）。
     */
    private fun editProfileSleep() {
        val v = vm.values.value
        showFieldDialog(
            R.string.setting_profile_sleep,
            listOf(
                FieldSpec(R.string.setting_profile_bed_label, v.profileSleepBed, InputType.TYPE_CLASS_TEXT),
                FieldSpec(R.string.setting_profile_wake_label, v.profileSleepWake, InputType.TYPE_CLASS_TEXT),
            ),
        ) { raw ->
            val bed = raw.getOrNull(0).orEmpty().trim()
            val wake = raw.getOrNull(1).orEmpty().trim()
            listOf(
                SettingsKeys.PROFILE_SLEEP_BED to bed,
                SettingsKeys.PROFILE_SLEEP_WAKE to wake,
            ).forEach { (k, value) ->
                if (value.isBlank() || TIME_RE.matches(value)) vm.put(k, value)
            }
        }
    }

    /** List → JSON 数组字符串（与 AI 抽取的 foods 存储形态同构）。 */
    private fun listToJson(items: List<String>): String {
        val arr = org.json.JSONArray()
        items.forEach { arr.put(it) }
        return arr.toString()
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
            FieldSpec(R.string.reminder_field_name, existing?.name.orEmpty(), InputType.TYPE_CLASS_TEXT),
            FieldSpec(
                R.string.reminder_field_days,
                existing?.intervalDays?.toString().orEmpty(),
                NUMBER_INT,
            ),
            FieldSpec(
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

    /** 复用 ConfirmSheet 的字段行（`row_sheet_field`）做数值 / 文本编辑。 */
    private fun showFieldDialog(titleRes: Int, specs: List<FieldSpec>, onOk: (List<String>) -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val inputs = mutableListOf<EditText>()
        specs.forEach { spec ->
            val b = RowSheetFieldBinding.inflate(layoutInflater, container, false)
            b.fieldLabel.setText(spec.labelRes)
            b.fieldValue.setText(spec.initial)
            b.fieldValue.inputType = spec.inputType
            b.fieldValue.setSelection(b.fieldValue.text.length)
            container.addView(b.root)
            inputs += b.fieldValue
        }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.confirm) { _: DialogInterface, _: Int ->
                onOk(inputs.map { it.text.toString().trim() })
            }
            .setNegativeButton(R.string.cancel, null as DialogInterface.OnClickListener?)
            .show()
    }

    /** 字段行规格（label 用资源 id，避免硬编码中文）。 */
    private data class FieldSpec(val labelRes: Int, val initial: String, val inputType: Int)

    /** 毫秒时间戳 → `yyyy-MM-dd`（本地时区）。 */
    private fun dateLabel(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    /**
     * day_key（yyyy-MM-dd）→ 相对时间：「今天」/「N 天前」。
     * 解析失败按"很久以前"处理（返回空串占位，让上层拼接后仍可读）。
     */
    private fun agoLabel(dayKey: String): String {
        if (dayKey.isBlank()) return ""
        val days = runCatching {
            java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(dayKey), LocalDate.now())
        }.getOrDefault(-1L)
        return when {
            days < 0 -> ""
            days <= 0 -> getString(R.string.setting_weight_ago_today)
            else -> getString(R.string.setting_weight_ago_days, days)
        }
    }

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

        // ⚠️ 键名一律从 SettingsKeys 取 —— 那里是唯一事实来源。
        //    2026-10-03 修过一次键名分裂 bug（见 SettingsKeys 头注释），
        //    此处保留 `KEY_*` 前缀是为了不改动几十个调用点，
        //    但值的来源必须收敛到 SettingsKeys。
        const val KEY_BASE_URL = SettingsKeys.BASE_URL
        const val KEY_MODEL = SettingsKeys.MODEL
        const val KEY_PROVIDER = SettingsKeys.PROVIDER
        const val KEY_QUOTA = SettingsKeys.EXTRACT_QUOTA
        const val KEY_CHAT_QUOTA = SettingsKeys.CHAT_QUOTA
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

        /**
         * 背景字数上限。
         * 2000 字中文约 2000-2600 token，对免费档是可控的开销；
         * 再长会挤占今日摘要与历史窗口的预算。
         */
        const val BACKGROUND_MAX = 2000

        /** 活动系数（总方案第五节 BMR 公式）。 */
        val ACTIVITY_VALUES = listOf("1.2", "1.375", "1.55", "1.725")

        /** 一天的毫秒数，用于提醒临期（≤7 天）判定。 */
        private const val DAY_MS = 24L * 60 * 60 * 1000

        private const val NUMBER_INT = InputType.TYPE_CLASS_NUMBER
        private const val NUMBER_DECIMAL =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        /** 作息时间格式（F6）：`HH:mm`，如 `00:30` / `7:30`。 */
        private val TIME_RE = Regex("""\d{1,2}:\d{2}""")
    }
}
