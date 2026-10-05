package com.healix.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.FragmentMineBinding
import com.healix.app.db.SettingsKeys
import com.healix.app.repo.ResourceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「我的」页（设计规范 11.1，v6 · 3 Tab）。v8：由 `MinePage.kt`（以 `(activity, root)` 手工
 * 绑定的辅助类）迁为**常驻 Tab Fragment** —— 三 Tab 从此同为 Fragment，导航范式唯一。
 *
 * 信息架构：概览（状态详情，含摘要副行）/ 数据（资源 · 预设 · 知识库 · 个人信息 · 导出备份）/
 * 应用（设置 · 调试 · 通知栏录入）。
 *
 * ⚠️ 入口迁移（11.1 取代 10.1）：知识库唯一入口从设置页迁至此页。
 *
 * 迁移要点（§A.6 第 5 条）：原实现在 `(activity as LifecycleOwner).lifecycleScope` 里
 * **真实读库**（`ResourceStore.filledCount` / `weightRowsInRange`）—— 迁 Fragment 时
 * 统一改为 `viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO)` 读、回主线程只做赋值。
 * 且 `LocalDate.now()` 当日键的违规一并修正（日界线纪律：日键必经 `dayStartHourOf` + `dayKeyOf`）。
 */
class MineFragment : Fragment() {

    private var _binding: FragmentMineBinding? = null
    private val binding get() = _binding!!

    private val container: HealixApp by lazy { HealixApp.from(requireContext()) }

