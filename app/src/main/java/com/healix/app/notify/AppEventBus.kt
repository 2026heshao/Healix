package com.healix.app.notify

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 轻量进程内事件总线（单例）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 关键取舍：为什么不用 LocalBroadcastManager / sendBroadcast
 * ══════════════════════════════════════════════════════════════════════════
 * 场景：QuickInputService（前台服务）拿到用户从通知栏输入的文字，
 * 处理完之后要让 MainActivity 的列表刷新 / 显示一行"已记录"。
 *
 * 三个候选：
 *
 * 1. `LocalBroadcastManager`
 *    → **已废弃**（androidx 1.1.0 起 deprecated，官方明确建议改用 Flow/LiveData）。
 *      新代码用它是主动背技术债。
 *
 * 2. `sendBroadcast` + 动态注册 receiver
 *    → 可行，但有两个具体劣化：
 *      a. receiver 必须成对 register/unregister，漏 unregister 就泄漏；
 *         在 onStart/onStop 里配对又会让后台切换时丢事件（用户在别的页面时收不到）。
 *      b. 走系统 Binder，是跨进程通道 —— 我们这里是同进程通信，杀鸡用牛刀，
 *         且广播是**一次性**的，没有回放（replay）能力。
 *
 * 3. `MutableSharedFlow`（**本方案**）
 *    → 同进程、零 Binder 开销、天然协程友好。
 *      用 `replay = 0, extraBufferCapacity = 8, onBufferOverflow = DROP_OLDEST`：
 *      · replay=0：新订阅者不会收到历史事件（避免 Activity 重建时重复弹提示）
 *      · extraBufferCapacity=8：发送方（Service）不会因无订阅者而挂起
 *      · DROP_OLDEST：极端情况下丢最老的，绝不阻塞 Service
 *
 *    **注意**：SharedFlow 是"广播给所有当前订阅者"，Activity 不在前台时事件会被丢弃 ——
 *    而我们的数据源本身是 Room 的 Flow（`observeByDay` 等），
 *    Activity 重建后会自动从数据库拿到最新状态。EventBus 只承担"即时提示"职责
 *    （如"已记录"这一行文案），不是数据通道。**这一点是选 SharedFlow 的前提** ——
 *    数据可靠性由 Room 保证，EventBus 只做锦上添花。
 *
 * 因此选 3。若将来需要"跨进程"或"必达"，才需要重新评估。
 *
 * 该单例在 Application 单进程内有效 —— HealixApp 未开启 android:process，
 * 所有组件同进程，无需考虑多进程可见性。
 */
object AppEventBus {

    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 只读订阅入口。UI 层 collect 这个。 */
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    /**
     * 发送事件（同步、非挂起）。
     * 用 `tryEmit` 而非 emit：发送方可能是 Service 的 onStartCommand（非协程上下文），
     * 且我们**绝不能**因为没人订阅就让 Service 阻塞。
     */
    fun emit(event: AppEvent) {
        _events.tryEmit(event)
    }
}

/** 进程内事件。用 sealed interface 便于 UI 层穷举 when。 */
sealed interface AppEvent {

    /** 通知栏录入已成功入库。UI 可据此显示一行提示 / 局部刷新。 */
    data class Recorded(
        val clientEventId: String,
        /** 类型中文标签：饮食 / 运动 / 体重 … */
        val typeLabel: String,
        /** 估算热量 */
        val kcal: Int,
        /** 拆出了几条（>1 时提示"已记录 3 条"） */
        val extraCount: Int,
    ) : AppEvent

    /** 通知栏录入已先落 pending，等待 AI 回填。UI 立刻显示原文即可。 */
    data class PendingQueued(
        val clientEventId: String,
        val rawText: String,
    ) : AppEvent

    /** 录入失败（含未配置 provider）。UI 显示错误行 + 重试按钮。 */
    data class Failed(
        val clientEventId: String,
        val reason: String,
    ) : AppEvent

    /** 撤销成功。 */
    data class Undone(val clientEventId: String) : AppEvent

    /** 请求 UI 把输入焦点移到速记框（点通知但输入失败时的兜底）。 */
    data object RequestFocusInput : AppEvent
}
