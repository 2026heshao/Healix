package com.healix.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.healix.app.HealixApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 撤销上一笔（通知栏"撤销"按钮，5 秒窗口内可点）。
 *
 * 语义：**软删除**（`deleted_at = now`），不是物理删除。
 * - 30 天后由清理任务物理清除，给误删留后悔期
 * - 为什么用软删除而不是物理删除：这个按钮在通知栏，误触成本低但用户当下没有撤销机会；
 *   软删除 + 数据库兜底 = 最坏情况可以手动恢复
 *
 * 定位方式：`clientEventId`。这是唯一能跨进程传递的稳定标识
 * （rowid 在 receiver 里拿不到，因为入库是异步的）。
 */
class UndoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HealixApp.ACTION_UNDO_LAST) return

        val clientEventId = intent.getStringExtra(QuickInputService.EXTRA_CLIENT_EVENT_ID)
        if (clientEventId.isNullOrEmpty()) return

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                val app = HealixApp.from(context)
                val done = app.eventRepository.undo(clientEventId)

                if (done) {
                    AppEventBus.emit(AppEvent.Undone(clientEventId))
                }
                // 无论撤销成败，都把通知恢复为默认态：
                // 撤销失败（比如 5 秒后记录已被别的流程改过）也应把 UI 收回可录入状态
                QuickInputService.refreshSubtitle(context)
            } catch (e: Exception) {
                // 兜底：撤销失败不崩溃，通知恢复默认态
                try {
                    QuickInputService.refreshSubtitle(context)
                } catch (ignored: Exception) {
                    // 忽略
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
