package com.healix.app.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.repo.ResourceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「我的」页（设计规范 11.1，v6 · 3 Tab）。
 *
 * 信息架构：概览（状态详情，含摘要副行）/ 数据（知识库 · 导出备份）/
 * 应用（设置 · 调试 · 通知栏录入）。
 *
 * ⚠️ 入口迁移（11.1 取代 10.1）：知识库唯一入口从设置页迁至此页。
 *
 * 载体：View 容器（view_mine_page include 进 activity_main），不是独立 Activity ——
 * Tab 平级切换在同一 Activity 内做，in_tab 动画可靠重播、无栈深代价。
 */
internal class MinePage(
    private val activity: Activity,
    private val root: View,
) {

    private val rowStatusDetail = root.findViewById<View>(R.id.rowStatusDetail)
    private val rowResources = root.findViewById<View>(R.id.rowResources)
    private val rowPresets = root.findViewById<View>(R.id.rowPresets)
    private val rowKnowledge = root.findViewById<View>(R.id.rowKnowledge)
    private val rowPersonalInfo = root.findViewById<View>(R.id.rowPersonalInfo)
    private val rowExport = root.findViewById<View>(R.id.rowExport)
    private val rowSettings = root.findViewById<View>(R.id.rowSettings)
    private val rowDebug = root.findViewById<View>(R.id.rowDebug)
    private val rowNotify = root.findViewById<View>(R.id.rowNotify)

    private val container: HealixApp by lazy { HealixApp.from(activity) }

    fun bind(onOpenStatus: () -> Unit) {
        // ── 概览：状态详情（副行 = 首页状态行同源摘要，见 bindStatus）──
        rowStatusDetail.findViewById<TextView>(R.id.label).setText(R.string.row_status_detail)
        rowStatusDetail.findViewById<TextView>(R.id.value).text = ""
        rowStatusDetail.findViewById<View>(R.id.chevron).visibility = View.VISIBLE
        rowStatusDetail.setOnClickListener {
            NavHost.open(activity, StatusDetailFragment.newInstance(null), NavHost.PAGE_STATUS_DETAIL)
            onOpenStatus()
        }
        rowStatusDetail.bindPressScale()

        // ── 数据：资源清单（手头食物/药物/运动条件，AI 自动读取）──
        rowResources.findViewById<TextView>(R.id.label).setText(R.string.resources_title)
        rowResources.findViewById<View>(R.id.chevron).visibility = View.VISIBLE
        rowResources.setOnClickListener {
            NavHost.open(activity, ResourceFragment(), NavHost.PAGE_RESOURCES)
        }
        rowResources.bindPressScale()

        // ── 数据：预设管理（聊天页快捷条的唯一创建/编辑/删除入口）──
        rowPresets.findViewById<TextView>(R.id.label).setText(R.string.preset_manage_title)
        rowPresets.findViewById<View>(R.id.chevron).visibility = View.VISIBLE
        rowPresets.setOnClickListener {
            NavHost.open(activity, PresetManageFragment(), NavHost.PAGE_PRESETS)
        }
        rowPresets.bindPressScale()

        // ── 数据：知识库（唯一入口迁移至此，11.1）──
        rowKnowledge.findViewById<TextView>(R.id.label).setText(R.string.knowledge_title)
        rowKnowledge.findViewById<View>(R.id.chevron).visibility = View.VISIBLE
        rowKnowledge.setOnClickListener {
            NavHost.open(activity, KnowledgeBaseFragment(), NavHost.PAGE_KNOWLEDGE)
        }
        rowKnowledge.bindPressScale()

        // ── 数据：个人信息（P1：设置页「个人 / 我的情况」两组迁至此）──
        // 体重入口写 events(type=body)，其余画像/身高/年龄/活动/卡路里读 settings 表
        // （键名值域零改动）；副行 = 身高 / 当前体重的组合摘要（见 observePersonalInfo）。
        rowPersonalInfo.findViewById<TextView>(R.id.label).setText(R.string.personal_info_title)
        rowPersonalInfo.findViewById<View>(R.id.chevron).visibility = View.VISIBLE
        rowPersonalInfo.setOnClickListener {
            NavHost.open(activity, PersonalInfoFragment(), NavHost.PAGE_PERSONAL_INFO)
        }
        rowPersonalInfo.bindPressScale()

        // ── 数据：导出备份（与设置页同一 ExportWriter 管道，JSON / SAF）──
        rowExport.findViewById<TextView>(R.id.label).setText(R.string.export_backup)
        rowExport.findViewById<TextView>(R.id.value).text = "JSON"
        rowExport.findViewById<View>(R.id.chevron).visibility = View.VISIBLE
        rowExport.setOnClickListener { exportBackup() }
        rowExport.bindPressScale()

        // ── 应用：设置 / 调试 / 通知栏录入 ──
        rowSettings.findViewById<TextView>(R.id.label).setText(R.string.settings)
        rowSettings.findViewById<View>(R.id.chevron).visibility = View.VISIBLE
        rowSettings.setOnClickListener {
            NavHost.open(activity, SettingsFragment(), NavHost.PAGE_SETTINGS)
        }
        rowSettings.bindPressScale()

        rowDebug.findViewById<TextView>(R.id.label).setText(R.string.group_debug)
        rowDebug.findViewById<View>(R.id.chevron).visibility = View.VISIBLE
        rowDebug.setOnClickListener {
            NavHost.open(activity, DebugFragment(), NavHost.PAGE_DEBUG)
        }
        rowDebug.bindPressScale()

        // 通知栏录入：跳系统通知设置（常驻通知由 QuickInputService 托管，
        // App 侧无独立配置页 —— 引导到渠道开关，而非发明新页）。
        rowNotify.findViewById<TextView>(R.id.label).setText(R.string.row_notify_input)
        rowNotify.findViewById<View>(R.id.chevron).visibility = View.VISIBLE
        rowNotify.setOnClickListener { openNotificationSettings() }
        rowNotify.bindPressScale()

        observeKnowledgeCount()
        observeResourceCount()
        observePresetsCount()
        observePersonalInfo()
    }

    /**
     * 资源清单入口值：三类（食物/药物/运动条件）里已填几类。
     * repeatOnLifecycle(STARTED) 每次回到「我的」页重读一次 ——
     * 从资源清单页返回后计数即时刷新（settings 无 Flow 观察者，用重读代替）。
     * 0 类显示「未填写」（text_3，与知识库 0 份同一空态口径）。
     */
    private fun observeResourceCount() {
        val value = rowResources.findViewById<TextView>(R.id.value)
        (activity as LifecycleOwner).lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val filled = ResourceStore.filledCount(container.database)
                value.text = if (filled > 0) {
                    activity.getString(R.string.resources_entry_count, filled)
                } else {
                    activity.getString(R.string.resources_entry_none)
                }
                value.setTextColor(
                    container.getColor(if (filled > 0) R.color.text_2 else R.color.text_3),
                )
            }
        }
    }    /** 状态详情副行：与首页状态行同源（HomeStatus 摘要态直接复用文案）。 */
    fun bindStatus(owner: LifecycleOwner, status: HomeStatus) {
        val value = rowStatusDetail.findViewById<TextView>(R.id.value)
        value.text = when (status) {
            is HomeStatus.Summary -> status.text
            is HomeStatus.Signal -> status.text
            HomeStatus.Empty -> activity.getString(R.string.status_none)
        }
    }

    /**
     * V6 断言：知识库文档数在「我的」页实时同步。
     * observeCount() 是 Room Flow —— 上传 +1 / 删除 -1 自动刷新；
     * 0 份时显示「未添加」（knowledge_entry_none，中性 text_3）。
     */
    private fun observeKnowledgeCount() {
        val value = rowKnowledge.findViewById<TextView>(R.id.value)
        (activity as LifecycleOwner).lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    container.database.knowledgeDocDao().observeCount().collect { count ->
                        value.text = if (count > 0) {
                            activity.getString(R.string.knowledge_entry_count, count)
                        } else {
                            activity.getString(R.string.knowledge_entry_none)
                        }
                        value.setTextColor(
                            container.getColor(
                                if (count > 0) R.color.text_2 else R.color.text_3,
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * 预设管理入口值：预设条数（Room Flow，管理页增删后即时刷新）。
     * 0 条显示「未创建」（text_3，与知识库 0 份同一空态口径）。
     */
    private fun observePresetsCount() {
        val value = rowPresets.findViewById<TextView>(R.id.value)
        (activity as LifecycleOwner).lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.database.presetDao().observeCount().collect { count ->
                    value.text = if (count > 0) {
                        activity.getString(R.string.presets_entry_count, count)
                    } else {
                        activity.getString(R.string.presets_entry_none)
                    }
                    value.setTextColor(
                        container.getColor(
                            if (count > 0) R.color.text_2 else R.color.text_3,
                        ),
                    )
                }
            }
        }
    }

    /**
     * 个人信息入口值（P1）：身高 / 当前体重的组合副行。
     *
     * 走 `repeatOnLifecycle(STARTED)` 重读 —— settings 无 Flow，从个人信息页返回后
     * 副行即时刷新（与资源清单入口同一模式，见 [observeResourceCount]）。
     * 体重取最近一条 events(type=body)，口径与个人信息页「当前体重」一致。
     */
    private fun observePersonalInfo() {
        val value = rowPersonalInfo.findViewById<TextView>(R.id.value)
        (activity as LifecycleOwner).lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val db = container.database
                val height = db.settingsDao().get(com.healix.app.db.SettingsKeys.HEIGHT)
                    ?.toIntOrNull() ?: 0
                val today = java.time.LocalDate.now()
                val weight = runCatching {
                    db.eventDao().weightRowsInRange(
                        today.minusDays(365).toString(),
                        today.toString(),
                    ).lastOrNull()?.weightKg ?: 0.0
                }.getOrDefault(0.0)
                val hasValue = height > 0 || weight > 0.0
                value.text = when {
                    height > 0 && weight > 0.0 ->
                        activity.getString(R.string.personal_info_entry_both, height, trimWeight(weight))
                    height > 0 ->
                        activity.getString(R.string.personal_info_entry_height, height)
                    weight > 0.0 ->
                        activity.getString(R.string.personal_info_entry_weight, trimWeight(weight))
                    else -> activity.getString(R.string.personal_info_entry_none)
                }
                value.setTextColor(container.getColor(if (hasValue) R.color.text_2 else R.color.text_3))
            }
        }
    }

    private fun trimWeight(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun exportBackup() {
        // buildJson 内部是 runBlocking 的同步 DB 读 —— 放 IO 线程，不卡主线程；
        // SAF 选择器必须在主线程发起。
        (activity as LifecycleOwner).lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) { ExportWriter.buildJson(activity) }
            ExportWriter.launchCreateDocument(activity, json)
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, HealixApp.CHANNEL_ID_QUICK_INPUT)
        }
        runCatching { activity.startActivity(intent) }
            .onFailure {
                // 个别 ROM 不支持渠道设置页 → 退回 App 总通知设置
                activity.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName),
                )
            }
    }
}
