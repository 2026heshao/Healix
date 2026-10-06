package com.healix.app.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.EditText

/**
 * 常驻输入条高度自适应（P0-2）。记录页与对话页**共用这一份实现**，禁止各写一遍。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么用「整屏无关的高度插值」而不是给容器套动画
 * ══════════════════════════════════════════════════════════════════════════
 * 输入条本体是 `wrap_content + minHeight=64dp`。文本换行时 EditText 的实测高度会
 * 在**一帧内**跳变（1 行 → 3 行多出约 46dp），直接让它落地就是「抖一下」——
 * 这正是本项要消除的跳变。由于容器的最终高度取决于子视图测量，无法对
 * 「自动高度」本身做属性动画，所以退一步：**用 EditText 的实测高度做插值源**，
 * 每帧把插值结果写回容器 height。到动画结束再还原成 WRAP_CONTENT，把控制权
 * 交还给测量系统（否则窗口尺寸变化 / 字号变化后容器会钉死在某一帧的高度上）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么必须屏蔽重入
 * ══════════════════════════════════════════════════════════════════════════
 * 动画每一帧都在改父容器高度 → 父容器重新布局 → EditText 的 `bottom - top`
 * 随之变化 → 再次触发本监听。若不吞掉动画期间的回调，会立刻叠出第二段动画，
 * 高度互相追逐、永不收敛。用局部 `animating` 标志在窗口期内直接 return。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 与 TabBar.bindImeGuard 共用同一个 LayoutParams
 * ══════════════════════════════════════════════════════════════════════════
 * `TabBar.bindImeGuard` 会改 `inputBar` 这个 **同一个** MarginLayoutParams 的
 * `bottomMargin`（键盘弹出时归零、收起时还原 tab_raise）。本类改的是它的
 * `height`。两者若各自缓存一份 LayoutParams 再回写，会互相覆盖丢更新 ——
 * 所以每次 `addUpdateListener` / `onAnimationEnd` 都**重新读** `bar.layoutParams`
 * 再改一个字段回写，双方各自的修改都能保留。
 *
 *   motion_grow = 200ms，曲线 cubic-bezier(0.2, 0, 0, 1)（Material 标准曲线）。
 */
object InputBarHeightAnimator {

    /** motion_grow：输入条高度自适应时长（ms）。数值唯一出处见 dimens.xml 顶部注释。 */
    const val DURATION_MS = 200L

    /**
     * 绑定一次。[input] 的可见高度变化经 200ms 曲线插值同步到 [bar] 的 height。
     *
     * [onGrow] 可选：仅当输入条**增高**时回调一次，供调用方同步把列表滚到底部
     * （记录页 / 对话页各传自己的滚动目标）。放在这里而不是各页自挂监听，是为了
     * 高度判定**只有一处**，避免「两套高度比较逻辑迟早对不上」。
     */
    fun bind(input: EditText, bar: View, onGrow: (() -> Unit)? = null) {
        // ⚠️ 状态必须是 bind 的**局部变量**（由下面这个监听闭包持有）。
        //    绝不能用 object 级的 HashMap<View, …> 静态缓存 —— 那会把视图
        //    连同其 Activity 一起钉在内存里（Fragment 常驻，泄漏面更大）。
        var lastHeight = 0
        var animating = false

        input.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val height = bottom - top
            // 动画期自身的尺寸变化，吞掉（否则高度互相追逐）
            if (animating) return@addOnLayoutChangeListener
            if (height == lastHeight) return@addOnLayoutChangeListener
            // 首次布局（上次为 0）：只记基线，不做动画
            if (lastHeight == 0) {
                lastHeight = height
                return@addOnLayoutChangeListener
            }

            val from = lastHeight
            lastHeight = height
            animating = true
            if (height > from) onGrow?.invoke()

            ValueAnimator.ofInt(from, height).apply {
                duration = DURATION_MS
                interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                addUpdateListener { va ->
                    // 每次都重新读 bar.layoutParams：bindImeGuard 也在改同一个
                    // MarginLayoutParams 的 bottomMargin，缓存回写会把它冲掉。
                    val lp = bar.layoutParams
                    lp.height = va.animatedValue as Int
                    bar.layoutParams = lp
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // 还原成 WRAP_CONTENT：把控制权交还测量系统，避免窗口 / 字号
                        // 变化后容器钉死在某一帧的高度上。
                        val lp = bar.layoutParams
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        bar.layoutParams = lp
                        animating = false
                    }
                })
                start()
            }
        }
    }
}
