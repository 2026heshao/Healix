package com.healix.app.notify

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启：恢复常驻通知服务。
 *
 * 监听两个 action：
 * - `BOOT_COMPLETED`：正常开机
 * - `MY_PACKAGE_REPLACED`：App 升级/覆盖安装后（升级会杀掉所有进程，常驻通知会消失）
 *
 * ⚠️ 国行 ROM 的现实约束（总方案第十节已确认）：
 * 即使声明了 `RECEIVE_BOOT_COMPLETED`，MagicOS / EMUI / MIUI 等默认**自启动管理**会拦截。
 * 用户需要手动在"设置 → 应用 → Healix → 自启动"里放行。
 * 这个 receiver 只能做到"系统给了广播就一定恢复"，做不到"保证一定收到广播"。
 * App 内应有引导文案（strings.xml 已有 perm_battery_needed），但**不做 hack 跳转**。
 *
 * 不做的事：
 * - 不在此处启动 Activity（开机后弹界面是骚扰）
 * - 不起 WorkManager 定时任务（国行 ROM 会杀，见"被动监督"设计）
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> restoreQuickInput(context)

            else -> Unit
        }
    }

    private fun restoreQuickInput(context: Context) {
        // 用户可能已手动关闭了常驻通知（在系统设置里划掉并禁止）——
        // 此时前台服务会被系统禁止启动，QuickInputService.start 内部已吞掉异常。
        // 这里额外检查一次通知是否被禁用，避免无谓的启动尝试打日志。
        if (isNotificationBlocked(context)) return

        QuickInputService.start(context)
    }

    /** 通知渠道被用户禁用时，常驻通知无意义 —— 不要启动前台服务。 */
    private fun isNotificationBlocked(context: Context): Boolean {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            !nm.areNotificationsEnabled()
        } catch (e: Exception) {
            false
        }
    }
}
