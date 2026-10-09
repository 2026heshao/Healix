package com.healix.app.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.healix.app.R
import kotlin.math.abs

/**
 * 记录行左滑操作（设计规范 11.3，v6）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 手势规格（原型 swipewrap 逐条对应）
 * ══════════════════════════════════════════════════════════════════════════
 * - 行左滑露出下层操作按钮（记录行「编辑」+「删除」两枚 72dp，满程 144dp；
 *   设置行只有「删除」一枚 72dp，满程 72dp）；
 * - pointer 拖拽 0 ~ -满程 实时跟随（clamp）；
 * - 越过**本行满程的一半**松手吸附全开，否则回弹（150ms）；
 * - 已开状态下反向拖拽回程过半则收起；
 * - 拖拽发生过的 300ms 内屏蔽 click（防"拖完误进编辑"，V4 断言）；
 * - **全局同时最多一行滑开**：按下其它行自动收起；按下列表其它区域 /
 *   列表外区域由宿主调用 [closeIfOutside]。
 *
 * 纯 onTouch 实现，不引入 Gesture / ViewPager 等新库。
 * 每个列表（RecyclerView）持有 1 个实例，所有行共享 → 天然"全局单开"。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 2026-10-09 修复（真机问题 3：T2「带纵向分量的左滑完全失效」+ 距离过长）
 * ══════════════════════════════════════════════════════════════════════════
 * 1. **横向意图锁定**：旧实现只在 ACTION_MOVE 里看 `event.x`，对 `y` 一无所知，
 *    也从不向父容器声明"本次手势归我"。真实手指必带纵向分量 → `|dy|` 一过
 *    `scaledTouchSlop`，外层 RecyclerView 便拦截并向本行发 ACTION_CANCEL，
 *    滑到一半被回弹（实测 T1 纯水平成功 / T2 仅加 60px 纵向就完全无效）。
 *    现在一旦判定「横向为主」即 `requestDisallowInterceptTouchEvent(true)`，
 *    抬手/取消时交还 `false`。
 * 2. **`ACTION_CANCEL` 独立分支**：吸附改判**当前位移**而不是重算 `dx`
 *    （取消时 `event.x` 可能已被改写）。顺带修掉旧判据
 *    `if (wasOpen) dx > -halfOpenPx` 的反向问题 —— 该式在已开状态下几乎恒真，
 *    导致行一旦滑开就**拖不回去**。
 * 3. **阈值按行派生**：满程取 `underlayOf()` 实测的按钮宽度之和（记录行 288px、
 *    设置行 144px），吸附线 = 其一半。旧实现是全局定值 288px，设置行只有一枚
 *    144px 按钮却要拖过 144px 才吸附 —— 这正是"要滑很长"的来源。
 */
class SwipeController(context: Context) {

    /** 最近一次有效拖拽的时间戳：300ms 内的 click 视为拖拽余波。 */
    var swipeTs: Long = 0L
        private set

    /**
     * underlay 不可测时的回落满程（2 × 72dp）。
     *
     * ⚠️ 单源化（真机问题 A5）：按钮宽度**只**从 `R.dimen.swipe_act_width` 读，
     *    不再另存一份 `SWIPE_ACT_DP = 72` —— 同语义两处来源时，改 dimen 会让
     *    clamp 与实测宽度静默分叉（对齐 MEMORY §2「禁私有副本」口径）。
     */
    private val fallbackFullPx: Float =
        context.resources.getDimensionPixelSize(R.dimen.swipe_act_width) * 2f

    private val slop: Int = ViewConfiguration.get(context).scaledTouchSlop

    private var current: View? = null
    private var startX = 0f
    private var startY = 0f
    private var moved = false

    /** 本次手势已锁定为横向拖拽（此后占有事件，父容器不得拦截）。 */
    private var locked = false

    /** 本次手势已判定为纵向滚动 → 彻底放弃接管（避免斜向滚动误触）。 */
    private var abandoned = false

    /** 本次手势的满程（按被拖行的 underlay 实测宽度派生）。 */
    private var rowFull = 0f

