package com.healix.app.ui

import androidx.fragment.app.Fragment

/**
 * 二级页可见性基类（v0.3 需求 4，批次 B3）。
 *
 * ── 为什么是它而不是 `onResume` ────────────────────────────────────────
 * 本仓的二级页导航是**手工 keep-alive 页面栈**：[NavHost] 用
 * `commitNowAllowingStateLoss()` + `hide/show`（不 `replace`）→ 被上层页覆盖的页
 * **不销毁**：既不重走 [Fragment.onViewCreated]，[Fragment.onResume] 也不触发
 * （见 `NavHost` 明文注释）。因此「回到本页后刷新」的**唯一正解**是
 * [onHiddenChanged] —— 由 `FragmentManager` 在事务执行的正确时机派发，
 * 无需自己拿捏时序。**红线：二级页不得用 `onResume` 替代。**
 *
 * ── 契约 ──────────────────────────────────────────────────────────────
 * 子类只需按需覆写 [onPageShown]（默认空操作）。统一守卫「`view != null` 才回调」
 * —— 视图未建时不触碰 binding（与既有 5 页 `onHiddenChanged` 的守卫口径一致）。
 *
 * ⚠️ 本类**公开**（非 internal）：既有若干 `internal`/`public` 子类继承它，
 *    公开基类可被 internal 子类继承（反向不行）。
 *
 * ⚠️ 本波仅 6 个目标页采用；既有 5 个已挂 `onHiddenChanged` 的页面（`RecordFragment`
 *    / `AssistantFragment` / `SettingsFragment` / `StatusDetailFragment` /
 *    `MedicalSummaryFragment`）**本批不改**，两范式并存的收敛列为后续债务。
 */
abstract class PageFragment : Fragment() {

    /**
     * 被覆盖后再展示时由 `FragmentManager` 派发（keep-alive 下 `onResume` 不触发）。
     *
     * `final`：统一在此做「`!hidden && view != null`」守卫后转交 [onPageShown]，
     * 子类不得重写本方法以免绕过守卫。
     */
    final override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // 统一守卫：视图未建（view == null）不触碰 binding / viewLifecycleOwner。
        if (!hidden && view != null) onPageShown()
    }

    /**
     * 本页重新可见（keep-alive 下由 [onHiddenChanged]`(false)` 触发）。默认空操作。
     *
     * 数据为 Room `Flow` 的页面无需覆写（Flow 天然实时）；数据是一次性快照 /
     * 一次性读库的页面在此重读（如计划页重订阅今日键、资源页逐框守卫回填）。
     */
    protected open fun onPageShown() {}
}
