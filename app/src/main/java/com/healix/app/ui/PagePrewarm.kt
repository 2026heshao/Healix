package com.healix.app.ui

import android.app.Activity
import android.os.Looper
import android.view.LayoutInflater
import com.healix.app.R

/**
 * 二级页布局**预热**（2026-10-08）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 解决什么
 * ══════════════════════════════════════════════════════════════════════════
 * 用户真机反馈：「**每次启动应用第一次**进入设置页面会掉帧」。这个限定词很关键 ——
 * 同一次会话里第二次、第三次进入都不掉，说明瓶颈不在「布局有多少 view」这个常量，
 * 而在**冷进程的首次 inflate**：
 * - 各 View 子类（`SwitchMaterial` 等）首次加载要跑类校验 + 解释执行；
 * - `Class.forName` + 反射构造 + `obtainStyledAttributes` 的资源解析首遍最慢；
 * - 这些都是**一次性**的 —— 做过一次，后续 inflate 直接吃缓存。
 *
 * 于是「预热」就是最对症的解法：在**应用启动后的主线程空闲期**把高频二级页的布局
 * 先 inflate 一遍再丢弃。view 不要，只要那份被热起来的类加载 / 资源缓存 / JIT。
 * 代价落在用户没在交互的空闲窗口，收益落在用户真正点进页面那一帧。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么挂在 IdleHandler 上、且一次只热一个
 * ══════════════════════════════════════════════════════════════════════════
 * - `Looper.addIdleHandler`：只在主线程**消息队列空了**才回调 —— 不会和首帧渲染、
 *   用户输入抢时间片。若队列一直不空（例如用户在猛滑首页），本预热**自动谦让**、
 *   完全不执行，宁可没有收益也不制造卡顿。
 * - 一次空闲只热**一个**布局：十几个页面的布局一次热完是几十毫秒的连续停顿，
 *   哪怕在空闲期也可能撞上用户忽然而至的触摸。拆成每帧一个，单个停顿压在一个
 *   inflate 的量级内。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 边界（诚实声明）
 * ══════════════════════════════════════════════════════════════════════════
 * - 预热**只是把冷启动那一份开销提前付掉**，不减少任何页面自身的 inflate 成本 ——
 *   真正的减负靠布局下沉（ViewStub，见 `layout/group_settings_ai.xml` 等）与
 *   渲染幂等化（见 `SettingsFragment.renderGoals` / `PlanReviewFragment.observe`）。
 * - 失败一律吞掉（[runCatching]）：预热是锦上添花，任何机型 / 主题差异都不该让
 *   应用起不来。
 * - 进程级只做一次（[scheduled]）：Activity 旋转重建不重复预热。
 */
internal object PagePrewarm {

    /**
     * 预热目标 = **高频二级页**的布局（含其懒建子布局）。
     *
     * 取舍依据：这些页从首页一级入口一两次点击即达（计划页在记录页顶部条、
     * 设置页在「我的」页、状态详情在记录页指标卡），是用户最可能踩到冷 inflate 的路径。
     * 低频页（就医准备材料、调试页、规则库…）收益小、不值得占空闲期。
     *
     * ⚠️ 顺序即优先级：`IdleHandler` 按序取，先热的先受益。
     */
    private val LAYOUTS: List<Int> = listOf(
        R.layout.fragment_settings,
        R.layout.group_settings_ai,
        R.layout.fragment_plan_review,
        R.layout.group_plan_review_review,
        R.layout.fragment_status_detail,
        R.layout.fragment_personal_info,
    )

    /** 待预热队列（首个空闲回调开始消费）。 */
    private var queue: ArrayDeque<Int>? = null

    /** 是否已排期（进程级一次）。 */
    private var scheduled: Boolean = false

    /**
     * 排期预热（宿主在 `onCreate` 调一次）。
     *
     * 幂等：重复调用只有第一次生效 —— 预热是全局性的，与调用方实例无关。
     * 取 `activity.layoutInflater`（而非 `LayoutInflater.from(context)`）——
     * 必须带宿主主题，否则 Material 控件按错主题解析属性，热出来的缓存**不能用**
     * （更糟：可能因主题缺项抛异常，被 [runCatching] 吞掉后变成"白预热一场"）。
     */
    fun schedule(activity: Activity) {
        if (scheduled) return
        scheduled = true
        queue = ArrayDeque(LAYOUTS)
        val inflater: LayoutInflater = activity.layoutInflater

        Looper.myQueue().addIdleHandler {
            val next = queue?.removeFirstOrNull() ?: return@addIdleHandler false
            // inflate 出来即丢弃：要的是类加载 / 资源解析 / JIT 这些一次性缓存的副作用。
            runCatching { inflater.inflate(next, null, false) }
            // 还有剩余 → 保留本 handler，等下一次空闲继续；队列空 → 注销自己。
            queue?.isNotEmpty() == true
        }
    }
}
