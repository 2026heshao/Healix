package com.healix.app.ui

import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.FragmentPersonalInfoBinding
import com.healix.app.db.SettingsKeys
import kotlinx.coroutines.launch

/**
 * 个人信息页（P1）：从设置页「个人 / 我的情况」两组迁移而来。
 *
 * 交互：基本信息（当前体重 / 身高 / 年龄 / 活动系数）→ 我的情况（忌口过敏 /
 * 疼痛不适 / 就餐场景 / 作息 / 补充说明）。
 *
 * 数据层**复用**现成的 [SettingsViewModel]（存储键名值域零改动）；本页只做 UI：
 * - **字段输入类**走 [FieldSheet]（底色容器，§5.1 统一载体）；
 * - **列表选择类**（活动系数 / 就餐场景）保留系统 `AlertDialog.setItems`（§5.1 边界）；
 * - 「当前体重」入口与存储分离：确认后经 [SettingsViewModel.saveCurrentWeight]
 *   写一条 `events(type=body)`（与「记一笔」同管道，契约不变）。
 *
 * ⚠️ 本页**不再承载任何目标数值**（v8 问题 2a：热量目标行已迁「目标」栏）——
 * 数值目标唯一归属设置页「目标」栏。也不含任何新的数值兜底常量；
 * 活动系数取值 [ACTIVITY_VALUES] 由设置页迁来（**不复制两份**）。
 *
 * v8 T03：由 `PersonalInfoActivity` 迁为宿主 [MainActivity] 内的二级页 Fragment。
 */
class PersonalInfoFragment : Fragment() {

    private var _binding: FragmentPersonalInfoBinding? = null
    private val binding get() = _binding!!

    private lateinit var vm: SettingsViewModel

    /** 背景项：最近一次已落库的内容，用于避免重复写入。 */
    private var lastSavedBackground: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPersonalInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vm = SettingsViewModel(HealixApp.from(requireContext()))

        binding.btnBack.setOnClickListener { NavHost.back(requireContext()) }

        // ── 基本信息 ──────────────────────────────────────────────
        setupRow(binding.rowCurrentWeight, R.string.setting_current_weight) { editCurrentWeight() }
        setupRow(binding.rowHeight, R.string.setting_height) { editHeight() }
        setupRow(binding.rowAge, R.string.setting_age) { editAge() }
        setupRow(binding.rowActivity, R.string.setting_activity) { chooseActivity() }

        // ── 每日摄入 ──────────────────────────────────────────────
        // v8 问题 2a：「每日目标摄入」行**整体移除** —— 数值目标（含热量）唯一归属
        // 设置页「目标」栏（`fragment_settings.xml` 动态目标行）；本页只保留静态档案。
        // 原 settings 键 TARGET_KCAL 仅作老数据迁移源（SettingsViewModel.migrateLegacyKcalTarget）。

        // ── 目标（唯一归属设置页「目标」栏）──────────────────────────
        // v10：本行改为**只读展示 + 跳转**——展示值与设置页主目标行同源
        // （goals 表 primary：增重/减重/保持/自定义文本），点击进设置页目标栏。
        // 不再有第二份可编辑的目标入口（原自由文本「我的目标」已并入主目标自定义态，
        // 键 GOAL_STATEMENT 由 GoalSetupSheet / 设置页统一读写）。
        binding.rowGoalStatement.label.setText(R.string.setting_goal_statement)
        binding.rowGoalStatement.chevron.visibility = View.VISIBLE
        binding.rowGoalStatement.root.setOnClickListener {
            NavHost.open(requireContext(), SettingsFragment.newInstance(focusGoal = true), NavHost.PAGE_SETTINGS)
        }

        // 清单3 R1/Q3：自定义目标只读展示行（非空可见）。点击 = 同样跳设置页目标栏
        // （目标唯一可编辑归属在设置页；本页与 rowGoalStatement 同款只读 + 跳转）。
        binding.rowCustomGoal.label.setText(R.string.custom_goal_label)
        binding.rowCustomGoal.chevron.visibility = View.VISIBLE
        binding.rowCustomGoal.root.setOnClickListener {
            NavHost.open(requireContext(), SettingsFragment.newInstance(focusGoal = true), NavHost.PAGE_SETTINGS)
        }

