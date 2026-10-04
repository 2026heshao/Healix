package com.healix.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.healix.app.R
import com.healix.app.ui.MainActivity
import com.healix.app.ui.TabBar
import com.healix.app.ui.TodaySummary

/**
 * 桌面小工具（微扩展 E·方案 B）：4×1 —— 本周运动缺口 + 运动 N/M + 「记一笔」。
 *
 * 数据口径与首页状态行同源：[TodaySummary]（本周 = ISO 周一到今天，
 * 次数 = countByTypeInRange("exercise", ...)，目标 = goals 表兜底 3 次）。
 * 不在本文件里另写一套周窗口算法 —— 跨文件唯一口径纪律。
 *
 * 刷新模型 = **app-pushes-updates**（updatePeriodMillis=0 刻意禁用系统周期刷新）：
 * - 写入点推送：EventRepository.submit 成功 / undo / restore / applyUserEdit
 * - 回前台推送：MainActivity.onResume（覆盖跨天 / 跨周口径变化）
 * - 放置时推送：onUpdate
 */
class HealixWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        push(context)
    }

    companion object {

        /**
         * 推送一次刷新。子线程读库（TodaySummary.build 内部 runBlocking），
         * 广播 / onResume 调用方都不阻塞主线程。重复推送幂等（同数据同结果）。
         */
        fun push(context: Context) {
            val app = context.applicationContext
            Thread {
                runCatching {
                    AppWidgetManager.getInstance(app).updateAppWidget(
                        ComponentName(app, HealixWidgetProvider::class.java),
                        buildViews(app),
                    )
                }
            }.start()
        }

        private fun buildViews(context: Context): RemoteViews {
            val summary = TodaySummary.build(context)
            val gap = summary.goalSessionsWeek - summary.exerciseCountThisWeek
            val views = RemoteViews(context.packageName, R.layout.widget_healix)
            views.setTextViewText(
                R.id.gapTitle,
                if (gap > 0) {
                    context.getString(R.string.widget_gap_left, gap)
                } else {
                    context.getString(R.string.widget_gap_done)
                },
            )
            views.setTextViewText(
                R.id.gapSub,
                context.getString(
                    R.string.status_dim_training,
                    summary.exerciseCountThisWeek,
                    summary.goalSessionsWeek,
                ),
            )
            views.setOnClickPendingIntent(R.id.btnAdd, logIntent(context))
            return views
        }

        /** 「记一笔」→ 主界面速记框（清栈复用现例；冷启动由 onCreate 300ms 自动聚焦兜底）。 */
        private fun logIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(TabBar.EXTRA_TAB, TabBar.TAB_RECORD)
                    putExtra(MainActivity.EXTRA_FOCUS_INPUT, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
