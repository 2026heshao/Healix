package com.healix.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.healix.app.R
import com.healix.app.perf.PerfProbe

/**
 * 二级页导航（v8 T03：二级页 Fragment 化；v8 后续：replace → add+hide/show 保活；
 * 2026-10-06 结构改造：**退出保活** —— 弃 `addToBackStack`/`popBackStack`，手工持栈）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么不再用 Activity
 * ══════════════════════════════════════════════════════════════════════════
 * 此前每个二级页都是独立 Activity：进入时系统起一个新窗口并走窗口转场，
 * 而新页 `onCreate` 要在**主线程**膨胀 14–23KB 布局并读库 —— 新旧窗口一起冻结，
 * 实测「前进/后退各卡约 1 秒」（需求 1 的根因之一）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么用 add + hide/show，而不是 replace
 * ══════════════════════════════════════════════════════════════════════════
 * `replace` 在打开 B 时把 A 的视图整个销毁；按返回弹栈时 FragmentManager 要在退出
 * 动画**启动的同一帧**里对 A 重新 onCreateView + onViewCreated（14–23KB 布局膨胀 +
 * 读库 + 绑表）→ 帧掉在 out_back 动画开头。Jetpack Navigation 的 FragmentNavigator
 * 内部同样是 replace，亦踩此坑。
 *
 * 解法（YouTube 式导航 / Ian Lake 建议，与本项目 Tab 页 `tabContainer` 上的
 * add+show/hide 同构）：`add() + hide(下层)` —— **下层页视图全程存活**，返回零重建。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 2026-10-06 结构改造：为什么连「退出的页」也要保活
 * ══════════════════════════════════════════════════════════════════════════
 * 真机 v3 探针（`docs/v8/专项-二级页转场卡顿-2026-10-05.md` §4.0 续2）实测，进入方向
 * 单次耗时 33–39ms ≈ 2.0–2.3 帧（60Hz），构成：
 * - 15–20ms = `commit()` 投递的事务消息被主线程 synchronization barrier 推迟到下一帧；
 * - 12–14ms = 事务消息内部的 **inflate + onCreateView**（占该消息 ~70%）；
 * - 5–6ms  = onStart + onResume。
 * 事务消息 18–20ms **> 16.7ms 帧预算** → 进入动画起手必掉 1 帧（用户所说的「进入瞬间一顿」）。
 *
 * 旧实现（`addToBackStack` + `popBackStack`）的 [back] 会**销毁顶层页**（实测
 * `frag.viewDestroyed → frag.destroyed`，销毁被 180ms `out_back` 推迟），
 * 于是**每次进入同一页都要重付全额 inflate**。
 *
 * 本改造把「退出的页」也保活（下称 **parked**）：
 * - [back] 不再弹栈销毁，而是 `hide(顶层) + show(下层)` —— 顶层视图保留，可复用；
 * - [open] 若容器内已有**同 tag 且 arguments 相同**的实例（含 parked），直接 `show()`
 *   复用 → **零 inflate**；否则先移除同名旧实例再 `add()` 新实例；
 * - 事务改用 `commitNowAllowingStateLoss()` **同步执行**（同 Tab 范式
 *   `MainActivity.applyTab`）—— 提交与执行不再经消息队列，复用路径只剩 `show()`
 *   的 2–5ms 量级，不再顶爆帧预算。
 *
 * 收益：**重复进入同一页的开销从 33–39ms 降到个位数**。首开一页仍需全额 inflate
 * （与改造前同量级）—— 首开开销另走「布局下沉」专项（见该文档 §七 Q3）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 栈语义（唯一事实来源 = 本对象的 [stack]，不再用 FragmentManager 回退栈）
 * ══════════════════════════════════════════════════════════════════════════
 * [stack] 存**页 tag 序列，栈顶在末尾**。容器内由此分成两类页：
 * - **在栈页**：tag 属于 [stack]，其中恰有一个可见（= 栈顶 = `stack.last()`）；
 * - **parked 页**：tag 不属于 [stack]，视图存活但 hidden，供 [open] 复用。
 *
 * 「是否停在二级页」判据 = **是否有可见页**（[isOpen]），而非「容器是否非空」——
 * 全部 park 之后容器里仍有隐藏子视图，但语义上已经回到宿主。宿主据此维护
 * `pageContainer` 的命中态（[onPageStackChanged]）。
 *
 * ⚠️ 进程重建：[stack] 是**进程内**状态，Activity 重建时必须由宿主经 [saveState] /
 * [restoreState] 还原 —— 丢失会导致返回键失效、把用户死锁在二级页。万一仍然丢失，
 * [pruneStack] 会退化为「把当前可见页当作唯一栈层」：**宁可少一层，不可死锁**。
 *
 * 其他取舍（与 Tab 页一致）：
 * - **被 park 的页不会在「从上层页回来」时重走 onViewCreated**，页面显示的是离开时的
 *   快照 —— 业务页若需刷新，应监听 [Fragment.onHiddenChanged]（Record/Assistant
 *   已用此范式），而非依赖 onResume（hide 不影响生命周期，它不会再触发）。
 * - ⚠️ `pageContainer` 在 `activity_main.xml` 里排在 tabbar **之后**（z 序更高），
 *   二级页天然盖住 tabbar —— 无需显式隐藏导航栏。但容器**没有可见页时不能吃点击**，
 *   故其 `isClickable` 由宿主按 [isOpen] 维护。
 *
 * 入口签名一律收 [Context]（而非 FragmentActivity）：调用方有 `Activity`、
 * `View.context` 等不同静态类型，统一在 [activityOf] 里沿 ContextWrapper 链解析，
 * 省掉调用点上的强转与 `?.` 噪音。
 */
