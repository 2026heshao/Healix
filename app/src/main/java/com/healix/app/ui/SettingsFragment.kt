package com.healix.app.ui

import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.FragmentSettingsBinding
import com.healix.app.databinding.ItemSwipeRowBinding
import com.healix.app.databinding.RowSettingSwitchBinding
import com.healix.app.databinding.RowSettingValueBinding
import com.healix.app.db.GoalDefaults
import com.healix.app.db.GoalEntity
import com.healix.app.db.GoalMetrics
import com.healix.app.db.GoalSlots
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
 *
 * v8 T03：由 `SettingsActivity` 迁为宿主 [MainActivity] 内的二级页 Fragment。
 * 键名常量 [KEY_BASE_URL] 等仍在此转发 [SettingsKeys]（唯一事实来源）——
 * 只服务本页自身调用点；[SettingsViewModel] 已改为**直接**引用 [SettingsKeys]，
 * 不再反向依赖 UI 层（原先 ViewModel → Activity 的依赖是分层倒挂）。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var vm: SettingsViewModel

    /**
     * 「热量摄入」开关监听（清单3 R3）：复用既有 [SettingsViewModel.toggleHide]
     * （`HIDE_KCAL` 写 "true"/"false"，IO 协程）—— 该方法注释「将来若在别处恢复入口，
     * 直接调本方法即可」的正主调用点。observe 回填时先摘监听再 setChecked 再挂回，
     * 防程序化 setChecked 触发本监听造成写回环。
     */
    private val kcalSwitchListener: CompoundButton.OnCheckedChangeListener =
        CompoundButton.OnCheckedChangeListener { _, _ ->
            vm.toggleHide(SettingsKeys.HIDE_KCAL)
        }

    /**
     * 「AI 可见资料范围」开关监听（SettingsKeys.AI_DATA_FULL）：
     * 复用 [SettingsViewModel.toggleAiDataFull]（IO 协程写键 + reload）。
     * observe 回填时先摘监听再 setChecked 再挂回，防程序化 setChecked 触发
     * 本监听造成写回环。⚠️ checked = aiDataFull 本身（**不取反**，
     * 与 kcal 行「HIDE_KCAL 取反显示」语义相反）。
     */
    private val aiDataSwitchListener: CompoundButton.OnCheckedChangeListener =
        CompoundButton.OnCheckedChangeListener { _, _ ->
            vm.toggleAiDataFull()
        }

    // ── AI 工具与写权限开关监听（v0.3 B5/B6，D4：默认开）───────────────
    // 复用 SettingsViewModel.toggleAiSwitch(key)（IO 协程写键 + reload）。
    // observe 回填时先摘监听再 setChecked 再挂回，防程序化 setChecked 触发
    // 本监听造成写回环（与 aiDataSwitchListener 同款）。
    private val aiToolsSwitchListener: CompoundButton.OnCheckedChangeListener =
        CompoundButton.OnCheckedChangeListener { _, _ ->
            vm.toggleAiSwitch(SettingsKeys.AI_TOOLS_ENABLED)
        }
    private val aiWritePlanListener: CompoundButton.OnCheckedChangeListener =
        CompoundButton.OnCheckedChangeListener { _, _ ->
            vm.toggleAiSwitch(SettingsKeys.AI_TOOL_WRITE_PLAN)
        }
    private val aiWriteRecordListener: CompoundButton.OnCheckedChangeListener =
        CompoundButton.OnCheckedChangeListener { _, _ ->
            vm.toggleAiSwitch(SettingsKeys.AI_TOOL_WRITE_RECORD)
        }
    private val aiWriteGoalListener: CompoundButton.OnCheckedChangeListener =
        CompoundButton.OnCheckedChangeListener { _, _ ->
            vm.toggleAiSwitch(SettingsKeys.AI_TOOL_WRITE_GOAL)
        }

    /** 左滑删除（11.3 / v8 需求 4）：目标行与提醒行共用 1 个实例 → 全局单开。 */
    private lateinit var swipe: SwipeController

    /**
     * 宿主 Activity 作用域的 [MainViewModel]：**只**用于「目标栏 → 主目标首启引导」的
     * 落库（[MainViewModel.completeGoalSetup]），避免把同一段 ensure+写值逻辑复制一份。
     * 与 [RecordFragment] 取到的是**同一个实例**（`ViewModelProvider(requireActivity())`）。
     */
    private val mainVm: MainViewModel by lazy {
        ViewModelProvider(requireActivity())[MainViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vm = SettingsViewModel(HealixApp.from(requireContext()))

        binding.btnBack.setOnClickListener { NavHost.back(requireContext()) }

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
        // v8 需求 4：行改为按 `goalDao().observeActive()` **动态渲染**（见 renderGoals，
        // 支持左滑删除）；此处只接末尾的「添加目标」入口（恢复归档项 / 补齐缺失项）。
        // v8 问题 2a/2b + G11：「添加目标」的状态（强调色可点 / 置灰禁用）与文案由
        // renderGoals → updateGoalAddEntry 按当前已启用槽位动态决定，这里不写死。
        binding.rowGoalAdd.label.setText(R.string.goal_add)
        binding.rowGoalAdd.root.setOnClickListener { showAddGoal() }

        // 目标组「依据提示」：仅首次打开该组时显示一次（规范 9.7）
        setupGoalSourceHint()
        // v8 问题 2a：老用户 kcal 目标迁移提示，仅首次显示（可点关闭）
        setupGoalKcalMovedHint()

        // ── 热量摄入（清单3 R3：kcal 的唯一 UI 入口）────────────────
        binding.rowKcalSwitch.label.setText(R.string.kcal_show_toggle)
        binding.rowKcalSwitch.switchWidget.setOnCheckedChangeListener(kcalSwitchListener)
        // 行点击 = 同义拨动开关（放大触控目标，规范 ②·触控）；行内 Switch 点击照常直拨
        binding.rowKcalSwitch.root.setOnClickListener {
            val sw = binding.rowKcalSwitch.switchWidget
            sw.isChecked = !sw.isChecked // 触发监听 → toggleHide，与直拨同一写链
        }
        // 数值行：editGoalKcal() 函数原样保留，仅入口从目标组动态行迁至本栏（:601）
        binding.rowKcalGoal.label.setText(R.string.setting_kcal_goal)
        binding.rowKcalGoal.chevron.visibility = View.VISIBLE
        binding.rowKcalGoal.root.setOnClickListener { editGoalKcal() }

        // ── AI 可见资料范围（SettingsKeys.AI_DATA_FULL 总开关的 UI 入口）──
        // checked = aiDataFull 本身（不取反）；行点击 = 同义拨动开关（放大触控目标，
        // 与 kcal 行同款）。
        binding.rowAiDataSwitch.label.setText(R.string.ai_data_toggle)
        binding.rowAiDataSwitch.switchWidget.setOnCheckedChangeListener(aiDataSwitchListener)
        binding.rowAiDataSwitch.root.setOnClickListener {
            val sw = binding.rowAiDataSwitch.switchWidget
            sw.isChecked = !sw.isChecked // 触发监听 → toggleAiDataFull，与直拨同一写链
        }

        // ── AI 组：规则库入口 + 工具 / 写权限开关（v0.3 B4/B5/B6）──
        // 规则库入口：进二级页增删改启停排序（页面自带「添加规则」）。
        binding.rowAiRules.label.setText(R.string.rules_title)
        binding.rowAiRules.chevron.visibility = View.VISIBLE
        binding.rowAiRules.root.setOnClickListener {
            NavHost.open(requireContext(), RulesFragment(), NavHost.PAGE_RULES)
        }
        // 工具总开关 + 三个写权限开关：均为「默认开」，行点击 = 同义拨动开关（放大触控目标）。
        setupAiSwitch(binding.rowAiTools, R.string.ai_tools_toggle, aiToolsSwitchListener)
        setupAiSwitch(
            binding.rowAiToolWritePlan,
            R.string.ai_tool_write_plan,
            aiWritePlanListener,
        )
        setupAiSwitch(
            binding.rowAiToolWriteRecord,
            R.string.ai_tool_write_record,
            aiWriteRecordListener,
        )
        setupAiSwitch(
            binding.rowAiToolWriteGoal,
            R.string.ai_tool_write_goal,
            aiWriteGoalListener,
        )

        // ── 提醒（reminders 表）──────────────────────────────────
        // v8 需求 4：不再预置默认提醒，仅保留「添加提醒」入口；行支持左滑删除。
        setupRow(binding.rowReminderAdd, R.string.reminder_add) { editReminder(null) }

        // v6（11.1）：「数据 / 调试 / 知识库」三组迁「我的」页，设置页回归纯配置。
        // v8 需求 6：原「隐私」组（HIDE_KCAL / HIDE_WEIGHT）UI 入口整体移除；
        //            两个 settings 键与全部消费方（首页 / 状态详情 / 对话提示）保留。

        // ── 左滑删除（11.3 / v8 需求 4）────────────────────────────
        swipe = SwipeController(requireContext())
        // 列表可视区外的按下（工具栏 / 按钮区 / 分组标题等）也收起滑开的行
        binding.root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) swipe.closeIfOutside(null)
            false // 不消费
        }

        observe()

        // 「个人信息页 → 我的目标」跳转进入时，自动滚到「目标」栏（focus_goal 参数）。
        if (arguments?.getBoolean(ARG_FOCUS_GOAL, false) == true) scrollToGoal()
    }

    /** 滚到「目标」栏。post：等首次布局完成后再取 goalContainer 的纵向位置。 */
    private fun scrollToGoal() {
        binding.settingsScroll.post {
            val target = offsetWithin(binding.settingsScroll, binding.goalSectionHeader)
            if (target > 0) binding.settingsScroll.smoothScrollTo(0, target)
        }
    }

    /**
     * 重新可见时补做「跳到目标栏」。
     *
     * 为什么需要：keep-alive 结构改造后，被复用的页**不会重走 onViewCreated**（见 [NavHost]）
     * ——「个人信息页 → 我的目标」这条入口若命中复用（arguments 相同），滚动意图会被静默丢掉，
     * 用户落在目标栏之外（而 `focus_goal` 的全部意义就是"一步到位"）。
     * `view == null` 守卫：视图未建时不得触碰 `binding`。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden || view == null) return
        if (arguments?.getBoolean(ARG_FOCUS_GOAL, false) == true) scrollToGoal()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 给 include 出来的行设标签与点击。箭头只在可点行显示。 */
    private fun setupRow(
        row: RowSettingValueBinding,
        labelRes: Int,
        onClick: () -> Unit,
    ) {
        row.label.setText(labelRes)
        row.chevron.visibility = View.VISIBLE
        row.root.setOnClickListener { onClick() }
    }

    /**
     * 给一个 AI 工具开关行设标签 + 监听 + 「行点击 = 同义拨动开关」（v0.3 B5/B6）。
     * 与 rowKcalSwitch / rowAiDataSwitch 同款：拨动走同一写链，行点击只是放大触控目标。
     * observe 回填负责 setChecked（先摘监听再挂回，防写回环）。
     */
    private fun setupAiSwitch(
        row: RowSettingSwitchBinding,
        labelRes: Int,
        listener: CompoundButton.OnCheckedChangeListener,
    ) {
        row.label.setText(labelRes)
        row.switchWidget.setOnCheckedChangeListener(listener)
        row.root.setOnClickListener {
            val sw = row.switchWidget
            sw.isChecked = !sw.isChecked // 触发监听，与直拨开关同一写链
        }
    }

    /**
     * 回填一个开关：先摘监听 → `setChecked` → 挂回。
     * 程序化 `setChecked` 不得触发写监听（防写回环）——与 `rowKcalSwitch` / `rowAiDataSwitch` 同款。
     */
    private fun bindSwitch(
        sw: CompoundButton,
        checked: Boolean,
        listener: CompoundButton.OnCheckedChangeListener,
    ) {
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener(listener)
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
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
                    // 清单3 R3：「热量摄入」开关态 = HIDE_KCAL 取反（键不存在 = 显示）。
                    // 先摘监听再回填：程序化 setChecked 不得触发 toggleHide（防写回环）。
                    binding.rowKcalSwitch.switchWidget.setOnCheckedChangeListener(null)
                    binding.rowKcalSwitch.switchWidget.isChecked = !v.hideKcal
                    binding.rowKcalSwitch.switchWidget.setOnCheckedChangeListener(kcalSwitchListener)
                    // AI 可见资料范围：checked = aiDataFull 本身（**不取反**，与 kcal 行语义相反）。
                    // 先摘监听再回填再挂回：程序化 setChecked 不得触发 toggleAiDataFull（防写回环）。
                    binding.rowAiDataSwitch.switchWidget.setOnCheckedChangeListener(null)
                    binding.rowAiDataSwitch.switchWidget.isChecked = v.aiDataFull
                    binding.rowAiDataSwitch.switchWidget.setOnCheckedChangeListener(aiDataSwitchListener)
                    // AI 工具与写权限开关（v0.3 B5/B6）：先摘监听再回填再挂回（防写回环）。
                    bindSwitch(binding.rowAiTools.switchWidget, v.aiToolsEnabled, aiToolsSwitchListener)
                    bindSwitch(binding.rowAiToolWritePlan.switchWidget, v.aiToolWritePlan, aiWritePlanListener)
                    bindSwitch(binding.rowAiToolWriteRecord.switchWidget, v.aiToolWriteRecord, aiWriteRecordListener)
                    bindSwitch(binding.rowAiToolWriteGoal.switchWidget, v.aiToolWriteGoal, aiWriteGoalListener)
                    // 写权限三行仅工具总开关开启时显示（关掉总开关 = 无工具，写权限无意义）。
                    val writeVisible = if (v.aiToolsEnabled) View.VISIBLE else View.GONE
                    binding.rowAiToolWritePlan.root.visibility = writeVisible
                    binding.rowAiToolWriteRecord.root.visibility = writeVisible
                    binding.rowAiToolWriteGoal.root.visibility = writeVisible
                }
            }
        }
        // 规则库条数（v0.3 B4）：入口行右侧值，随规则增删即时刷新。
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ruleCount.collect { n ->
                    binding.rowAiRules.value.text = if (n == 0) {
                        getString(R.string.rules_entry_none)
                    } else {
                        getString(R.string.rules_entry_count, n)
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.testResult.collect { r ->
                    if (r == null) {
                        binding.testResult.visibility = View.GONE
                    } else {
                        binding.testResult.visibility = View.VISIBLE
                        binding.testResult.text = r.text
                        // 成功用 success，失败用 negative（= error）—— 只出现在文字上
                        binding.testResult.setTextColor(
                            ContextCompat.getColor(
                                requireContext(),
                                if (r.ok) R.color.success else R.color.negative,
                            )
                        )
                    }
                }
            }
        }
        // 接入结果：与测试结果共用同一个展示位（避免两条状态文本打架）
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.applyResult.collect { r ->
                    if (r == null) return@collect
                    binding.testResult.visibility = View.VISIBLE
                    binding.testResult.text = r.text
                    binding.testResult.setTextColor(
                        ContextCompat.getColor(
                            requireContext(),
                            if (r.ok) R.color.success else R.color.negative,
                        )
                    )
                }
            }
        }
        // 接入状态条：常驻显示「已接入 / 未接入」，不靠弹窗
        // 同时订阅 values（取服务商/模型名）与 applied（取接入与否），
        // 保证两者永远同帧一致 —— 分开读 .value 会出现"名字已更新但状态没更新"的撕裂。
        viewLifecycleOwner.lifecycleScope.launch {
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
        // 目标：goals 表一次订阅 + 自由文本自述 + 自定义次目标（清单3 R1），同帧渲染（规范 9.7 ①）
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(vm.goals, vm.values) { list, v ->
                    Triple(list, v.goalStatement, v.customGoalText)
                }.collect { (list, statement, customGoalText) ->
                    renderGoals(list, statement, customGoalText)
                }
            }
        }
        // 提醒：reminders 表动态渲染（可增删，规范 9.7 ③）
        viewLifecycleOwner.lifecycleScope.launch {
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
        viewLifecycleOwner.lifecycleScope.launch {
            if (vm.raw(SettingsKeys.GOAL_SOURCE_SEEN) == "true") return@launch
            binding.goalSourceHint.setText(R.string.setting_goal_source_dietary)
            binding.goalSourceHint.visibility = View.VISIBLE
            vm.put(SettingsKeys.GOAL_SOURCE_SEEN, "true")
        }
    }

    /**
     * v8 问题 2a：老用户迁移提示 `热量目标现已移至此栏`，**仅一次**、可点关闭。
     *
     * 数据驱动（不靠"读 settings 标记然后祈祷时序"）：
     * 订阅 [SettingsViewModel.kcalMoved] —— 迁移真的发生才提示；没迁过（新用户 /
     * 从没设过热量目标的老用户）不提示，避免无意义打扰。
     * 展示过即落 [SettingsKeys.KCAL_MOVE_HINT_SEEN]，跨启动不再出现。
     */
    private fun setupGoalKcalMovedHint() {
        var shown = false
        binding.goalKcalMovedHint.setOnClickListener {
            binding.goalKcalMovedHint.visibility = View.GONE
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.kcalMoved.collect { moved ->
                    if (!moved || shown) return@collect
                    if (vm.raw(SettingsKeys.KCAL_MOVE_HINT_SEEN) == "true") return@collect
                    shown = true
                    binding.goalKcalMovedHint.setText(R.string.goal_kcal_moved_hint)
                    binding.goalKcalMovedHint.visibility = View.VISIBLE
                    vm.put(SettingsKeys.KCAL_MOVE_HINT_SEEN, "true")
                }
            }
        }
    }

    /**
     * 目标组动态渲染（v8 需求 4 + v8 问题 2）。
     *
     * 结构（自上而下）：
     * 1. **主目标行**（恒在）：已设 → 显示模式（增重/减重/保持/自定义文本），点击进编辑；
     *    未设（问题 2b 删种子后的新用户 / 跳过了引导）→ 值显示 `未设置`(text_3)，
     *    点击开首启引导 [GoalSetupSheet]。**永不出删除位**（`is_primary` 语义）。
     * 2. **次目标行**：按 [GoalSlots.ADDABLE] 顺序（热量 → 体重 → 每周训练 → 睡眠
     *    → 饮水），仅渲染"有生效行"的槽位；可左滑删除（归档）。
     * 3. **空态说明** `还没有次目标`：仅"已设主目标但一个次目标都没有"时出现。
     * 4. **「添加目标」入口**：由 [updateGoalAddEntry] 按剩余可加槽位切换启用/禁用态。
     *
     * 槽位全部 metric 都未启用时**不显示该行** —— 由末尾「添加目标」恢复
     * （归档 ≠ 物理删除，见 [GoalSlots] 头注释）。
     */
    private fun renderGoals(list: List<GoalEntity>, goalStatement: String, customGoalText: String) {
        val byMetric = list.associateBy { it.metric }
        val container = binding.goalContainer
        // 重渲染会销毁旧行视图 → 先收起滑开态，避免 SwipeController 跟踪已 detach 的视图
        swipe.closeAll()
        container.removeAllViews()

        // ── ① 主目标行（恒在，不可删）──
        val primary = byMetric[GoalMetrics.PRIMARY]
        val primaryRow = ItemSwipeRowBinding.inflate(layoutInflater, container, false)
        primaryRow.actDelete.visibility = View.GONE
        primaryRow.swipeRow.label.setText(R.string.setting_primary_goal)
        primaryRow.swipeRow.chevron.visibility = View.VISIBLE
        if (primary == null) {
            primaryRow.swipeRow.value.setText(R.string.value_not_set)
            primaryRow.swipeRow.value.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_3))
        } else {
            primaryRow.swipeRow.value.text = goalValueText(GoalSlots.PRIMARY, listOf(primary), goalStatement)
        }
        primaryRow.swipeRow.root.setOnClickListener {
            if (!swipe.clickAllowed()) return@setOnClickListener
            swipe.closeAll()
            if (primary == null) openGoalSetup() else editGoal(GoalSlots.PRIMARY)
        }
        container.addView(primaryRow.root)

        // ── ② 次目标行（可增 / 删 / 改）──
        var secondaryCount = 0
        GoalSlots.ADDABLE.forEach { slot ->
            // 清单3 R3：kcal 槽位的唯一 UI 归属是「热量摄入」独立栏，目标组不再渲染该行。
            // （ADDABLE 已收窄为 4 项，此处防御性跳过 —— kcal_daily active 数据行仍驱动
            //   首页汇总 / 预警 / 计划，老用户数据行零迁移。）
            if (slot == GoalSlots.KCAL) return@forEach
            val active = slot.metrics.mapNotNull { byMetric[it] }
            if (active.isEmpty()) return@forEach // 全部未启用 → 交给「添加目标」入口
            secondaryCount++

            val row = ItemSwipeRowBinding.inflate(layoutInflater, container, false)
            row.swipeRow.label.setText(goalLabelRes(slot))
            row.swipeRow.value.text = goalValueText(slot, active, goalStatement)
            row.swipeRow.chevron.visibility = View.VISIBLE
            row.swipeItem.setOnTouchListener { v, ev ->
                swipe.onTouch(v, ev)
                false // 不消费：点击 / 滚动照旧
            }
            row.actDelete.setOnClickListener {
                if (!swipe.clickAllowed()) return@setOnClickListener
                swipe.closeAll()
                archiveSlotWithUndo(slot)
            }
            // 整行点击 → 编辑（按 slot 分发到既有编辑逻辑）
            row.swipeRow.root.setOnClickListener {
                if (!swipe.clickAllowed()) return@setOnClickListener
                swipe.closeAll()
                editGoal(slot)
            }
            container.addView(row.root)
        }

        // ── ②b 文本型自定义目标行（清单3 R1）：非空才渲染；点行 = 再次编辑（Q4 裁决）；
        //      左滑清除 = 清 settings 键 + UndoBar 文本快照恢复（第三种撤销模式）。
        //      不占 goals 表（文本型无数值，schema 零变更），只渲染于 UI。
        if (customGoalText.isNotBlank()) {
            secondaryCount++
            val row = ItemSwipeRowBinding.inflate(layoutInflater, container, false)
            row.swipeRow.label.setText(R.string.custom_goal_label)
            row.swipeRow.value.text = customGoalText
            row.swipeRow.chevron.visibility = View.VISIBLE
            row.swipeItem.setOnTouchListener { v, ev ->
                swipe.onTouch(v, ev)
                false // 不消费：点击 / 滚动照旧
            }
            row.actDelete.setOnClickListener {
                if (!swipe.clickAllowed()) return@setOnClickListener
                swipe.closeAll()
                clearCustomGoalWithUndo(customGoalText)
            }
            row.swipeRow.root.setOnClickListener {
                if (!swipe.clickAllowed()) return@setOnClickListener
                swipe.closeAll()
                editCustomGoal()
            }
            container.addView(row.root)
        }

        // ── ③ 空态说明（完全空时由上面的「未设置」主目标行承担，不重复）──
        binding.goalEmptyHint.visibility =
            if (primary != null && secondaryCount == 0) View.VISIBLE else View.GONE

        // ── ④ 「添加目标」入口启用态（G11 数量上限）──
        updateGoalAddEntry(byMetric.keys, customGoalText)

        // ── ⑤ 「热量摄入」数值行（清单3 R3）：与 goals 表 kcal_daily 行同源显示
        //      （kcalTargetOf 同口径：active 行取值，没设过回落 GoalDefaults）。
        val kcalTarget = list.firstOrNull { it.metric == GoalMetrics.KCAL_DAILY }
            ?.targetValue?.toInt()?.takeIf { it > 0 } ?: GoalDefaults.TARGET_KCAL
        binding.rowKcalGoal.value.text = getString(R.string.unit_kcal, kcalTarget)
    }

    /**
     * 「添加目标」入口的两态（v8 问题 2 + G11）。
     *
     * - 仍有未启用槽位 → 强调色可点（唯一显式创建入口）；
     * - 槽位加满（主目标 1 + 次目标 5 = 6）→ label 置灰 `text_3`、右侧 caption12
     *   `已达上限 6 项`、不可点（**不再**"看起来能点、点完才弹 Toast"）。
     *
     * 上限值取 [GoalSlots.ALL].size —— 结构性封顶，不写死数字（仍为 6）。
     *
     * 清单3 Q1 裁决：满员判据按 **UI 可见口径** = 主目标 1 + 4 固定槽位 + 1 自定义 = 6；
     * 老用户 `kcal_daily` active 数据行**不占**次目标槽位（UI 已收编至「热量摄入」栏）。
     */
    private fun updateGoalAddEntry(activeMetrics: Set<String>, customGoalText: String) {
        val pending = GoalSlots.ADDABLE.filter { slot -> slot.metrics.none { it in activeMetrics } }
        val customUsed = customGoalText.isNotBlank()
        val full = pending.isEmpty() && customUsed
        val row = binding.rowGoalAdd
        row.label.setTextColor(
            ContextCompat.getColor(requireContext(), if (full) R.color.text_3 else R.color.accent),
        )
        row.value.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_3))
        row.value.text = if (full) getString(R.string.goal_limit_reached, GoalSlots.ALL.size) else ""
        row.chevron.visibility = if (full) View.GONE else View.VISIBLE
        row.root.isClickable = !full
        row.root.isEnabled = !full
    }

    /**
     * 主目标未设时，从「目标」栏开首启引导（[GoalSetupSheet]）。
     *
     * 落库复用 [MainViewModel.completeGoalSetup]（与记录页首启引导同一段 ensure+写值逻辑，
     * **不复制第二份**）；完成/跳过都会写 `GOAL_SETUP_DONE`。
     */
    private fun openGoalSetup() {
        if (childFragmentManager.isStateSaved) return
        // lambda 元数必须与 [GoalSetupSheet.onDone] 严格一致（三参
        // `(Int?, Double?, String?)`）—— 第三参是「自定义主目标」文本（选挡 3 时非空），
        // 随同交 [MainViewModel.completeGoalSetup] 落 `settings.GOAL_STATEMENT`。
        GoalSetupSheet.newInstance().apply {
            onDone = { modeIndex, weightKg, customText ->
                mainVm.completeGoalSetup(modeIndex, weightKg, customText)
            }
        }.show(childFragmentManager, GoalSetupSheet.TAG)
    }

    /** 槽位 → 行标签资源。 */
    private fun goalLabelRes(slot: GoalSlots.Slot): Int = when (slot) {
        GoalSlots.PRIMARY -> R.string.setting_primary_goal
        GoalSlots.KCAL -> R.string.setting_kcal_goal
        GoalSlots.WEIGHT -> R.string.setting_weight_goal
        GoalSlots.TRAIN -> R.string.setting_train_goal
        GoalSlots.SLEEP -> R.string.setting_sleep_goal
        else -> R.string.setting_water_goal
    }

    /** 槽位当前值文案（口径与旧固定行一致；空值走 [R.string.value_not_set]）。
     *  [goalStatement] 供主目标自定义态展示（mode 3 → 自由文本自述）。 */
    private fun goalValueText(slot: GoalSlots.Slot, active: List<GoalEntity>, goalStatement: String): CharSequence {
        val m = active.associateBy { it.metric }
        return when (slot) {
            GoalSlots.PRIMARY -> {
                val mode = m[GoalMetrics.PRIMARY]?.targetValue?.toInt() ?: SettingsViewModel.GOAL_MODE_GAIN
                when (mode) {
                    SettingsViewModel.GOAL_MODE_LOSS -> getString(R.string.goal_loss)
                    SettingsViewModel.GOAL_MODE_KEEP -> getString(R.string.goal_keep)
                    SettingsViewModel.GOAL_MODE_CUSTOM -> goalStatement.ifBlank { getString(R.string.value_not_set) }
                    else -> getString(R.string.goal_gain)
                }
            }

            GoalSlots.WEIGHT -> {
                val w = m[GoalMetrics.WEIGHT_KG]?.targetValue ?: 0.0
                if (w > 0) getString(R.string.unit_kg, trim(w)) else getString(R.string.value_not_set)
            }

            GoalSlots.TRAIN -> {
                val s = m[GoalMetrics.SESSIONS_PER_WEEK]?.targetValue?.toInt() ?: 0
                val min = m[GoalMetrics.TRAIN_MINUTES_PER_WEEK]?.targetValue?.toInt() ?: 0
                getString(R.string.unit_train_goal, s, min)
            }

            GoalSlots.SLEEP -> getString(R.string.unit_hours, trim(m[GoalMetrics.SLEEP_H]?.targetValue ?: 0.0))

            GoalSlots.KCAL -> getString(
                R.string.unit_kcal,
                m[GoalMetrics.KCAL_DAILY]?.targetValue?.toInt() ?: GoalDefaults.TARGET_KCAL,
            )

            else -> getString(R.string.unit_ml, m[GoalMetrics.WATER_ML]?.targetValue?.toInt() ?: 0)
        }
    }

    /** 整行点击 → 按槽位分发到既有编辑逻辑。 */
    private fun editGoal(slot: GoalSlots.Slot) {
        when (slot) {
            GoalSlots.PRIMARY -> choosePrimaryGoal()
            GoalSlots.KCAL -> editGoalKcal()
            GoalSlots.WEIGHT -> editGoalWeight()
            GoalSlots.TRAIN -> editGoalTrain()
            GoalSlots.SLEEP -> editGoalSleep()
            else -> editGoalWater()
        }
    }

    /**
     * 左滑删除一个目标槽位（归档 + 5 秒撤销）。
     * 「撤销」= `ensureSlotActive`（归档行按原值恢复，不新增行）。
     */
    private fun archiveSlotWithUndo(slot: GoalSlots.Slot) {
        vm.archiveSlot(slot)
        UndoBar.bind(
            container = binding.undoBar,
            leftText = binding.undoLeft,
            action = binding.undoAction,
            text = getString(R.string.undo_deleted, getString(goalLabelRes(slot))),
            announce = null,
            onUndo = { vm.ensureSlotActive(slot) },
        )
    }

    /** 打开「添加目标」弹窗（列出当前未启用的槽位；选中即恢复 / 补齐）。 */
    private fun showAddGoal() {
        // 已启用 = 该槽位至少有一个 active metric。
        // 可选集合 = GoalSlots.ADDABLE（清单3：不含主目标与 kcal）+ 文本型自定义入口。
        val activeMetrics = vm.goals.value.map { it.metric }.toSet()
        val pending = GoalSlots.ADDABLE.filter { slot -> slot.metrics.none { it in activeMetrics } }
        val customUsed = vm.values.value.customGoalText.isNotBlank()
        // 加满（4 固定全启用 且 自定义已用）时入口已被 updateGoalAddEntry 置灰禁用，
        // 这里只做防御性早退（不再弹 Toast）。
        if (pending.isEmpty() && customUsed) return
        val keys = pending.map { it.key }.toMutableList()
        val labels = pending.map { getString(goalLabelRes(it)) }.toMutableList()
        if (!customUsed) {
            // 清单3 R1：文本型自定义目标入口（key 约定 "custom"，仅当未使用时追加）。
            // AddGoalSheet 自身不读库、槽位由宿主经 arguments 传入 → 零改动。
            keys.add(ADD_GOAL_KEY_CUSTOM)
            labels.add(getString(R.string.add_goal_custom))
        }
        val sheet = AddGoalSheet.newInstance(keys = keys, labels = labels)
        sheet.onPick = { key ->
            if (key == ADD_GOAL_KEY_CUSTOM) {
                editCustomGoal()
            } else {
                GoalSlots.byKey(key)?.let { vm.ensureSlotActive(it) }
            }
        }
        sheet.show(childFragmentManager, AddGoalSheet.TAG)
    }

    /** 当前目标值（编辑弹窗回显用）。 */
    private fun currentTarget(metric: String, fallback: Double): Double =
        vm.goals.value.firstOrNull { it.metric == metric }?.targetValue ?: fallback

    private fun choosePrimaryGoal() {
        val labels = arrayOf(
            getString(R.string.goal_gain),
            getString(R.string.goal_loss),
            getString(R.string.goal_keep),
            getString(R.string.goal_custom),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_primary_goal)
            .setItems(labels) { _, which ->
                if (which == SettingsViewModel.GOAL_MODE_CUSTOM) {
                    chooseCustomGoalText()
                } else {
                    vm.setPrimaryGoal(which)
                }
            }
            .setNegativeButton(R.string.cancel, null as DialogInterface.OnClickListener?)
            .show()
    }

    /**
     * 自定义主目标文本输入（主目标 = 第 4 态）：确认后 mode=3 + 文本落
     * [SettingsKeys.GOAL_STATEMENT]（AI prompt / 首页展示同源）。空白输入不落库。
     */
    private fun chooseCustomGoalText() {
        viewLifecycleOwner.lifecycleScope.launch {
            val current = vm.raw(SettingsKeys.GOAL_STATEMENT).orEmpty()
            showFieldDialog(
                R.string.goal_custom,
                listOf(
                    FieldSheet.FieldSpec(
                        R.string.setting_goal_statement,
                        current,
                        InputType.TYPE_CLASS_TEXT,
                        GOAL_STATEMENT_MAX,
                    ),
                ),
            ) { raw ->
                val text = raw.firstOrNull().orEmpty().trim()
                if (text.isNotEmpty()) vm.setPrimaryGoalCustom(text)
            }
        }
    }

    /**
     * 文本型自定义次目标输入（清单3 R1）：复用主目标自定义态的完整先例
     * [chooseCustomGoalText] —— FieldSheet 单行文本、上限 [GOAL_STATEMENT_MAX]（80 字）、
     * **空白输入不落库**（清空请走行上左滑清除，有撤销兜底）。
     * 确认后落 [SettingsKeys.CUSTOM_GOAL_TEXT]（独立新键，AI 口径零触碰）。
     */
    private fun editCustomGoal() {
        viewLifecycleOwner.lifecycleScope.launch {
            val current = vm.raw(SettingsKeys.CUSTOM_GOAL_TEXT).orEmpty()
            showFieldDialog(
                R.string.add_goal_custom,
                listOf(
                    FieldSheet.FieldSpec(
                        R.string.custom_goal_label,
                        current,
                        InputType.TYPE_CLASS_TEXT,
                        GOAL_STATEMENT_MAX,
                    ),
                ),
            ) { raw ->
                val text = raw.firstOrNull().orEmpty().trim()
                if (text.isNotEmpty()) vm.setCustomGoalText(text)
            }
        }
    }

    /**
     * 左滑清除自定义目标（清单3 R1）：清 settings 键（文本型无归档态）+ 5 秒撤销。
     * 撤销 = **文本快照恢复**（[SettingsViewModel.setCustomGoalText] 回写 prevText）——
     * 与目标槽位的 `ensureSlotActive`、提醒的整条快照并列的第三种撤销模式；
     * `UndoBar` 组件本身零改动。
     */
    private fun clearCustomGoalWithUndo(prevText: String) {
        vm.setCustomGoalText("")
        UndoBar.bind(
            container = binding.undoBar,
            leftText = binding.undoLeft,
            action = binding.undoAction,
            text = getString(R.string.undo_deleted, getString(R.string.custom_goal_label)),
            announce = null,
            onUndo = { vm.setCustomGoalText(prevText) },
        )
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

    /**
     * 热量目标（v8 问题 2a）：编辑入口从「个人信息页」迁至本栏。
     *
     * 值域 [KCAL_MIN]–[KCAL_MAX] 整数（规范 ②·边界）：越界输入**忽略**（不写库、不猜）。
     * 值落 `goals` 表 `metric = kcal_daily`，首页汇总 / 预警 / 计划生成同源读取。
     */
    private fun editGoalKcal() {
        val initial = currentTarget(GoalMetrics.KCAL_DAILY, GoalDefaults.TARGET_KCAL.toDouble())
            .toInt().toString()
        showFieldDialog(
            R.string.setting_kcal_goal,
            listOf(FieldSheet.FieldSpec(R.string.setting_kcal_goal, initial, NUMBER_INT)),
        ) { raw ->
            raw.firstOrNull()?.toIntOrNull()
                ?.takeIf { it in KCAL_MIN..KCAL_MAX }
                ?.let { vm.setGoalTarget(GoalMetrics.KCAL_DAILY, it.toDouble()) }
        }
    }

    // ── 提醒渲染与编辑 ────────────────────────────────────────────

    /**
     * 提醒组动态渲染（v8 需求 4）：每条提醒 inflate 一行 `item_swipe_row.xml`，
     * 支持左滑删除（删除后 5 秒可撤销，撤销 = 原样插回）。
     */
    private fun renderReminders(list: List<ReminderEntity>) {
        val container = binding.reminderContainer
        // 先收起滑开态（旧行视图即将被销毁）
        swipe.closeAll()
        container.removeAllViews()
        val now = System.currentTimeMillis()
        list.forEach { reminder ->
            val row = ItemSwipeRowBinding.inflate(layoutInflater, container, false)
            row.swipeRow.label.text = reminder.name
            row.swipeRow.chevron.visibility = View.VISIBLE
            row.swipeRow.value.text = getString(R.string.status_reminder_next, dateLabel(reminder.nextDueAt))
            // 到期或临期（≤7 天）：右侧日期用 accent（规范 9.7 ③）
            val due = reminder.nextDueAt - now <= 7L * HealixDate.DAY_MS
            row.swipeRow.value.setTextColor(
                ContextCompat.getColor(requireContext(), if (due) R.color.accent else R.color.text_2),
            )
            row.swipeItem.setOnTouchListener { v, ev ->
                swipe.onTouch(v, ev)
                false // 不消费：点击 / 滚动照旧
            }
            row.actDelete.setOnClickListener {
                if (!swipe.clickAllowed()) return@setOnClickListener
                swipe.closeAll()
                deleteReminderWithUndo(reminder)
            }
            row.swipeRow.root.setOnClickListener {
                if (!swipe.clickAllowed()) return@setOnClickListener
                swipe.closeAll()
                reminderActions(reminder)
            }
            container.addView(row.root)
        }
    }

    /**
     * 左滑删除一条提醒 + 5 秒撤销。
     * 提醒是**物理删除**，撤销需保留整条快照原样插回（[SettingsViewModel.restoreReminder]）。
     */
    private fun deleteReminderWithUndo(reminder: ReminderEntity) {
        vm.deleteReminder(reminder.id)
        UndoBar.bind(
            container = binding.undoBar,
            leftText = binding.undoLeft,
            action = binding.undoAction,
            text = getString(R.string.undo_deleted, reminder.name),
            announce = null,
            onUndo = { vm.restoreReminder(reminder) },
        )
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
        AlertDialog.Builder(requireContext())
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
     *
     * ⚠️ Fragment 化后容器用 **childFragmentManager** —— 弹窗属于本二级页，
     * 必须在二级页 pop 时一起销毁（挂宿主 supportFragmentManager 会活过页面的生命周期）。
     */
    private fun showFieldDialog(
        titleRes: Int,
        specs: List<FieldSheet.FieldSpec>,
        onOk: (List<String>) -> Unit,
    ) {
        val sheet = FieldSheet.newInstance(titleRes, specs)
        sheet.onResult = onOk
        sheet.show(childFragmentManager, FieldSheet.TAG)
    }

    /** 毫秒时间戳 → `yyyy-MM-dd`（本地时区）。 */
    private fun dateLabel(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    /**
     * [target] 在 [scroll] 坐标系里的纵向偏移：沿 parent 链累加 `top`，
     * 走到 [scroll] 本身为止（两层：ScrollView → 内容 LinearLayout → 各分组）。
     * 链断裂（parent 不是 View）时返回当前累计值，调用端以 `> 0` 兜底。
     */
    private fun offsetWithin(scroll: android.widget.ScrollView, target: View): Int {
        var y = 0
        var v: View = target
        while (v !== scroll) {
            y += v.top
            val p = v.parent as? View ?: break
            v = p
        }
        return y
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
        binding.testResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_2))
        binding.testResult.text = getString(R.string.apply_applying)

        vm.applyProvider {
            _binding?.btnApply?.isEnabled = true
            _binding?.btnTest?.isEnabled = true
        }
    }

    // ── 测试连通性 ────────────────────────────────────────────────

    private fun runConnectivityTest() {
        binding.btnTest.isEnabled = false
        binding.testResult.visibility = View.VISIBLE
        binding.testResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_2))
        binding.testResult.text = getString(R.string.setting_testing)

        vm.testConnectivity {
            _binding?.btnTest?.isEnabled = true
        }
    }

    // ── 编辑对话框（就地修改，不新开页面） ────────────────────────
    //
    // ⚠️ vm.raw(key) 是 suspend（要读 Room）。不能在 EditText.apply { } 里
    //    直接调用 —— 那是普通 lambda，不是协程。必须在 lifecycleScope 里
    //    先 await 拿到值，再构造对话框。下面三个函数统一按这个模式写。

    private fun editText(key: String, labelRes: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            val current = vm.raw(key).orEmpty()
            val input = EditText(requireContext()).apply {
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
        viewLifecycleOwner.lifecycleScope.launch {
            val current = vm.raw(key).orEmpty()
            val input = EditText(requireContext()).apply {
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
        AlertDialog.Builder(requireContext())
            .setTitle(labelRes)
            .setMessage(getString(R.string.setting_value_out_of_range, range.first, range.last))
            .setPositiveButton(R.string.confirm, null as DialogInterface.OnClickListener?)
            .show()
    }

    private fun editDecimal(key: String, labelRes: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            val current = vm.raw(key).orEmpty()
            val input = EditText(requireContext()).apply {
                setText(current)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setSelection(text.length)
            }
            showDialog(labelRes, input) { vm.put(key, input.text.toString().trim()) }
        }
    }

    /** API Key 编辑：输入框掩码，不回显已存的 key（无法回显 —— 且刻意不提供读取原值的能力）。 */
    private fun editApiKey() {
        val input = EditText(requireContext()).apply {
            hint = "粘贴你的 API Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        // P1-8：明文 / 掩码切换（文字按钮：13sp accent、48dp 热区、无边框 ripple）
        val toggle = TextView(requireContext()).apply {
            text = getString(R.string.setting_show)
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.accent))
            gravity = Gravity.CENTER
            minHeight = resources.getDimensionPixelSize(R.dimen.touch_min)
            isClickable = true
            isFocusable = true
            // ?attr/selectableItemBackgroundBorderless 在代码里要先解析成 drawable 资源 id
            val outValue = TypedValue()
            requireContext().theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, outValue, true,
            )
            background = ContextCompat.getDrawable(requireContext(), outValue.resourceId)
            setOnClickListener {
                // 明文 ⇄ 密文。切换后光标挪到尾部：改 inputType 会重置光标与字体，
                // 且会触发 IME 重建，不补这一手光标会跳回行首。
                val toPlain = input.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
                input.inputType = if (toPlain) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                input.setSelection(input.text.length)
                text = getString(if (toPlain) R.string.setting_hide else R.string.setting_show)
            }
        }
        showDialog(R.string.api_key, input, trailing = toggle) {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) vm.saveApiKey(text)
        }
    }

    private fun showDialog(
        labelRes: Int,
        input: EditText,
        trailing: View? = null,
        onOk: () -> Unit,
    ) {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            // 可选尾随控件（API Key 的「显示 / 隐藏」）。setView 只能收一个 View，
            // 所以必须塞进同一个 container，不能另开第二个 setView。
            if (trailing != null) addView(trailing)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(labelRes)
            .setView(container)
            // ⚠️ AlertDialog.Builder 没有「只传文案」的单参 setPositiveButton。
            //    必须显式给 OnClickListener；不关心点击时传 null 会被 Kotlin
            //    判为「无法推断用哪个重载」，要写成带类型的 lambda。
            .setPositiveButton(R.string.confirm) { _: DialogInterface, _: Int ->
                onOk()
            }
            .setNegativeButton(R.string.cancel, null as DialogInterface.OnClickListener?)
            .show()
    }

    private fun chooseProvider() {
        // ⚠️ setItems 只接受 Array<CharSequence>，不接受 List<String>。
        //    vm.providerNames() 返回 List<String>，直接传会报
        //    「None of the following candidates is applicable」，
        //    并连锁导致后续 setNegativeButton 也解析失败（返回类型未定）。
        val presets = vm.providerNames().toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.provider)
            .setItems(presets) { _, which -> vm.selectProvider(which) }
            .setNegativeButton(R.string.cancel, null as DialogInterface.OnClickListener?)
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

        /**
         * 跳转参数：true = 进入后自动滚动到「目标」栏。
         * 两个入口共用本参数：[RecordFragment] 主目标行的「去设置 ›」、
         * [PersonalInfoFragment] 的「我的目标」。
         */
        const val ARG_FOCUS_GOAL = "focus_goal"

        /** 主目标自定义文本上限（与 GoalSetupSheet 自定义输入一致，80 字）。 */
        private const val GOAL_STATEMENT_MAX = 80

        /**
         * 「添加目标」弹层里文本型自定义目标的约定 key（清单3 R1）。
         * 仅经 arguments 传给 [AddGoalSheet] 后回调分发用，**非持久化键**（不落任何表）。
         */
        private const val ADD_GOAL_KEY_CUSTOM = "custom"

        /**
         * 标准构造：默认不带参数；[focusGoal] = true 时携带 [ARG_FOCUS_GOAL]
         * （进入后滚到「目标」栏）。⚠️ 经 `arguments` 传参（setArguments），
         * 进程重建可恢复；禁止无参构造后靠字段传参。
         */
        fun newInstance(focusGoal: Boolean = false): SettingsFragment =
            SettingsFragment().apply {
                if (focusGoal) {
                    arguments = Bundle().apply { putBoolean(ARG_FOCUS_GOAL, true) }
                }
            }

        // ⚠️ 键名一律从 SettingsKeys 取 —— 那里是唯一事实来源。
        //    2026-10-03 修过一次键名分裂 bug（见 SettingsKeys 头注释），
        //    此处保留 `KEY_*` 前缀是为了不改动调用点，
        //    但值的来源必须收敛到 SettingsKeys。
        const val KEY_BASE_URL = SettingsKeys.BASE_URL
        const val KEY_MODEL = SettingsKeys.MODEL
        const val KEY_RETRY = SettingsKeys.RETRY
        const val KEY_RETRY_DELAY = SettingsKeys.RETRY_DELAY
        const val KEY_DAY_START = SettingsKeys.DAY_START

        // ⚠️ v8 问题 2a / 需求 6 起，以下转发常量已随各自 UI 入口一并删除（原入口职责已迁走）：
        //    KEY_TARGET_KCAL → 「目标」栏的 kcal 行（`goals.metric = kcal_daily`，问题 2a）；
        //    KEY_HEIGHT / KEY_WEIGHT / KEY_AGE / KEY_ACTIVITY / KEY_BACKGROUND / KEY_PROVIDER
        //    → 个人信息页（本页已不再承载任何目标数值或档案字段）。
        //    键名本身仍在 [SettingsKeys]（唯一事实来源），消费方（`InputSanitizer` /
        //    `HealthAggregator` / `EventRepository`）继续直接引用，不受影响。

        // ⚠️ v8 需求 6：原「隐私」组（`KEY_HIDE_KCAL` / `KEY_HIDE_WEIGHT`）UI 入口已移除，
        //    两条转发常量随之删除。`SettingsKeys.HIDE_KCAL` / `HIDE_WEIGHT` 及全部消费方保留。

        private const val NUMBER_INT = InputType.TYPE_CLASS_NUMBER
        private const val NUMBER_DECIMAL =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        /** 热量目标值域（v8 问题 2a，规范 ②·边界）：越界输入忽略，不写库。 */
        private const val KCAL_MIN = 100
        private const val KCAL_MAX = 9999
    }
}
