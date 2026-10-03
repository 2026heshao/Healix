package com.healix.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.repo.SOURCE_NOTIFICATION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 通知栏 RemoteInput 的接收者。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 关键 API：RemoteInput.getResultsFromIntent
 * ══════════════════════════════════════════════════════════════════════════
 * 必须在 `intent` 上调用（不是 `intent.extras`），因为 Android 12+
 * 会把 RemoteInput 的结果包在一个特殊的 clipData 里，直接读 extras 拿不到。
 * 这是 QuickMDCapture 等实现里最关键的一处 API 用法，抄的就是这一句。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 幂等（功能补充 1.3）
 * ══════════════════════════════════════════════════════════════════════════
 * 通知栏的 RemoteInput 在极少数 ROM 上会**重复投递**同一个 Intent。
 * [EventRepository.submit] 内部用 `insertIgnore` + UNIQUE(client_event_id) 兜底，
 * 但这里更进一步：在**生成 clientEventId 之前**就检查是否已经有同文本的 pending 记录，
 * 避免重复投递产生两条独立的 UUID（那样 UNIQUE 约束就失效了）。
 *
 * 更可靠的做法：把 clientEventId 放进 Intent extras 由 service 生成。但本 receiver
 * 是广播入口，无法预先生成 —— 因此这里用"同文本 + 3 秒窗口"作为去重启发式。
 * 副作用可接受：3 秒内两次完全相同的输入会被视为重复（用户不会这么做）。
 */
class QuickInputReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HealixApp.ACTION_QUICK_INPUT_SUBMIT) return

        // 关键：在 intent 上取 RemoteInput 结果，不是 intent.extras
        val results: Bundle = RemoteInput.getResultsFromIntent(intent) ?: Bundle.EMPTY
        val text = results.getCharSequence(QuickInputService.KEY_QUICK_INPUT_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()

        if (text.isEmpty()) {
            // 用户点了发送但没输入：不做任何事，通知保持原样
            return
        }

        // 用 goAsync 延长广播生命周期：本地处理需要几秒（网络调用）
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                val app = HealixApp.from(context)

                // 幂等启发式：3 秒内同文本的 pending 视为重复投递
                if (isLikelyDuplicate(context, text)) {
                    return@launch
                }

                val clientEventId = UUID.randomUUID().toString()

                // 先落 pending 并广播"已排队"，让 UI 立刻可见（0ms）
                AppEventBus.emit(
                    AppEvent.PendingQueued(clientEventId = clientEventId, rawText = text)
                )

                // 走完整抽取链（内部已含超时 + 重试 + 降级，不抛异常）
                val result = app.eventRepository.submit(
                    rawText = text,
                    source = SOURCE_NOTIFICATION,
                    clientEventId = clientEventId,
                )

                if (result.ok) {
                    val event = result.firstEvent
                    val labelRes = EventText.typeLabelRes(event?.type ?: "other")
                    val valueText = formatValue(event)

                    AppEventBus.emit(
                        AppEvent.Recorded(
                            clientEventId = clientEventId,
                            typeLabel = context.getString(labelRes),
                            kcal = event?.kcal ?: 0,
                            extraCount = result.extraCount,
                        )
                    )

                    QuickInputService.showRecorded(
                        context = context,
                        clientEventId = clientEventId,
                        typeLabelRes = labelRes,
                        valueText = valueText,
                    )
                } else {
                    AppEventBus.emit(
                        AppEvent.Failed(
                            clientEventId = clientEventId,
                            reason = result.error ?: "unknown",
                        )
                    )
                    QuickInputService.showFailed(context)
                }
            } catch (e: Exception) {
                // 兜底：goAsync 的协程里任何异常都不能逃逸（会静默吞掉 broadcast 结果）
                try {
                    QuickInputService.showFailed(context)
                } catch (ignored: Exception) {
                    // 通知更新失败也无所谓，数据已尽力处理
                }
            } finally {
                pendingResult.finish()
            }
        }

        // 不 cancel scope：让协程跑完。pendingResult.finish() 会释放广播槽位。
    }

    /**
     * 3 秒窗口内是否已有同文本的 pending 记录。
     *
     * 用阻塞查询（runBlocking）不合适 —— 这里直接在协程里调 suspend 方法。
     * 返回 true 表示"看起来是重复投递"。
     */
    private suspend fun isLikelyDuplicate(context: Context, text: String): Boolean {
        return try {
            val app = HealixApp.from(context)
            val pending = app.database.eventDao().listPending(limit = 20)
            val now = System.currentTimeMillis()
            pending.any {
                it.rawText == text && (now - it.createdAt) < DUPLICATE_WINDOW_MS
            }
        } catch (e: Exception) {
            false // 查询失败时按"不重复"处理，宁可多插一条也不要漏记
        }
    }

    /** 通知副标题的数值部分：按类型给出 kcal / 体重 / 时长，都没有则不显示数值。 */
    private fun formatValue(event: com.healix.app.parse.ParsedEvent?): String {
        if (event == null) return ""
        return when (val v = EventText.summarySuffix(event)) {
            is EventText.SummaryValue.Kcal -> v.value.toString()
            is EventText.SummaryValue.Weight -> v.kg.toString()
            is EventText.SummaryValue.Sleep -> v.hours.toString()
            EventText.SummaryValue.None -> ""
        }
    }

    companion object {
        /** 重复投递判定窗口 */
        private const val DUPLICATE_WINDOW_MS = 3_000L
    }
}