internal object NavHost {

    /** 标签前缀：二级页统一携带，调试时一眼分辨来源。 */
    private const val TAG_PREFIX = "page:"

    // ── 二级页标签（唯一事实来源）──────────────────────────────────────────
    // 调用点**不得**各写一份字面量：重复字面量正是"改一处漏一处"的温床
    // （check_kotlin 的重复定义提示就盯着这类）。标签同时作为 Fragment 的 tag，
    // dumpsys / 调试时能直接看出停在哪一页。
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

    /** Activity 重建时保存页面栈的 Bundle 键（见 [saveState] / [restoreState]）。 */
    private const val KEY_STACK = "healix.navhost.pageStack"

    /**
     * 二级页栈：**页 tag 序列，栈顶在末尾**。栈语义的唯一事实来源（不依赖
     * FragmentManager 回退栈 —— 本设计不用回退栈）。只在主线程读写。
     */
    private val stack = ArrayList<String>()

    /**
     * 栈变化钩子（宿主接线）：宿主据此同步 `pageContainer` 命中态。
     *
     * 为什么需要钩子：结构改造后已无 FragmentManager 回退栈，原先的
     * `addOnBackStackChangedListener` 事件源消失 —— 把「栈变了」这一事件源从
     * FragmentManager 搬到 [NavHost] 自己。单进程单 Activity 场景下只需一个槽位；
     * 宿主在 `onCreate` 覆盖注册（重建时自然指向新实例，不会累积）。
     */
    var onPageStackChanged: (() -> Unit)? = null

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
     * 判据是**是否有可见页**而非「容器是否非空」：keep-alive 下 parked 页仍留在容器里，
     * 但已经不在栈上、不可见，语义上等同已回到宿主。
     */
    fun isOpen(context: Context): Boolean {
        val fm = activityOf(context)?.supportFragmentManager ?: return false
        return visiblePage(fm) != null
    }

