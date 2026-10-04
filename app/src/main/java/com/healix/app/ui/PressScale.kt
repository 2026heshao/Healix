package com.healix.app.ui

import android.view.MotionEvent
import android.view.View

/**
 * 按压缩放（设计规范 11.4，v6）：可点元素按下时 **变色 + scale(0.985)** 双反馈，
 * transition 约 120ms。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 实现说明
 * ══════════════════════════════════════════════════════════════════════════
 * - 变色：项目所有可点元素已用 `?attr/selectableItemBackground`，按下变色天然存在
 *   （规范 §2.1 的 press 令牌，深色模式由 values-night 接管）—— 这里只补缩放；
 * - OnTouchListener 返回 false：不消费事件，原有的点击 / 涟漪态 / 长按全部保留，
 *   只是"旁听"按下与抬起；
 * - 缩放 pivot 默认为视图中心；松手 / 取消弹回 1.0；
 * - 深色模式行为一致：缩放与颜色令牌无关，无需单独适配。
 *
 * 应用范围（原型 `.item/.row/.docrow/.btn-primary/.planhint/.quick div` 的对应物）：
 * 记录行 / 预设行 / 「我的」页行 / 状态行 / 计划条 / 监督条 / 弹盘主按钮 / 快捷入口。
 */
internal fun View.bindPressScale(scale: Float = 0.985f, durationMs: Long = 120L) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(scale).scaleY(scale).setDuration(durationMs).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(durationMs).start()
        }
        false // 不消费：点击行为照旧
    }
}
