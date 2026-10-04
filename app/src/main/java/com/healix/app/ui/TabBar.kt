package com.healix.app.ui

import android.app.Activity
import android.content.Intent
import android.widget.TextView
import androidx.core.content.ContextCompat
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

        highlight(record, active == TAB_RECORD)
        highlight(assistant, active == TAB_ASSISTANT)
        highlight(mine, active == TAB_MINE)

        record.setOnClickListener { onRecord?.invoke() ?: chatTo(activity, TAB_RECORD) }
        mine.setOnClickListener { onMine?.invoke() ?: chatTo(activity, TAB_MINE) }

        // 已在助理页再点「助理」不动作（原型：同页重复 go() 不重播）
        assistant.setOnClickListener {
            if (activity !is ChatActivity) {
                activity.startActivity(Intent(activity, ChatActivity::class.java))
                activity.overridePendingTransition(R.anim.in_tab, R.anim.hold)
            }
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

    /** 二级页返回：统一 in_back 转场（11.2 后退，返回页从 -22% 滑入）。 */
    fun backOut(activity: Activity) {
        activity.finish()
        activity.overridePendingTransition(R.anim.in_back, R.anim.out_back)
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