    /**
     * 打开二级页：进 `pageContainer`（hide 当前可见页 + add/show 目标页，**都不销毁**）。
     *
     * 动画参数是 `(enter, exit)`：
     * - 入场 = in_fwd（22%→0）；被压入下层的旧页走 out_fwd（0→-22%）——「二级页 → 二级页」
     *   也是标准前进转场；从 Tab 页首开时容器本为空，exit 无对象、自然不播。
     * - 弹栈方向的 `(in_back, out_back)` 在 [back] 里显式传入 —— 手工事务不经过
     *   FragmentManager 的 pop 路径，框架不会替我们换成 pop 动画。
     *
     * 动画只有 `translate`、无 alpha 交叉（同窗口内不会露出旧窗口，恒不透明仍成立）。
     *
     * 复用规则（本设计的核心收益）：
     * - 容器内已有**同 tag 且 arguments 相同**的实例 → `show()` 复用，**零 inflate**；
     * - 同名但 arguments 不同（如 `SettingsFragment.newInstance(focusGoal = true)`
     *   与无参 `SettingsFragment()` 共用 `PAGE_SETTINGS`）→ 先 `remove()` 旧实例再 add，
     *   保证**同名实例唯一**（否则 `findFragmentByTag` 会与可见页脱节，parked 旧实例
     *   会与新实例并存）。
     *
     * 已在栈顶时**直接返回**：同一页被重复触发（双击）不应压入重复实例。
     *
     * 找不到宿主 Activity（理论上不会）时静默忽略 —— 不抛异常拖垮入口点击。
     */
    fun open(context: Context, fragment: Fragment, tag: String) {
        val host = activityOf(context) ?: return
        val fm = host.supportFragmentManager
        val full = TAG_PREFIX + tag

        val current = visiblePage(fm)
        val existing = fm.findFragmentByTag(full)
        // 目标页已在栈顶（双击 / 重复触发）：不做任何事务 —— 含 arguments 变化的情形
        // （同页重入不换参）；当前无此可达路径，标注在此以免后人误以为漏了一个分支。
        if (existing != null && existing === current) return

        // 探针锚点（只读观测）：转场启动前打一行 MARK，供判读时把 LONG_FRAME / SLOW_MSG
        // 对齐到「这次 open」。探针关闭时 PerfProbe.mark 直接 return，零开销。
        PerfProbe.mark("nav.open:" + fragment::class.java.simpleName)
        // 探针生命周期打点（只读观测）：探针开启时给 FragmentManager 注册生命周期回调，
        // 把本事务内各页的 frag.created/viewCreated/…/resumed 各写一行 MARK，拆解这笔
        // 事务消息内部的子阶段（inflate vs 生命周期）。探针关闭时不注册任何回调，零开销。
        PerfProbe.ensureLifecycleMarks(fm, R.id.pageContainer)

        // hide 前对旧页 view clearFocus，防止焦点/光标残留在不可见页上
        // （输入法、SwipeController 的按下态都挂在焦点视图上）。
        current?.view?.clearFocus()

        val reused = existing?.takeIf { sameArguments(it, fragment) }
        val tx = fm.beginTransaction()
            // 与 Tab 页 applyTab（MainActivity.kt:268）同范式，置 true 后 FragmentManager
            // 可重排/合并本事务的操作。保留此写法与 Tab 范式形式统一；本事务只做
            // hide + add/show，不存在「同一 Fragment 先 add 后 remove」这类会被重排改变
            // 语义的组合，故不改变最终可见性结果。
            .setReorderingAllowed(true)
            .setCustomAnimations(R.anim.in_fwd, R.anim.out_fwd)
        if (current != null) tx.hide(current)
        if (reused != null) {
            tx.show(reused)
        } else {
            if (existing != null) tx.remove(existing)
            tx.add(R.id.pageContainer, fragment, full)
        }
        // commitNowAllowingStateLoss（同 applyTab）：**同步执行**。这正是本改造压掉
        // 「推迟一帧」的手段 —— `commit()` 投递的是同步消息，会被主线程 synchronization
        // barrier 挡到下一帧（实测 15–20ms）；同步执行则当场完成。
        // 用 AllowingStateLoss 版而非 commitNow：本方法可能晚于 onSaveInstanceState 到达
        // （外部入口重开等路径），与 applyTab 同一理由 —— 页面显隐是纯视图状态，
        // 进程重建后由栈还原 + ensureRestoredVisibility 重放，允许丢失无害。
        tx.commitNowAllowingStateLoss()

        // 入栈：先移除同名项再追加 —— 保证栈内 tag 唯一且栈顶恒为最后一项。
        stack.remove(full)
        stack.add(full)
        notifyStackChanged()
    }

    /**
     * 返回：有在栈页则退一层并返回 true（调用方据此判定"返回键已被消费"）。
     *
     * 与改造前的关键差别：**退出的页不销毁**。此前走 `popBackStack()` = 逆向
     * `remove(顶层)`，页面与其视图被销毁，下次进入重付全额 inflate；现在只 `hide(顶层)`
     * —— 视图存活成 parked，下次 [open] 同一页直接 `show()` 复用。
     *
     * 动画：`(in_back, out_back)` 显式传在 (enter, exit) 槽（手工事务不走 pop 路径）。
     * 被退出的页视图**永不销毁**，故 out_back 动画期间没有任何"同一帧重建"的竞速。
     *
     * 栈空（或栈底页已不存在）时返回 false，由宿主决定后续语义（Tab 回记录页 / 退出）。
     */
    fun back(context: Context): Boolean {
        val host = activityOf(context) ?: return false
        val fm = host.supportFragmentManager
        pruneStack(fm)
        val topTag = stack.lastOrNull() ?: return false
        val top = fm.findFragmentByTag(topTag) ?: return false

        // 探针锚点（只读观测）：弹栈前打一行 MARK（探针关闭时 PerfProbe.mark 零开销）。
        PerfProbe.mark("nav.back")
        // 探针生命周期打点（只读观测）：同 open，探针关闭态零注册、零开销。
        PerfProbe.ensureLifecycleMarks(fm, R.id.pageContainer)

        // 与 open 对称：退出的页视图会**存活**（只 hide 不 remove），焦点必须显式清掉，
        // 否则光标 / 输入法会残留在已不可见的页上，下次复用时不刷新就得面对错位的光标。
        top.view?.clearFocus()

        stack.removeAt(stack.size - 1)
        val prev = stack.lastOrNull()?.let { fm.findFragmentByTag(it) }

        fm.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(R.anim.in_back, R.anim.out_back)
            .apply {
                hide(top)
                if (prev != null) show(prev)
            }
            .commitNowAllowingStateLoss()

        notifyStackChanged()
        return true
    }

