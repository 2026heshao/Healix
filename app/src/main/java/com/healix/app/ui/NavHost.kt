package com.healix.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.healix.app.R

/**
 * 二级页导航（v8 T03：二级页 Fragment 化）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么不再用 Activity
 * ══════════════════════════════════════════════════════════════════════════
 * 此前每个二级页都是独立 Activity：进入时系统起一个新窗口并走窗口转场，
 * 而新页 `onCreate` 要在**主线程**膨胀 14–23KB 布局并读库 —— 新旧窗口一起冻结，
 * 实测「前进/后退各卡约 1 秒」（需求 1 的根因之一）。
 *
 * 现在二级页是宿主 [MainActivity] 里 `pageContainer` 上的 Fragment：
 * - **零窗口转场**：同一窗口内做 View 动画，不再有 WindowManager 的双窗口调度；
 * - **零 Activity 重建**：宿主与 Tab 页状态全程保留（返回时列表滚动位置还在）；
 * - 返回键交给 FragmentManager 回退栈（[back]），不再依赖 Activity 栈。
 *
 * 入场/出场沿用 11.2 的动画（in_fwd 22%→0；返回时 out_back 0→22%），
 * 因为同窗口内不存在"旧窗口透出"问题，恒不透明仍然成立。
 *
 * ⚠️ `pageContainer` 在 `activity_main.xml` 里排在 tabbar **之后**（z 序更高），
 * 所以二级页天然盖住 tabbar —— 无需显式隐藏导航栏（原型 go() 白名单的等价物）。
 * 但容器**空着时不能吃点击**，故其 `isClickable` 由宿主按回退栈状态维护
 * （见 MainActivity 的 backStackChangedListener）。
 *
 * 入口签名一律收 [Context]（而非 FragmentActivity）：调用方有 `Activity`、
 * `View.context` 等不同静态类型，统一在 [activityOf] 里沿 ContextWrapper 链解析，
 * 省掉调用点上的强转与 `?.` 噪音。
 */
internal object NavHost {

    /** 回退栈标签前缀：二级页统一携带，调试时一眼分辨来源。 */
    private const val TAG_PREFIX = "page:"

    /** 从任意 Context（含 ContextWrapper 链）里找出宿主 FragmentActivity。 */
    fun activityOf(context: Context): FragmentActivity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is FragmentActivity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /** 宿主当前是否停在某个二级页（返回键语义 / 输入法守卫都要问它）。 */
    fun isOpen(context: Context): Boolean =
        activityOf(context)?.supportFragmentManager?.backStackEntryCount?.let { it > 0 } ?: false

    /**
     * 打开二级页：进 `pageContainer`。
     *
     * 动画参数是 `(enter, exit, popEnter, popExit)`：
     * - 入场 = in_fwd（22%→0）；`replace` 掉的旧页不做动画（同容器内本就只有一页）；
     * - 弹栈 = popExit 走 out_back（0→22%），即二级页向右滑出、露出底下的 Tab 页。
     *
     * 找不到宿主 Activity（理论上不会）时静默忽略 —— 不抛异常拖垮入口点击。
     */
    fun open(context: Context, fragment: Fragment, tag: String) {
        val host = activityOf(context) ?: return
        host.supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.in_fwd, 0, R.anim.in_back, R.anim.out_back)
            .replace(R.id.pageContainer, fragment, TAG_PREFIX + tag)
            .addToBackStack(TAG_PREFIX + tag)
            .commit()
    }

    /**
     * 返回：有二级页则弹栈并返回 true（调用方据此判定"返回键已被消费"）。
     * 用 `popBackStack()`（异步）而非 Immediate —— 异步才能让 out_back 播完。
     */
    fun back(context: Context): Boolean {
        val host = activityOf(context) ?: return false
        val fm = host.supportFragmentManager
        if (fm.backStackEntryCount == 0) return false
        fm.popBackStack()
        return true
    }
}
