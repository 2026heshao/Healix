package com.healix.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.healix.app.R

/**
 * 二级页导航（v8 T03：二级页 Fragment 化；v8 后续：replace → add+hide/show 保活）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么不再用 Activity
 * ══════════════════════════════════════════════════════════════════════════
 * 此前每个二级页都是独立 Activity：进入时系统起一个新窗口并走窗口转场，
 * 而新页 `onCreate` 要在**主线程**膨胀 14–23KB 布局并读库 —— 新旧窗口一起冻结，
 * 实测「前进/后退各卡约 1 秒」（需求 1 的根因之一）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么用 add + hide/show，而不是 replace（keep-alive）
 * ══════════════════════════════════════════════════════════════════════════
 * 真机（MagicOS）反馈 Fragment 化后二级页**退出仍掉帧**。根因：`replace` 在
 * 打开 B 时把 A 的视图整个销毁；按返回弹栈时 FragmentManager 要在退出动画
 * **启动的同一帧**里对 A 重新 onCreateView + onViewCreated（14–23KB 布局
 * 膨胀 + 读库 + 绑表）→ 帧掉在 out_back 动画开头。这也是 Jetpack Navigation
 * FragmentNavigator（内部同样是 replace）的经典坑。
 *
 * 解法（YouTube 式导航 / Ian Lake 建议，与本项目 Tab 页 `tabContainer` 上的
 * add+show/hide 同构）：`add() + hide(下层) + addToBackStack()` ——
 * - **下层页视图全程存活**：返回弹栈时 FragmentManager 自动逆向回放事务
 *   （remove(顶层) + show(下层)），下层**零重建**，掉帧环节消失；
 * - 滚动位置、输入框草稿等视图状态天然保留（没有被销毁过）；
 * - [FragmentTransaction.setCustomAnimations] 对 show/hide 引起的可见性变化
 *   同样生效，四参 `(enter, exit, popEnter, popExit)` 语义不变；
 * - 被弹出的顶层页视图存活到 popExit 动画播完才销毁，动画期间帧纯净。
 *
 * ⚠️ 保活的代价（与 Tab 页一致的取舍）：下层页返回时**不会**重走
 * onViewCreated，页面显示的是打开时的快照 —— 业务页若需在「从上层页回来」
 * 时刷新，应监听 [Fragment.onHiddenChanged]（Tab 页 Record/Assistant 已用
 * 此范式），而非依赖 onResume（hide 不影响生命周期，它不会再触发）。
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

    // ── 二级页标签（唯一事实来源）──────────────────────────────────────────
    // 调用点**不得**各写一份字面量：重复字面量正是"改一处漏一处"的温床
    // （check_kotlin 的重复定义提示就盯着这类）。标签同时作为 FragmentManager
    // 的 back stack name，dumpsys / 调试时能直接看出停在哪一页。
    const val PAGE_STATUS_DETAIL = "status_detail"
    const val PAGE_SETTINGS = "settings"
    const val PAGE_PLAN_REVIEW = "plan_review"
    const val PAGE_PERSONAL_INFO = "personal_info"
    const val PAGE_RESOURCES = "resources"
    const val PAGE_PRESETS = "presets"
    const val PAGE_KNOWLEDGE = "knowledge"
    const val PAGE_DEBUG = "debug"

    /** 就医准备材料（v8 需求 9 功能 3；入口在「我的」页数据组）。 */
    const val PAGE_MEDICAL = "medical"

    /** 从任意 Context（含 ContextWrapper 链）里找出宿主 FragmentActivity。 */
    fun activityOf(context: Context): FragmentActivity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is FragmentActivity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /** 宿主当前是否停在某个二级页（返回键语义 / 输入法守卫都要问它）。
     *
     * keep-alive 下语义不变：回退栈仍是「二级页层数」的唯一事实来源
     * （open 时 addToBackStack、pop 时自动减一），Tab 页不进栈。
     */
    fun isOpen(context: Context): Boolean =
        activityOf(context)?.supportFragmentManager?.backStackEntryCount?.let { it > 0 } ?: false

    /**
     * 打开二级页：进 `pageContainer`（add + hide 下层，**不销毁**下层视图）。
     *
     * 动画参数是 `(enter, exit, popEnter, popExit)`：
     * - 入场 = in_fwd（22%→0）；被压入下层的旧页走 out_fwd（0→-22%），
     *   于是「二级页 → 二级页」（如状态详情 → 计划页）也是标准的前进转场；
     *   **从 Tab 页首开二级页时容器本为空，exit 无对象、自然不播** —— 一组参数覆盖两种情形。
     * - 弹栈 = popEnter 走 in_back（-22%→0，下层页从左归位）、popExit 走 out_back
     *   （0→22%，被弹出的页向右滑出）—— 与「前进/后退」的横向队列直觉一致。
     *
     * 动画只有 `translate`、无 alpha 交叉（同窗口内不会露出旧窗口，恒不透明仍成立）。
     *
     * keep-alive 语义：
     * - 旧页只 hide（视图存活、生命周期不动），弹栈时 FragmentManager 自动
     *   逆向回放本事务（remove(顶层) + show(下层)）→ 返回零重建；
     * - hide 前对旧页 view [View.clearFocus]，防止焦点/光标残留在不可见页上
     *   （输入法、SwipeController 的按下态都挂在焦点视图上）。
     *
     * 多层叠加时 z 序天然正确：同容器内 Fragment 按添加顺序绘制，后 add 的
     * （栈更上层）盖在前者之上，与回退栈顺序一致。
     *
     * 找不到宿主 Activity（理论上不会）时静默忽略 —— 不抛异常拖垮入口点击。
     */
    fun open(context: Context, fragment: Fragment, tag: String) {
        val host = activityOf(context) ?: return
        val fm = host.supportFragmentManager
        // 容器里最上层（最后 add）的既有页；从 Tab 页首开时为 null。
        // 不用 findFragmentById：多 Fragment 共容器时其返回顺序依赖实现细节，
        // fm.fragments 按 add 序排列，last() 才是确定性的"栈顶"。
        val current = fm.fragments.lastOrNull { it.id == R.id.pageContainer }
        current?.view?.clearFocus()
        fm.beginTransaction()
            .setCustomAnimations(R.anim.in_fwd, R.anim.out_fwd, R.anim.in_back, R.anim.out_back)
            .apply { if (current != null) hide(current) }
            .add(R.id.pageContainer, fragment, TAG_PREFIX + tag)
            .addToBackStack(TAG_PREFIX + tag)
            .commit()
    }

    /**
     * 返回：有二级页则弹栈并返回 true（调用方据此判定"返回键已被消费"）。
     * 用 `popBackStack()`（异步）而非 Immediate —— 异步才能让 out_back 播完。
     *
     * keep-alive 下此函数逻辑不变，靠 FragmentManager 的自动逆向回放兜底：
     * - pop 会把 open 事务反向执行 —— remove(顶层) + **show(下层)**，
     *   下层页零重建、不触发 onViewCreated，popEnter（in_back）照播；
     * - 被弹出的顶层页视图存活到 popExit（out_back）动画播完才销毁，
     *   动画期间没有"同一帧重建下层视图"的竞速，掉帧环节消失。
     */
    fun back(context: Context): Boolean {
        val host = activityOf(context) ?: return false
        val fm = host.supportFragmentManager
        if (fm.backStackEntryCount == 0) return false
        fm.popBackStack()
        return true
    }

    /**
     * Activity 重建保险：校验回退栈非空时容器里**只有栈顶页可见**。
     *
     * FragmentManager 恢复回退栈时会重建各 Fragment 并回放 hidden 状态，但
     * hidden 状态的持久化在不同 androidx 版本间口径不一 —— 万一没恢复，下层页
     * 会全部以可见态堆在栈顶之下（视觉上被盖住、平移动画期间会露馅）。这里
     * 在宿主 onCreate 恢复完成后兜底一次：该 show 的 show、该 hide 的 hide，
     * 状态正确时是纯 no-op（不产生任何事务）。无视图依赖，恢复期调用安全。
     */
    fun ensureRestoredVisibility(context: Context) {
        val fm = activityOf(context)?.supportFragmentManager ?: return
        if (fm.backStackEntryCount == 0) return
        val pages = fm.fragments.filter { it.id == R.id.pageContainer }
        if (pages.isEmpty()) return
        val top = pages.last()
        val tx = fm.beginTransaction()
        var changed = false
        for (page in pages) {
            val shouldShow = page === top
            if (page.isHidden == shouldShow) {
                changed = true
                if (shouldShow) tx.show(page) else tx.hide(page)
            }
        }
        if (changed) tx.commitNowAllowingStateLoss()
    }
}
