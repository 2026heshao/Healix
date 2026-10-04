package com.healix.app.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 记录行左滑操作（设计规范 11.3，v6）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 手势规格（原型 swipewrap 逐条对应）
 * ══════════════════════════════════════════════════════════════════════════
 * - 行左滑露出下层「编辑」（text_3 底）/「删除」（negative 底）两枚 72dp 按钮，
 *   满程 144dp（2 × 72）；
 * - pointer 拖拽 ≤0 ~ ≥-144dp 实时跟随（clamp）；
 * - 越过半程（72dp）松手吸附全开，否则回弹（150ms）；
 * - 已开状态下反向拖拽回程过半则收起；
 * - 拖拽发生过的 300ms 内屏蔽 click（防"拖完误进编辑"，V4 断言）；
 * - **全局同时最多一行滑开**：按下其它行自动收起；按下列表其它区域 /
 *   列表外区域由宿主调用 [closeIfOutside]。
 *
 * 纯 onTouch 实现，不引入 Gesture / ViewPager 等新库。
 * 每个列表（RecyclerView）持有 1 个实例，所有行共享 → 天然"全局单开"。
 */
internal class SwipeController(context: Context) {

    /** 最近一次有效拖拽的时间戳：300ms 内的 click 视为拖拽余波。 */
    var swipeTs: Long = 0L
        private set

    private val fullOpenPx: Int =
        (SWIPE_ACT_DP * 2 * context.resources.displayMetrics.density).toInt()
    private val halfOpenPx: Int = fullOpenPx / 2
    private val slop: Int = ViewConfiguration.get(context).scaledTouchSlop

    private var current: View? = null
    private var startX = 0f
    private var moved = false

    /** 拖拽开始时该行是否已滑开（原型 cur.__open）。 */
    private var wasOpen = false

    /** 当前处于滑开态的行（手势结束后仍持久跟踪，供"点其它区域回弹"用）。 */
    private var openRow: View? = null

    fun onTouch(item: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                closeOthers(item)
                current = item
                startX = event.x
                moved = false
                wasOpen = item.translationX <= -halfOpenPx
            }
            MotionEvent.ACTION_MOVE -> {
                if (current !== item) return
                val dx = event.x - startX
                if (abs(dx) > slop) moved = true
                val base = if (wasOpen) -fullOpenPx.toFloat() else 0f
                item.translationX = (base + dx).coerceIn(-fullOpenPx.toFloat(), 0f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (current !== item) return
                val dx = event.x - startX
                // 原型：已开 → 反向拖过 +72dp 才收；未开 → 拖过 -72dp 才开
                val stick = if (wasOpen) dx > -halfOpenPx else dx < -halfOpenPx
                snap(item, if (stick) -fullOpenPx.toFloat() else 0f)
                openRow = if (stick) item else null
                if (moved) swipeTs = System.currentTimeMillis()
                current = null
            }
        }
    }

    /** 拖完 300ms 内的点击：吞掉（V4 断言）。 */
    fun clickAllowed(): Boolean = System.currentTimeMillis() - swipeTs >= 300

    /**
     * 收起所有滑开的行（按下列表其它区域时由宿主调用）。
     * [hit] = 按下点所在的列表项根视图；滑开行若不在它的子树里就收起，
     * 已滑开行自身的按下不收（允许继续拖拽）。
     */
    fun closeIfOutside(hit: View?) {
        val open = openRow ?: return
        if (hit == null) {
            snap(open, 0f)
            openRow = null
            return
        }
        var p: android.view.ViewParent? = open.parent
        while (p != null) {
            if (p === hit) return // 按在滑开行自身所在的列表项内
            p = (p as? View)?.parent
        }
        snap(open, 0f)
        openRow = null
    }

    private fun closeOthers(item: View) {
        val open = openRow
        if (open != null && open !== item) {
            snap(open, 0f)
        }
        openRow = null
    }

    private fun snap(item: View, target: Float) {
        item.animate().cancel()
        item.animate().translationX(target).setDuration(150L).start()
    }

    private companion object {
        /** 单枚操作按钮 72dp（R.dimen.swipe_act_width），满程 2 × 72。 */
        const val SWIPE_ACT_DP = 72
    }
}
