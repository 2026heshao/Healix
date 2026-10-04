package com.healix.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import com.healix.app.R

/**
 * 底部弹盘抓手容器（设计规范 11.5，v6）：支持**下拖关闭**。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 行为（原型 grabber 逐条对应）
 * ══════════════════════════════════════════════════════════════════════════
 * - 按下后向下拖，弹盘位移实时跟随（translateY = dy，dy ≥ 0）；
 * - 松手时位移超过 80dp（R.dimen.grab_close_offset）→ [onDragDismiss]，否则弹回；
 * - **排除 input/button 上的按下**（原型 `closest("input,button,select,label")`）：
 *   这类控件自己会消费 DOWN（EditText/Button 均可点击），我们只在
 *   「按下点落在非交互子视图上」时才允许拦截，天然不会劫持表单手势；
 * - 与 BottomSheetBehavior 不冲突：拦截发生时向上发出
 *   requestDisallowInterceptTouchEvent，阻止外层弹盘容器抢走手势。
 *
 * 用法：sheet_confirm.xml 根容器即本类；EventEditSheet 在 onViewCreated 里设
 * `binding.root.onDragDismiss = { dismiss() }`。
 */
class GrabberLayout : FrameLayout {

    /** 下拖超过阈值松手时的回调（通常 = dismiss()）。 */
    var onDragDismiss: (() -> Unit)? = null

    private var downY = 0f
    private var dragging = false

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) :
        super(context, attrs, defStyle)

    private val closeOffsetPx: Int
        get() = resources.getDimensionPixelSize(R.dimen.grab_close_offset)

    private val touchSlopPx: Int
        get() = android.view.ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.y
                dragging = false
                // 阻止 BottomSheetBehavior（外层弹盘 FrameLayout）抢走本次手势
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dy = ev.y - downY
                    // 只拦"向下"的拖动，且按下点不能在交互控件上（input/button 语义）
                    if (dy > touchSlopPx && !hitsInteractiveChild(ev)) dragging = true
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> downY = ev.y
            MotionEvent.ACTION_MOVE -> {
                val dy = (ev.y - downY).coerceAtLeast(0f)
                translationY = dy // 实时跟随
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dy = ev.y - downY
                if (dy > closeOffsetPx) {
                    onDragDismiss?.invoke()
                } else {
                    // 弹回（原型：transition 恢复，位移归零）
                    animate().translationY(0f).setDuration(200L).start()
                }
                dragging = false
            }
        }
        return true
    }

    /** 按下点（DOWN 时判定）是否落在可交互子视图上 —— 是则不拦截，防表单误拖。 */
    private fun hitsInteractiveChild(ev: MotionEvent): Boolean {
        var target: View? = findViewAt(this, ev.x, ev.y)
        while (target != null && target !== this) {
            if (target is EditText || target.isClickable || target.isLongClickable) return true
            target = target.parent as? View
        }
        return false
    }

    /** 命中测试：返回 (x, y) 处最深层的可见子视图。 */
    private fun findViewAt(root: View, x: Float, y: Float): View? {
        if (root !is ViewGroup) return root
        for (i in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(i)
            if (child.visibility != VISIBLE) continue
            if (x >= child.left && x < child.right && y >= child.top && y < child.bottom) {
                return findViewAt(child, x - child.left, y - child.top)
            }
        }
        return root
    }
}