        // ── 我的情况（结构化画像，F6）────────────────────────────
        setupRow(binding.rowProfileAllergens, R.string.setting_profile_allergens) {
            editProfileTags(SettingsKeys.PROFILE_ALLERGENS, R.string.setting_profile_allergens)
        }
        setupRow(binding.rowProfilePain, R.string.setting_profile_pain) {
            editProfileTags(SettingsKeys.PROFILE_PAIN, R.string.setting_profile_pain)
        }
        setupRow(binding.rowProfileScene, R.string.setting_profile_scene) { chooseProfileScene() }
        setupRow(binding.rowProfileSleep, R.string.setting_profile_sleep) { editProfileSleep() }

        setupBackground()

        observe()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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

    // ── 字段编辑（复用 FieldSheet 底色容器，§5.1）────────────────────

    /**
     * 弹 [FieldSheet] 采集字段：取消不回调，确认回传各字段文本（顺序与 specs 一致）。
     *
     * ⚠️ Fragment 化后容器用 **childFragmentManager**（弹窗随二级页一起销毁）。
     */
    private fun showField(
        titleRes: Int,
        specs: List<FieldSheet.FieldSpec>,
        onOk: (List<String>) -> Unit,
    ) {
        val sheet = FieldSheet.newInstance(titleRes, specs)
        sheet.onResult = onOk
        sheet.show(childFragmentManager, FieldSheet.TAG)
    }

    /** 身高（正整数，空输入清除）。 */
    private fun editHeight() {
        val cur = vm.values.value.height
        showField(
            R.string.setting_height,
            listOf(FieldSheet.FieldSpec(R.string.setting_height, if (cur > 0) cur.toString() else "", NUMBER_INT)),
        ) { raw ->
            val text = raw.firstOrNull().orEmpty()
            if (text.isBlank()) {
                vm.put(SettingsKeys.HEIGHT, "")
            } else {
                text.toIntOrNull()?.takeIf { it > 0 }?.let { vm.put(SettingsKeys.HEIGHT, it.toString()) }
            }
        }
    }

    /** 年龄（正整数，空输入清除）。 */
    private fun editAge() {
        val cur = vm.values.value.age
        showField(
            R.string.setting_age,
            listOf(FieldSheet.FieldSpec(R.string.setting_age, if (cur > 0) cur.toString() else "", NUMBER_INT)),
        ) { raw ->
            val text = raw.firstOrNull().orEmpty()
            if (text.isBlank()) {
                vm.put(SettingsKeys.AGE, "")
            } else {
                text.toIntOrNull()?.takeIf { it > 0 }?.let { vm.put(SettingsKeys.AGE, it.toString()) }
            }
        }
    }

    /**
     * 「当前体重」（F5）：复用 [FieldSheet] 字段行，回显最近一条记录值；
     * 确认后经 [SettingsViewModel.saveCurrentWeight] 写一条 `events(type=body)`，
     * 走与「记一笔」相同的数据管道。
     */
    private fun editCurrentWeight() {
        val initial = trimOrEmpty(vm.values.value.latestWeightKg)
        showField(
            R.string.setting_current_weight,
            listOf(FieldSheet.FieldSpec(R.string.setting_weight, initial, NUMBER_DECIMAL)),
        ) { raw ->
            raw.firstOrNull()?.toDoubleOrNull()?.let { vm.saveCurrentWeight(it) }
        }
    }

    /**
     * 标签类画像编辑（忌口 / 疼痛）。顿号/逗号分隔输入，空输入 = 清空该字段
     * （[SettingsViewModel.put] 对空串执行 remove）。
     */
    private fun editProfileTags(key: String, labelRes: Int) {
        val v = vm.values.value
        val current = when (key) {
            SettingsKeys.PROFILE_ALLERGENS -> v.profileAllergens
            SettingsKeys.PROFILE_PAIN -> v.profilePain
            else -> emptyList()
        }
        showField(
            labelRes,
            listOf(FieldSheet.FieldSpec(labelRes, current.joinToString("、"), InputType.TYPE_CLASS_TEXT)),
        ) { raw ->
            val items = raw.getOrNull(0).orEmpty()
                .split("、", ",", "，", ";", "；", "/", "|")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            vm.put(key, listToJson(items))
        }
    }

