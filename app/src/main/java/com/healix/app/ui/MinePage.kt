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
    private val rowKnowledge = root.findViewById<View>(R.id.rowKnowledge)
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
            TabBar.openSecondary(
                activity,
                Intent(activity, StatusDetailActivity::class.java),
            )
            onOpenStatus()
        }
        rowStatusDetail.bindPressScale()

        // ── 数据：知识库（唯一入口迁移至此，11.1）──
        rowKnowledge.findViewById<TextView>(R.id.label).setText(R.string.knowledge_title)
        rowKnowledge.findViewById<View>(R.id.chevron).visibility = View.VISIBLE
        rowKnowledge.setOnClickListener {
            TabBar.openSecondary(
                activity,
                Intent(activity, KnowledgeBaseActivity::class.java),
            )
        }
        rowKnowledge.bindPressScale()

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
            TabBar.openSecondary(activity, Intent(activity, SettingsActivity::class.java))
        }
        rowSettings.bindPressScale()

        rowDebug.findViewById<TextView>(R.id.label).setText(R.string.group_debug)
        rowDebug.findViewById<View>(R.id.chevron).visibility = View.VISIBLE
        rowDebug.setOnClickListener {
            TabBar.openSecondary(activity, Intent(activity, DebugActivity::class.java))
        }
        rowDebug.bindPressScale()

        // 通知栏录入：跳系统通知设置（常驻通知由 QuickInputService 托管，
        // App 侧无独立配置页 —— 引导到渠道开关，而非发明新页）。
        rowNotify.findViewById<TextView>(R.id.label).setText(R.string.row_notify_input)
        rowNotify.findViewById<View>(R.id.chevron).visibility = View.VISIBLE
        rowNotify.setOnClickListener { openNotificationSettings() }
        rowNotify.bindPressScale()

        observeKnowledgeCount()
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