    /** 与 [RecordFragment] 共享的 Activity 作用域 VM —— 状态副行读同一个 `homeStatus`。 */
    private val vm: MainViewModel by lazy {
        ViewModelProvider(requireActivity())[MainViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindRows()
        observeEntryValues()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun bindRows() {
        // ── 概览：状态详情（副行 = 首页状态行同源摘要）──
        binding.rowStatusDetail.label.setText(R.string.row_status_detail)
        binding.rowStatusDetail.value.text = ""
        binding.rowStatusDetail.chevron.visibility = View.VISIBLE
        binding.rowStatusDetail.root.setOnClickListener {
            NavHost.open(
                requireContext(),
                StatusDetailFragment.newInstance(null),
                NavHost.PAGE_STATUS_DETAIL,
            )
            vm.acknowledgeSignals()
        }
        binding.rowStatusDetail.root.bindPressScale()

        // ── 数据：资源清单（手头食物/药物/运动条件，AI 自动读取）──
        binding.rowResources.label.setText(R.string.resources_title)
        binding.rowResources.chevron.visibility = View.VISIBLE
        binding.rowResources.root.setOnClickListener {
            NavHost.open(requireContext(), ResourceFragment(), NavHost.PAGE_RESOURCES)
        }
        binding.rowResources.root.bindPressScale()

        // ── 数据：预设管理（聊天页快捷条的唯一创建/编辑/删除入口）──
        binding.rowPresets.label.setText(R.string.preset_manage_title)
        binding.rowPresets.chevron.visibility = View.VISIBLE
        binding.rowPresets.root.setOnClickListener {
            NavHost.open(requireContext(), PresetManageFragment(), NavHost.PAGE_PRESETS)
        }
        binding.rowPresets.root.bindPressScale()

        // ── 数据：知识库（唯一入口迁移至此，11.1）──
        binding.rowKnowledge.label.setText(R.string.knowledge_title)
        binding.rowKnowledge.chevron.visibility = View.VISIBLE
        binding.rowKnowledge.root.setOnClickListener {
            NavHost.open(requireContext(), KnowledgeBaseFragment(), NavHost.PAGE_KNOWLEDGE)
        }
        binding.rowKnowledge.root.bindPressScale()

        // ── 数据：个人信息（P1：设置页「个人 / 我的情况」两组迁至此）──
        binding.rowPersonalInfo.label.setText(R.string.personal_info_title)
        binding.rowPersonalInfo.chevron.visibility = View.VISIBLE
        binding.rowPersonalInfo.root.setOnClickListener {
            NavHost.open(requireContext(), PersonalInfoFragment(), NavHost.PAGE_PERSONAL_INFO)
        }
        binding.rowPersonalInfo.root.bindPressScale()

        // ── 数据：导出备份（与设置页同一 ExportWriter 管道，JSON / SAF）──
        binding.rowExport.label.setText(R.string.export_backup)
        binding.rowExport.value.text = "JSON"
        binding.rowExport.chevron.visibility = View.VISIBLE
        binding.rowExport.root.setOnClickListener { exportBackup() }
        binding.rowExport.root.bindPressScale()

        // ── 应用：设置 / 调试 / 通知栏录入 ──
        binding.rowSettings.label.setText(R.string.settings)
        binding.rowSettings.chevron.visibility = View.VISIBLE
        binding.rowSettings.root.setOnClickListener {
            NavHost.open(requireContext(), SettingsFragment(), NavHost.PAGE_SETTINGS)
        }
        binding.rowSettings.root.bindPressScale()

        binding.rowDebug.label.setText(R.string.group_debug)
        binding.rowDebug.chevron.visibility = View.VISIBLE
        binding.rowDebug.root.setOnClickListener {
            NavHost.open(requireContext(), DebugFragment(), NavHost.PAGE_DEBUG)
        }
        binding.rowDebug.root.bindPressScale()

        // 通知栏录入：跳系统通知设置（常驻通知由 QuickInputService 托管，
        // App 侧无独立配置页 —— 引导到渠道开关，而非发明新页）。
        binding.rowNotify.label.setText(R.string.row_notify_input)
        binding.rowNotify.chevron.visibility = View.VISIBLE
        binding.rowNotify.root.setOnClickListener { openNotificationSettings() }
        binding.rowNotify.root.bindPressScale()
    }

    private fun observeEntryValues() {
        observeStatusSubtitle()
        observeKnowledgeCount()
        observeResourceCount()
        observePresetsCount()
        observePersonalInfo()
    }

    /** 状态详情副行：与首页状态行同源（`HomeStatus` 两态直接复用文案）。 */
    private fun observeStatusSubtitle() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.homeStatus.collect { status ->
                    binding.rowStatusDetail.value.text = when (status) {
                        is HomeStatus.Summary -> status.text
                        is HomeStatus.Signal -> status.text
                        HomeStatus.Empty -> getString(R.string.status_none)
                    }
                }
            }
        }
    }

    /**
     * 知识库文档数：Room Flow —— 上传 +1 / 删除 -1 自动刷新；
     * 0 份显示「未添加」（`knowledge_entry_none`，中性 text_3）。
     */
    private fun observeKnowledgeCount() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.database.knowledgeDocDao().observeCount().collect { count ->
                    binding.rowKnowledge.value.text = if (count > 0) {
                        getString(R.string.knowledge_entry_count, count)
                    } else {
                        getString(R.string.knowledge_entry_none)
                    }
                    binding.rowKnowledge.value.setTextColor(
                        container.getColor(if (count > 0) R.color.text_2 else R.color.text_3),
                    )
                }
            }
        }
    }

    /**
     * 资源清单入口值：三类（食物/药物/运动条件）里已填几类。
     * `repeatOnLifecycle(STARTED)` 每次回到「我的」页重读一次 ——
     * 从资源清单页返回后计数即时刷新（settings 无 Flow 观察者，用重读代替）。
     */
    private fun observeResourceCount() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val filled = withContext(Dispatchers.IO) {
                    ResourceStore.filledCount(container.database)
                }
                binding.rowResources.value.text = if (filled > 0) {
                    getString(R.string.resources_entry_count, filled)
                } else {
                    getString(R.string.resources_entry_none)
                }
                binding.rowResources.value.setTextColor(
                    container.getColor(if (filled > 0) R.color.text_2 else R.color.text_3),
                )
            }
        }
    }

    /**
     * 预设管理入口值：预设条数（Room Flow，管理页增删后即时刷新）。
     * 0 条显示「未创建」（text_3，与知识库 0 份同一空态口径）。
     */
    private fun observePresetsCount() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.database.presetDao().observeCount().collect { count ->
                    binding.rowPresets.value.text = if (count > 0) {
                        getString(R.string.presets_entry_count, count)
                    } else {
                        getString(R.string.presets_entry_none)
                    }
                    binding.rowPresets.value.setTextColor(
                        container.getColor(if (count > 0) R.color.text_2 else R.color.text_3),
                    )
                }
            }
        }
    }

    /**
     * 个人信息入口值（P1）：身高 / 当前体重的组合副行。
     *
     * 走 `repeatOnLifecycle(STARTED)` 重读 —— settings 无 Flow，从个人信息页返回后
     * 副行即时刷新（与资源清单入口同一模式）。体重取最近一条 `events(type=body)`，
     * 口径与个人信息页「当前体重」一致。
     *
     * ⚠️ 日界线纪律：区间端点用 `dayKeyOf(now, dayStartHourOf(...))` 算出的 day_key，
     * **不用 `LocalDate.now()` 当日键**（凌晨窗口内会与列表 / 日期标签口径打架）。
     */
    private fun observePersonalInfo() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val db = container.database
                val (height, weight) = withContext(Dispatchers.IO) {
                    val h = db.settingsDao().get(SettingsKeys.HEIGHT)?.toIntOrNull() ?: 0
                    val dayStart = com.healix.app.parse.dayStartHourOf(
                        db.settingsDao().get(SettingsKeys.DAY_START),
                    )
                    val todayKey = com.healix.app.parse.dayKeyOf(System.currentTimeMillis(), dayStart)
                    val from = runCatching {
                        java.time.LocalDate.parse(todayKey).minusDays(365).toString()
                    }.getOrNull()
                    val w = if (from == null) {
                        0.0
                    } else {
                        runCatching {
                            db.eventDao().weightRowsInRange(from, todayKey).lastOrNull()?.weightKg
                                ?: 0.0
                        }.getOrDefault(0.0)
                    }
                    h to w
                }
                val hasValue = height > 0 || weight > 0.0
                binding.rowPersonalInfo.value.text = when {
                    height > 0 && weight > 0.0 ->
                        getString(R.string.personal_info_entry_both, height, trimWeight(weight))
                    height > 0 ->
                        getString(R.string.personal_info_entry_height, height)
                    weight > 0.0 ->
                        getString(R.string.personal_info_entry_weight, trimWeight(weight))
                    else -> getString(R.string.personal_info_entry_none)
                }
                binding.rowPersonalInfo.value.setTextColor(
                    container.getColor(if (hasValue) R.color.text_2 else R.color.text_3),
                )
            }
        }
    }

    private fun trimWeight(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun exportBackup() {
        // buildJson 内部是 runBlocking 的同步 DB 读 —— 放 IO 线程，不卡主线程；
        // SAF 选择器必须在主线程发起（回传由宿主 MainActivity.onActivityResult 转发）。
        viewLifecycleOwner.lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) { ExportWriter.buildJson(requireContext()) }
            ExportWriter.launchCreateDocument(requireActivity(), json)
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, HealixApp.CHANNEL_ID_QUICK_INPUT)
        }
        runCatching { requireContext().startActivity(intent) }
            .onFailure {
                // 个别 ROM 不支持渠道设置页 → 退回 App 总通知设置
                requireContext().startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName),
                )
            }
    }
}