    /** 拖拽开始时该行是否已滑开（原型 cur.__open）。 */
    private var wasOpen = false

    /** 当前处于滑开态的行（手势结束后仍持久跟踪，供"点其它区域回弹"用）。 */
    private var openRow: View? = null

    fun onTouch(item: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                closeOthers(item)
                current = item
                // ⚠️ 必须用 **屏幕坐标 rawX/rawY**，不能用 event.x/y（2026-10-09 根因）。
                //    `event.x` 是**本视图自己的坐标系**里的值，而本视图在拖拽过程中正被
                //    自己平移（translationX）—— 于是每一帧的 dx 都把上一帧的位移又减掉
                //    一次，递推成 `T(n) = d - T(n-1)`：位移量在 `d` 与 `0` 之间来回振荡。
                //    后果就是「有时拖 300px 能吸附、拖 200px 反而回弹」这种看似随机的
                //    手感，用户只能靠拖得又长又稳来碰运气。`rawX` 是屏幕绝对坐标，
                //    不受本视图平移影响，位移量单调、可预测。
                startX = event.rawX
                startY = event.rawY
                moved = false
                locked = false
                abandoned = false
                rowFull = rowFullOf(item)
                wasOpen = item.translationX <= -rowFull / 2f
            }

            MotionEvent.ACTION_MOVE -> {
                if (current !== item || abandoned) return
                val dx = event.rawX - startX
                if (!locked) {
                    // 位移未过 touch slop → 尚不足以判定意图，继续观察。
                    if (abs(dx) <= slop) return
                    // 纵向分量更大 → 这是列表滚动，交还父容器（本次手势不再接管）。
                    if (abs(event.rawY - startY) > abs(dx)) {
                        abandoned = true
                        return
                    }
                    // 横向为主 → 锁定并占有事件。这一步是 T2 失效的根因修复：
                    // 不声明所有权，|dy| 一过 slop 就会被 RecyclerView 抢走。
                    locked = true
                    moved = true
                    item.parent?.requestDisallowInterceptTouchEvent(true)
                }
                val base = if (wasOpen) -rowFull else 0f
                val t = (base + dx).coerceIn(-rowFull, 0f)
                item.translationX = t
                // 稳定性修复（2026-10-08，重叠/截断）：下层按钮随内容同步平移 ——
                // 关闭态完全移出屏右、滑开态回到原位。此前按钮恒在原位，靠
                // swipeItem 盖住；但记录列表的 swipeItem 背景是透明的（v6 分组卡
                // 刻意透出 eventRoot 底色）→ 按钮永远透出来，文字直接叠在按钮上
                //（窄屏 540px 下必然重叠，宽屏也从未真正盖住过）。
                underlayOf(item)?.let { u ->
                    u.translationX = t + underlayOffset(u)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (current !== item) return
                if (locked) item.parent?.requestDisallowInterceptTouchEvent(false)
                // 用**抬手点**补齐末段位移：MOVE 是采样出来的，快速甩动（或注入式手势）
                // 常常只送来两三个 MOVE，最后一个会明显落后于真实抬手位置 —— 若只看
                // 已写入的 translationX，用户明明拖过了半数却会回弹，这正是「必须滑得
                // 很长且很精确」的体感来源之一。ACTION_UP 的坐标是可靠的（未被父容器
                // 改写），故在此把末段位移补上再裁决。
                if (locked) {
                    val base = if (wasOpen) -rowFull else 0f
                    val t = (base + (event.rawX - startX)).coerceIn(-rowFull, 0f)
                    item.translationX = t
                    underlayOf(item)?.let { u -> u.translationX = t + underlayOffset(u) }
                }
                if (moved) swipeTs = System.currentTimeMillis()
                // 判据读**当前可视化位置**：拖过去又拖回来的手势因此能正确回弹。
                val stick = item.translationX < -rowFull / 2f
                snap(item, if (stick) -rowFull else 0f)
                openRow = if (stick) item else null
                current = null
                locked = false
                abandoned = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (current !== item) return
                // 取消（被父容器接管 / 手势中止）：`event.x` 此时可能已被父容器改写，
                // **只**按当前可视化位置裁决 —— 拖过半数就吸附，否则回弹。
                if (locked) item.parent?.requestDisallowInterceptTouchEvent(false)
                if (moved) swipeTs = System.currentTimeMillis()
                val stick = item.translationX < -rowFull / 2f
                snap(item, if (stick) -rowFull else 0f)
                openRow = if (stick) item else null
                current = null
                locked = false
                abandoned = false
            }
        }
    }

    /** 拖完 300ms 内的点击：吞掉（V4 断言）。 */
    fun clickAllowed(): Boolean = System.currentTimeMillis() - swipeTs >= 300

    /** 无条件收起当前滑开的行（点「编辑/删除」后调用，避免带着滑开态进二级页）。 */
    fun closeAll() {
        openRow?.let { snap(it, 0f) }
        openRow = null
    }

    /**
     * 行被回收/复用时解除滑开跟踪（复用残留修复）：
     * RecyclerView 复用会把滑开态的 ViewHolder 让给新 item，此时 openRow 若仍
     * 指向这个视图，"全局单开"与 closeIfOutside 都在跟踪一个已经不属于原行的
     * 视图。宿主在 bind 时重置平移的同时调用本方法，保证出屏的滑开行滚回来
     * 一定是已回弹的干净行。
     */
    fun release(item: View) {
        if (openRow === item) openRow = null
    }

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
        underlayOf(item)?.let { u ->
            u.animate().cancel()
            u.animate().translationX(target + underlayOffset(u)).setDuration(150L).start()
        }
    }

    /**
     * 内容行对应的**下层按钮容器**（`item_event.xml` / `item_swipe_row.xml` 的
     * swipeWrap FrameLayout：index 0 = swipeActs，index 1 = swipeItem 内容层）。
     * 找不到返回 null —— 没有 underlay 的调用方行为与改前一致。
     */
    private fun underlayOf(item: View): View? {
        val p = item.parent as? android.view.ViewGroup ?: return null
        if (p.indexOfChild(item) != 1) return null
        return p.getChildAt(0)
    }

    /**
     * 该行 underlay 的**关闭态平移量**（正值，向右移出屏外）：
     * = swipeActs 里按钮宽度之和（记录行 2 × 72dp，设置行 1 × 72dp）。
     * 取 layoutParam 的声明宽度而非 measuredWidth —— bind 期测量未完成时
     * measuredWidth 恒 0，会让按钮提前漏出来。
     * ⚠️ `layoutParams.width` 解析自 XML dimen，**已经是像素**（72dp 在 1.875 屏
     * 上 = 135px），不再乘 density —— 再乘一次会把按钮推到两倍距离外（实测翻车：
     * 滑到满程按钮仍在屏外不可见）。异常情况（宽 0）回落 [fallbackFullPx]。
     */
    private fun underlayOffset(underlay: View): Float {
        if (underlay is android.view.ViewGroup) {
            var w = 0f
            for (i in 0 until underlay.childCount) {
                val lp = underlay.getChildAt(i).layoutParams
                if (lp != null && lp.width > 0) w += lp.width
            }
            if (w > 0f) return w
        }
        return fallbackFullPx
    }

    /**
     * 本行的拖拽满程 = 该行 underlay 实测宽度之和（无 underlay 时回落两枚按钮宽度）。
     * 记录行 288px、设置行 144px —— 吸附线即本值的一半，不再是全局定值。
     */
    private fun rowFullOf(item: View): Float {
        val u = underlayOf(item) ?: return fallbackFullPx
        return underlayOffset(u)
    }

    /**
     * 行绑定时的初始状态复位（RecordListAdapters.bind 调用，替代散落的三行手写）：
     * 内容归零 + 下层按钮复位到屏右外 + 解除滑开跟踪。
     * ⚠️ 必须在 bind 时显式复位 underlay：ViewHolder 复用可能带着上一行的
     * 滑开平移，不复位 = 新 item 的按钮留在半开位置。
     */
    fun resetRow(content: View) {
        content.animate().cancel()
        content.translationX = 0f
        underlayOf(content)?.let { it.translationX = underlayOffset(it) }
        release(content)
    }
}
