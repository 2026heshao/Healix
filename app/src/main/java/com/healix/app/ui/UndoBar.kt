package com.healix.app.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import java.util.WeakHashMap

/**
 * 内联撤销条（Healix设计规范系统.md 9.6）。
 *
 * ```
 * 已记录 运动 · 推（胸/肩/三头）                    撤销
 * ```
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么是一个共享工具而不是各页面各写一遍
 * ══════════════════════════════════════════════════════════════════════════
 * 「写入后 5 秒可撤销」在首页（速记框/预设）与计划页训练 Tab（记一笔）**都要有**，
 * 而 PRD §15.7 把「撤得回」列为"不确认直接写"这条决策的**前提条件**之一 ——
 * 两处行为必须完全一致（同样的 5 秒、同样的文案口径、同样的无障碍朗读），
 * 各写一遍必然漂移。
 *
 * **与通知栏撤销的关系**：通知栏路径已有 `UndoReceiver` + `AppEventBus`。
 * 这里不替换它，只保证"5 秒"这个数字在两处一致，形成统一心智。
 *
 * **并发语义**：同时最多 1 条，新条直接顶掉旧条（规范 9.6）。
 * 用 `WeakHashMap<View, Runnable>` 按容器记录待执行的隐藏任务，
 * 而不是用一个静态字段 —— 否则两个 Activity 先后使用会互相取消对方的定时器。
 */
object UndoBar {

    /** 5 秒。与通知栏撤销一致（规范 9.6「形成统一心智」）。 */
    const val DURATION_MS: Long = 5_000L

    private val handler = Handler(Looper.getMainLooper())
    private val pending = WeakHashMap<View, Runnable>()

    /**
     * 显示撤销条。
     *
     * @param container 整条（48dp 全宽的容器），出现/消失都作用在它身上
     * @param leftText  左侧 13sp `text_2` 文案，例：`已记录 运动 · 推（胸/肩/三头）`
     * @param action    右侧「撤销」文字按钮
     * @param announce  无障碍朗读文本（[com.healix.app.R.string.undo_announce]），null 则不朗读
     * @param onUndo    点「撤销」时执行；执行后立即隐藏
     *
     * ⚠️ 立刻插入、**无入场动画**（规范 9.6：出现无动画，消失才淡出）。
     */
    fun bind(
        container: View,
        leftText: TextView,
        action: TextView,
        text: CharSequence,
        announce: String?,
        onUndo: () -> Unit,
    ) {
        cancel(container)

        leftText.text = text
        container.visibility = View.VISIBLE

        action.setOnClickListener {
            cancel(container)
            container.visibility = View.GONE
            onUndo()
        }

        val task = Runnable { container.visibility = View.GONE }
        pending[container] = task
        handler.postDelayed(task, DURATION_MS)

        // 规范 9.10：出现时朗读一次。工具状态不朗读，但这个要朗读 —— 它可操作。
        if (!announce.isNullOrEmpty()) {
            container.announceForAccessibility(announce)
        }
    }

    /** 主动收起（例如用户切页）。 */
    fun hide(container: View) {
        cancel(container)
        container.visibility = View.GONE
    }

    private fun cancel(container: View) {
        pending.remove(container)?.let { handler.removeCallbacks(it) }
    }
}
