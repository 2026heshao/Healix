package com.healix.app.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.healix.app.R

/**
 * 全局底部导航 3 Tab（设计规范 11.1，v6）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 载体决策（原型 go() 的 Android 等价）
 * ══════════════════════════════════════════════════════════════════════════
 * - 「记录」与「我的」同在 [MainActivity] 内以两个容器切换（pageHome / minePage），
 *   互切走 in_tab 转场（View 动画，同 Activity 内可靠重播）；
 * - 「助理」是独立的 [ChatActivity]（对话引擎与消息列表是独立栈），
 *   Tab 互切走 Activity 转场（in_tab / hold），视觉效果与原型一致；
 * - 二级页（状态详情/设置/知识库/调试/计划）不属于 TABS 白名单 ——
 *   tabbar 只存在于 Tab 页的布局里，二级页整体覆盖后自然"隐藏"，
 *   返回时恢复（原型 go() 白名单显隐的等价实现）。
 *
 * include 布局：view_tabbar.xml（64dp，绝对定位盖底，不占 flex 流）。
 */
internal object TabBar {

    /** Tab 页白名单（原型 TABS 的等价物）——只有这三页可见 tabbar。 */
    const val TAB_RECORD = 0
    const val TAB_ASSISTANT = 1
    const val TAB_MINE = 2

    /** MainActivity 收到的「启动后落在哪个 Tab」。 */
    const val EXTRA_TAB = "tab"

    /**
     * 绑定 tabbar：高亮 [active]，处理三段点击。
     *
     * [onRecord] / [onMine]：同 Activity 内的 Tab 页切换（MainActivity 传）。
     * 缺省时 = 从助理页切回对应 Tab（finish / 带 extra 重开主界面）。
     */
    fun bind(
        activity: Activity,
        active: Int,
        onRecord: (() -> Unit)? = null,
        onMine: (() -> Unit)? = null,
    ) {
        val record = activity.findViewById<TextView>(R.id.tabRecord)
        val assistant = activity.findViewById<TextView>(R.id.tabAssistant)
        val mine = activity.findViewById<TextView>(R.id.tabMine)

        select(activity, active)

        record.setOnClickListener { onRecord?.invoke() ?: chatTo(activity, TAB_RECORD) }
        mine.setOnClickListener { onMine?.invoke() ?: chatTo(activity, TAB_MINE) }

        // 已在助理页再点「助理」不动作（原型：同页重复 go() 不重播）
        assistant.setOnClickListener {
            if (activity !is ChatActivity) {
                activity.startActivity(Intent(activity, ChatActivity::class.java))
                activity.overridePendingTransition(R.anim.in_tab, R.anim.hold)
            }
        }

        bindImeGuard(activity)
    }

    /**
     * Tab 切换后重设高亮：MainActivity 的记录/我的在同一 Activity 内互切，
     * 切完必须同步 tabbar 三段颜色（bind 只在 onCreate 高亮一次，不够）。
     */
    fun select(activity: Activity, tab: Int) {
        highlight(activity.findViewById(R.id.tabRecord), tab == TAB_RECORD)
        highlight(activity.findViewById(R.id.tabAssistant), tab == TAB_ASSISTANT)
        highlight(activity.findViewById(R.id.tabMine), tab == TAB_MINE)
    }

    /**
     * 键盘守卫（11.6 交互遗留修复）：adjustResize 下键盘弹出时 WindowInsets
     * 把整个内容区（含锚底的 tabbar）一起顶到键盘之上 —— tabbar 悬浮在键盘上
     * 是真机最直观的破绽。
     *
     * 处理：ime 可见 → tabbar 隐藏，同时把输入条为让位 tabbar 而预留的
     * 64dp 抬升（tab_raise marginBottom）归零，输入条贴住键盘顶；
     * ime 收起 → 全部还原。选 GONE 而非 translate 动画：与 adjustResize 的
     * 同帧重排叠加动画会闪烁，瞬时显隐反而干净。
     *
     * 两处宿主（activity_main / activity_chat）都有 @+id/tabbar include 与
     * @+id/input 输入条，且都在 onCreate 调 [bind] —— 逻辑放这一处，
     * ChatActivity 侧零改动生效，两页行为必然一致。
     *
     * 限制：ime insets 精确上报需 API 30+（minSdk 29，目标机型 Magic6 Pro
     * 为 API 34）；API 29 上拿不到 ime 可见性，行为退回现状（tabbar 随键盘上移）。
     */
    private fun bindImeGuard(activity: Activity) {
        val root = activity.findViewById<View>(android.R.id.content) ?: return
        val tabbar = activity.findViewById<View>(R.id.tabbar) ?: return
        val input = activity.findViewById<View>(R.id.input) ?: return
        val bar = input.parent as? ViewGroup ?: return
        val lp = bar.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val raisePx = lp.bottomMargin // 布局里的 tab_raise 抬升量

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val imeVisible = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
            tabbar.visibility = if (imeVisible) View.GONE else View.VISIBLE
            lp.bottomMargin = if (imeVisible) 0 else raisePx
            bar.layoutParams = lp // 重新赋值触发 requestLayout
            insets // 不消费，根布局 fitsSystemWindows 照常工作
        }
    }

    /** 助理页 → 其它 Tab：记录 = 返回栈顶下的主界面；我的 = 重开主界面并定位。 */
    private fun chatTo(activity: Activity, target: Int) {
        if (target == TAB_MINE) {
            activity.startActivity(
                Intent(activity, MainActivity::class.java)
                    .putExtra(EXTRA_TAB, TAB_MINE)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        activity.finish()
        activity.overridePendingTransition(R.anim.in_tab, R.anim.hold)
    }

    /** 二级页进入：统一 in_fwd 转场（11.2 前进）。 */
    fun openSecondary(activity: Activity, intent: Intent) {
        activity.startActivity(intent)
        activity.overridePendingTransition(R.anim.in_fwd, R.anim.out_fwd)
    }

    private fun highlight(tab: TextView, on: Boolean) {
        tab.setTextColor(
            ContextCompat.getColor(
                tab.context,
                if (on) R.color.text_1 else R.color.text_2,
            ),
        )
    }
}
