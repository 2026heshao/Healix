package com.healix.app.ui

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.healix.app.R

/**
 * 全局底部导航 3 Tab（设计规范 11.1，v6；v8 需求 1/2 重写）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 载体决策（v8：Tab 即时响应的根因修复）
 * ══════════════════════════════════════════════════════════════════════════
 * 三个 Tab **全部不再经过 Activity 窗口转场**：
 * - 三页均为宿主 [MainActivity] 内的**常驻 Fragment**（记录 [RecordFragment] /
 *   助理 [AssistantFragment] / 我的 [MineFragment]），挂在 `tabContainer` 上，
 *   `add` 一次 + `show/hide` 切换（**零动画**，拍板 #2）；
 * - 此前「助理」是 Fragment、「记录 / 我的」是 View 容器 —— v8 起统一为 Fragment，
 *   导航范式唯一（无半迁移）；
 * - 旧的 `ChatActivity` 已删除 —— 此前 `chatTo()` 用
 *   `startActivity(CLEAR_TOP|SINGLE_TOP)` 重启 MainActivity 并 `finish()`，
 *   每次都吃一次窗口转场，这是「Tab 点击延迟偏高」的根因。
 *
 * **按下即高亮**：`ACTION_DOWN` 时立即 [select] 新 Tab 的文字色，
 * 内容切换由抬起后的 `onTab` 回调完成 —— 与微信底部 Tab 的手感一致。
 *
 * - 二级页（状态详情/设置/计划/个人信息/知识库/资源/预设/调试）：
 *   v8 T03 起**八页全部**改为宿主 [MainActivity] 内 `pageContainer` 上的 Fragment，
 *   统一经 [NavHost.open] 进入（零窗口转场）；`tabbar` 被二级页整体覆盖后自然"隐藏"。
 *   本对象**不再承担"开新页"职责**（原 `openSecondary` 已随最后一批迁移删除）。
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
     * 绑定 tabbar：高亮 [active]，三段点击都交给 [onTab]（宿主负责切 Fragment/View）。
     *
     * 高亮在 `ACTION_DOWN` 立即生效；`setOnTouchListener` 返回 false，
     * 不消费事件 —— 按压态、涟漪、点击照旧（v8 需求 2）。
     */
    fun bind(activity: Activity, active: Int, onTab: (Int) -> Unit) {
        val record = activity.findViewById<TextView>(R.id.tabRecord)
        val assistant = activity.findViewById<TextView>(R.id.tabAssistant)
        val mine = activity.findViewById<TextView>(R.id.tabMine)

        select(activity, active)

        listOf(record to TAB_RECORD, assistant to TAB_ASSISTANT, mine to TAB_MINE)
            .forEach { (view, tab) ->
                view.setOnTouchListener { _, ev ->
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                        // 按下即高亮：不等内容切换（内容切换有 IO/布局成本，色变没有）
                        select(activity, tab)
                    }
                    false // 不消费：onClick 照常触发真正的内容切换
                }
                view.setOnClickListener { onTab(tab) }
            }
    }

    /**
     * Tab 切换后重设高亮：三个 Tab 的内容都在同一 Activity 里，
     * 切完必须同步 tabbar 三段颜色（bind 只在 onCreate 高亮一次，不够）。
     */
    fun select(activity: Activity, tab: Int) {
        highlight(activity.findViewById(R.id.tabRecord), tab == TAB_RECORD)
        highlight(activity.findViewById(R.id.tabAssistant), tab == TAB_ASSISTANT)
        highlight(activity.findViewById(R.id.tabMine), tab == TAB_MINE)
    }

    /**
     * 键盘守卫（11.6 交互遗留修复 + v8 Fragment 化改造）：adjustResize 下
     * 键盘弹出时 WindowInsets 会把整个内容区（含锚底的 tabbar）一起顶到
     * 键盘之上 —— tabbar 悬浮在键盘上是最直观的破绽。
     *
     * 处理：ime 可见 → tabbar 隐藏，同时把「当前 Tab 的输入条」为让位 tabbar
     * 预留的 64dp 抬升（tab_raise marginBottom）归零，输入条贴住键盘顶；
     * ime 收起 → 全部还原。选 GONE 而非 translate 动画：与 adjustResize 的
     * 同帧重排叠加动画会闪烁，瞬时显隐反而干净。
     *
     * ⚠️ v8 关键变化：`@id/input` 现在**同时存在于**记录页 View 容器与
     * [AssistantFragment] 两处。`hide()` 的 Fragment 视图仍 attach 在视图树上，
     * 直接 `activity.findViewById(R.id.input)` 会命中**隐藏的那个**（结果不确定）。
     * 所以由宿主经 [visibleInput] 提供当前可见 Tab 的输入条 —— 查询范围
     * 限定在当前 Tab 内，结果确定。
     *
     * 限制：ime insets 精确上报需 API 30+（minSdk 29，目标机型 Magic6 Pro
     * 为 API 34）；API 29 上拿不到 ime 可见性，行为退回现状。
     */
    fun bindImeGuard(activity: Activity, visibleInput: () -> View?) {
        val root = activity.findViewById<View>(android.R.id.content) ?: return
        val tabbar = activity.findViewById<View>(R.id.tabbar) ?: return
        val raisePx = activity.resources.getDimensionPixelSize(R.dimen.tab_raise)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val imeVisible = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
            tabbar.visibility = if (imeVisible) View.GONE else View.VISIBLE

            // 输入条 parent 的 marginBottom 归零 / 还原；重复赋值会触发
            // 无谓的 requestLayout，所以先比较再写。
            val bar = visibleInput()?.parent as? ViewGroup
            val lp = bar?.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                val target = if (imeVisible) 0 else raisePx
                if (lp.bottomMargin != target) {
                    lp.bottomMargin = target
                    bar.layoutParams = lp
                }
            }
            insets // 不消费，根布局 fitsSystemWindows 照常工作
        }
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