    /**
     * 作息两时间（F6 软背景）：就寝 / 起床，`HH:mm` 输入。
     * 清空输入 = 清除该项；非空但格式不合法的项跳过（不猜）。
     */
    private fun editProfileSleep() {
        val v = vm.values.value
        showField(
            R.string.setting_profile_sleep,
            listOf(
                FieldSheet.FieldSpec(R.string.setting_profile_bed_label, v.profileSleepBed, InputType.TYPE_CLASS_TEXT),
                FieldSheet.FieldSpec(R.string.setting_profile_wake_label, v.profileSleepWake, InputType.TYPE_CLASS_TEXT),
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

    // ── 列表选择（保留系统 AlertDialog，§5.1 边界）────────────────────

    /** 活动系数单选（取值 [ACTIVITY_VALUES]）。 */
    private fun chooseActivity() {
        val labels = resources.getStringArray(R.array.activity_levels)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_activity)
            .setItems(labels) { _, which -> vm.put(SettingsKeys.ACTIVITY, ACTIVITY_VALUES[which]) }
            .setNegativeButton(R.string.cancel, null as DialogInterface.OnClickListener?)
            .show()
    }

    /** 就餐场景单选（F6：宿舍 / 食堂 / 外卖 / 自己做饭；首项「未固定」清空）。 */
    private fun chooseProfileScene() {
        val options = resources.getStringArray(R.array.profile_scenes)
        val items = arrayOf<CharSequence>(
            getString(R.string.setting_profile_scene_fixed),
            *options.map { it as CharSequence }.toTypedArray(),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_profile_scene)
            .setItems(items) { _, which ->
                vm.put(SettingsKeys.PROFILE_SCENE, if (which == 0) "" else options[which - 1])
            }
            .setNegativeButton(R.string.cancel, null as DialogInterface.OnClickListener?)
            .show()
    }

    /** List → JSON 数组字符串（与 AI 抽取的 foods 存储形态同构）。 */
    private fun listToJson(items: List<String>): String {
        val arr = org.json.JSONArray()
        items.forEach { arr.put(it) }
        return arr.toString()
    }

    // ── 补充说明（自由文本，多行）────────────────────────────────────

    /**
     * 补充说明（F6 保留的自由文本）：常驻输入框（非弹窗 —— 长文本用弹窗体验差）。
     * 保存时机：**失焦**（onFocusChange）+ **返回 / 切后台前**（onPause 兜底）。
     * 上限 [BACKGROUND_MAX] 字：**输入时**即硬截断（不能只在保存时截 —— 静默丢数据）。
     */
    private fun setupBackground() {
        val edit = binding.editBackground

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
        binding.backgroundCount.text = getString(
            R.string.setting_background_count, edit.text.length, BACKGROUND_MAX,
        )

        edit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveBackground()
        }
    }

    /** 把输入框内容落库。内容没变则不写（避免无谓 IO 与 StateFlow 抖动）。 */
    private fun saveBackground() {
        val text = binding.editBackground.text.toString().trim()
        if (text == lastSavedBackground) return
        lastSavedBackground = text
        vm.put(SettingsKeys.BACKGROUND, text)
    }

    override fun onPause() {
        // 兜底：用户直接按返回 / 切后台时不走失焦回调，这里补一次。
        // ⚠️ 视图可能已拆解（onDestroyView 之后宿主仍会走 onPause）→ 先判 _binding。
        super.onPause()
        if (_binding != null) saveBackground()
    }

    // ── 渲染 ──────────────────────────────────────────────────────

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.values.collect { v ->
                    binding.rowHeight.value.text =
                        if (v.height > 0) getString(R.string.unit_cm, v.height) else "—"
                    binding.rowAge.value.text =
                        if (v.age > 0) getString(R.string.unit_years, v.age) else "—"

                    // 当前体重行（F5）：有记录显示「值 · 相对时间」；从未记录显示灰色「未记录」
                    if (v.latestWeightKg > 0) {
                        binding.rowCurrentWeight.value.text = getString(
                            R.string.setting_current_weight_value,
                            trim(v.latestWeightKg),
                            agoLabel(v.latestWeightDayKey),
                        )
                        binding.rowCurrentWeight.value.setTextColor(
                            ContextCompat.getColor(requireContext(), R.color.text_2)
                        )
                    } else {
                        binding.rowCurrentWeight.value.text = getString(R.string.setting_weight_none)
                        binding.rowCurrentWeight.value.setTextColor(
                            ContextCompat.getColor(requireContext(), R.color.text_3)
                        )
                    }

                    binding.rowActivity.value.text = activityLabel(v.activity)

                    // 我的情况（F6）：画像行右侧值；未填写显示「—」
                    binding.rowProfileAllergens.value.text =
                        v.profileAllergens.joinToString("、").ifBlank { "—" }
                    binding.rowProfilePain.value.text =
                        v.profilePain.joinToString("、").ifBlank { "—" }
                    binding.rowProfileScene.value.text = v.profileScene.ifBlank { "—" }
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

                    // 背景：只在「用户没在编辑」时回填，否则 reload 回填会覆写正在敲的字
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
        // 目标行（v10）：与设置页主目标同源（goals 表 primary + 自由文本自述）。
        // combine 保证 mode 与 statement 同帧，避免「自定义文本已改、行值滞后一帧」。
        // 清单3 R1：同行带出自定义次目标（customGoalText 非空才显示只读行）。
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(vm.goals, vm.values) { list, v ->
                    list to v
                }.collect { (list, v) ->
                    val mode = list.firstOrNull { it.metric == com.healix.app.db.GoalMetrics.PRIMARY }
                        ?.targetValue?.toInt() ?: SettingsViewModel.GOAL_MODE_GAIN
                    binding.rowGoalStatement.value.text = when (mode) {
                        SettingsViewModel.GOAL_MODE_LOSS -> getString(R.string.goal_loss)
                        SettingsViewModel.GOAL_MODE_KEEP -> getString(R.string.goal_keep)
                        SettingsViewModel.GOAL_MODE_CUSTOM ->
                            v.goalStatement.ifBlank { getString(R.string.value_not_set) }
                        else -> getString(R.string.goal_gain)
                    }
                    renderCustomGoal(v.customGoalText)
                }
            }
        }
    }

    /**
     * 自定义目标只读展示（清单3 R1）：非空可见并显示文本本身，空则整行 GONE
     * （不做「未设置」占位 —— 未使用自定义目标的用户不该看到一行占位噪声）。
     */
    private fun renderCustomGoal(text: String) {
        if (text.isBlank()) {
            binding.rowCustomGoal.root.visibility = View.GONE
        } else {
            binding.rowCustomGoal.root.visibility = View.VISIBLE
            binding.rowCustomGoal.value.text = text
        }
    }

    private fun activityLabel(value: String): String {
        val labels = resources.getStringArray(R.array.activity_levels)
        val idx = ACTIVITY_VALUES.indexOf(value)
        return labels.getOrElse(idx) { labels[0] }
    }

    /**
     * day_key（yyyy-MM-dd）→ 相对时间：「今天」/「N 天前」。
     * 解析失败按"很久以前"处理（返回空串占位，让上层拼接后仍可读）。
     */
    private fun agoLabel(dayKey: String): String {
        if (dayKey.isBlank()) return ""
        val days = runCatching {
            java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(dayKey), java.time.LocalDate.now())
        }.getOrDefault(-1L)
        return when {
            days < 0 -> ""
            days <= 0 -> getString(R.string.setting_weight_ago_today)
            else -> getString(R.string.setting_weight_ago_days, days)
        }
    }

    private fun trimOrEmpty(v: Double): String =
        if (v <= 0.0) "" else trim(v)

    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    companion object {
        /**
         * 活动系数（总方案第五节 BMR 公式）。
         * ⚠️ 从设置页（原型类名 `SettingsActivity`，v8 已迁为 [SettingsFragment]）
         * 迁来（P1 迁移）—— **不得两份并存**。
         */
        val ACTIVITY_VALUES = listOf("1.2", "1.375", "1.55", "1.725")

        /** 背景字数上限（与设置页原值一致）。 */
        const val BACKGROUND_MAX = 2000

        private const val NUMBER_INT = InputType.TYPE_CLASS_NUMBER
        private const val NUMBER_DECIMAL =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        /** 作息时间格式（F6）：`HH:mm`，如 `00:30` / `7:30`。 */
        private val TIME_RE = Regex("""\d{1,2}:\d{2}""")
    }
}