    /**
     * Activity 重建保险：把容器内可见性校正为「只有栈顶页可见」。
     *
     * FragmentManager 恢复时各 Fragment 与其 hidden 状态一并重建，但实现细节上
     * hidden 的持久化口径在不同 androidx 版本间不完全一致 —— 万一没恢复，parked 页
     * 会全部以可见态堆在栈顶之下（视觉上被盖住、平移动画期间会露馅）。这里在宿主
     * `onCreate` 恢复完成后兜底一次：该 show 的 show、该 hide 的 hide，状态正确时是
     * 纯 no-op（不产生任何事务）。无视图依赖，恢复期调用安全。
     */
    fun ensureRestoredVisibility(context: Context) {
        val fm = activityOf(context)?.supportFragmentManager ?: return
        pruneStack(fm)
        val topTag = stack.lastOrNull() ?: return
        val pages = pagesOf(fm)
        if (pages.isEmpty()) return
        val tx = fm.beginTransaction()
        var changed = false
        for (page in pages) {
            val shouldShow = page.tag == topTag
            if (page.isHidden == shouldShow) {
                changed = true
                if (shouldShow) tx.show(page) else tx.hide(page)
            }
        }
        if (changed) tx.commitNowAllowingStateLoss()
        notifyStackChanged()
    }

    /**
     * 保存页面栈（宿主在 `onSaveInstanceState` 调用）。
     *
     * 为什么必须由宿主经 Bundle 持久化：[stack] 是本进程对象的内存状态，Activity 重建
     * （旋转 / 后台被杀后恢复）会丢掉它，而容器里的页却被 FragmentManager 恢复了 ——
     * 栈与页面一旦不同步，返回键就会失效（[back] 找不到栈顶）。宁可多一个 Bundle 往返，
     * 也不要用户被死锁在二级页里。
     */
    fun saveState(outState: Bundle) {
        outState.putStringArrayList(KEY_STACK, ArrayList(stack))
    }

    /** 还原页面栈（宿主在 `onCreate` 调用，**先于** [ensureRestoredVisibility]）。
     *  `state == null`（全新启动）即清空 —— 进程级单例必须显式复位，不能沿用手机会话的栈。 */
    fun restoreState(state: Bundle?) {
        stack.clear()
        state?.getStringArrayList(KEY_STACK)?.let { stack.addAll(it) }
    }

    // ── 内部实现 ──────────────────────────────────────────────────

    /** 容器内的二级页（按 add 序）。 */
    private fun pagesOf(fm: FragmentManager): List<Fragment> =
        fm.fragments.filter { it.id == R.id.pageContainer }

    /** 当前可见的二级页（= 栈顶那一页）。全 park 时为 null。 */
    private fun visiblePage(fm: FragmentManager): Fragment? =
        pagesOf(fm).lastOrNull { !it.isHidden }

    /**
     * 修剪栈：丢掉已不存在的页（FragmentManager 可能因恢复差异丢掉个别页），
     * 并在**栈整体丢失**时退化为「把当前可见页当作唯一栈层」。
     *
     * 兜底方向是刻意的：宁可少一层（用户多点一次返回），也不能让 [back] 返回 false
     * 而把用户死锁在二级页里。
     */
    private fun pruneStack(fm: FragmentManager) {
        val alive = stack.filter { fm.findFragmentByTag(it) != null }
        stack.clear()
        stack.addAll(alive)
        if (stack.isEmpty()) {
            visiblePage(fm)?.tag?.let { stack.add(it) }
        }
    }

    /**
     * arguments 等价判定（[open] 的复用前提）。
     *
     * 为什么不用 `Bundle.equals`：其相等语义依赖实现细节，一旦退化为 Object 同一性，
     * 复用会**静默失效**（每次都走 remove + add，白付 inflate）。这里按
     * 「键集合 + 各值字符串形式」逐项比较，取值只可能是 Int / String / Boolean / null（见各页
     * `newInstance`），字符串形式足以区分。
     */
    private fun sameArguments(a: Fragment, b: Fragment): Boolean {
        val ba = a.arguments
        val bb = b.arguments
        if (ba == null || bb == null) return ba == null && bb == null
        if (ba.size() != bb.size()) return false
        for (key in ba.keySet()) {
            if (!bb.containsKey(key)) return false
            if (ba.get(key)?.toString() != bb.get(key)?.toString()) return false
        }
        return true
    }

    /** 通知宿主栈已变化；宿主回调异常不得反过来影响导航（同 CrashCatcher 哲学）。 */
    private fun notifyStackChanged() {
        runCatching { onPageStackChanged?.invoke() }
    }
}
