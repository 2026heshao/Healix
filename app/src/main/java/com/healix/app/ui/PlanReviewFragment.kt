package com.healix.app.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.healix.app.R
import com.healix.app.databinding.FragmentPlanReviewBinding
import com.healix.app.databinding.GroupPlanReviewReviewBinding
import kotlinx.coroutines.launch

/**
 * 计划 / 回顾（v8 需求 7 重构）。
 *
 * ── 与旧版的差别 ───────────────────────────────────────────────────
 * - **两个可见 Tab**：计划（= 本周训练 + 今日计划的**统一时间轴**）| 回顾。
 *   原「训练」Tab 与手动「更新」「刷新」主按钮一并去除。
 * - **无手动刷新**：数据变化经 Room Flow → [PlanReviewViewModel] 本地重算（0 AI）；
 *   AI 重排由 VM 后台自动触发（≤1 次/日），页顶只留一行「正在按你的最新数据重排…」提示。
 * - **训练空态**：整周计划仍需一个生成入口 → 轴尾一条**低调文字链**（非主按钮）。
 *
 * v8 T03：由 `PlanReviewActivity` 迁为宿主 [MainActivity] 内的二级页 Fragment
 * （[NavHost] 路由），进出零窗口转场。
 *
 * ⚠️ VM 用 `ViewModelProvider(this)`（**Fragment 作用域**）——随本 Fragment 销毁即
 *    `onCleared()`，`viewModelScope` 取消在途自动重排（避免退后台空跑 20s 请求）。
 *    ⚠️ **不能**用 `by viewModels()`：那是 `fragment-ktx` 的扩展，本模块未引入该依赖
 *    （只有 `lifecycle-viewmodel-ktx`），本地无 JDK 编译、漏了要到 CI 才炸。
 *    二级页 keep-alive（[NavHost] 用 add + hide/show，非 replace）：本页被上层页
 *    覆盖时视图保活，pop 时由 FragmentManager 逆向回放自动 show，零重建 ——
 *    渲染完全依赖 [PlanReviewViewModel.plan] 的当前值（无状态重建，滚动位置天然保留）。
 *
 * v0.3 B3：改继承 [PageFragment]（统一 `onHiddenChanged` 派发）→ 重新可见时经
 *   [onPageShown] 刷新（重算 + 重订阅今日键）。**不得用 `onResume` 替代**（keep-alive
 *   下不触发）。
 */
class PlanReviewFragment : PageFragment() {

    private var _binding: FragmentPlanReviewBinding? = null
    private val binding get() = _binding!!

    private val vm: PlanReviewViewModel by lazy {
        ViewModelProvider(this)[PlanReviewViewModel::class.java]
    }

    /**
     * 上次已渲染的**数据指纹**（[observe] 的幂等判据）。`null` = 尚未渲染。
     *
     * ⚠️ 判据是 `PlanUiState.dataFingerprint()` 而**不是**整个状态对象（2026-10-09，
     *    真机问题 1 的 R1-1）：整对象含 `updating` / `failed` 等瞬时标志，而自动重排
     *    只在三个位置翻转标志、数据一字未动 —— 旧判据会被击穿，一次进入计划页白跑
     *    3 次「清空 + 逐条 re-inflate」。详见 [PlanUiState.dataFingerprint] 的 KDoc。
     *
     * 视图销毁时在 [onDestroyView] 复位。
     */
    private var renderedFingerprint: PlanUiState? = null

    /**
     * 首帧的时间轴条目是否尚未填充（2026-10-09，真机问题 1 的「转场窗口内延后填轴」）。
     *
     * 进入本页有 180ms 入场动画；而 [renderTimelineItems] 是 `removeAllViews()` +
     * 逐条 `inflate` 整条时间轴。这段主线程工作量若落在动画窗口内，用户看到的就是
     * 「滑入收尾卡一下」。故**首次**填轴延后到动画之后（复用 `StatusDetailFragment`
     * 已有的 200ms 口径：180ms 动画 + 余量），此时页面已在屏内且动画已结束。
     *
     * 仅延后**首次**：此后数据变化（用户记一笔、自动重排落库）本就发生在用户
     * 停留期间，再延后只会让「记一笔」显得迟滞。
     */
    private var firstItemsPending = true

    /**
     * 「回顾」组的懒建绑定（2026-10-08，性能）。
     *
     * 该组在 `fragment_plan_review.xml` 里是 [ViewStub]（计划 Tab 是默认页，首帧
     * 看不到它）；**首次切到「回顾」Tab** 才 inflate。`null` = 尚未建。
     * ⚠️ ViewStub 只能 inflate 一次（第二次会抛 `IllegalStateException`：父容器已
     * 不认它），所以必须靠本字段做「建过就不再建」的唯一守卫。
     */
    private var reviewBinding: GroupPlanReviewReviewBinding? = null

    /**
     * 最近一次收到的复盘数据。
     *
     * 为什么需要：[observe] 的 `vm.review` collector 在本页可见期间**一直在跑**，
     * 而回顾组的视图要等用户切 Tab 才建 —— 不缓存这一份，「切过去是空的」。
     */
    private var lastReview: ReviewUiState? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPlanReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { NavHost.back(requireContext()) }
        binding.tabPlan.setOnClickListener { selectTab(PlanTab.PLAN) }
        binding.tabReview.setOnClickListener { selectTab(PlanTab.REVIEW) }
        binding.btnGenerateWeek.setOnClickListener { vm.generateTraining() }
        binding.btnGenerateToday.setOnClickListener { vm.generateTodayPlan() }
        // 常驻手动重排入口（仅非空态可见，与空态 planEmptyRow 互斥）
        binding.btnRerankToday.setOnClickListener { vm.generateTodayPlan() }

        selectTab(PlanTab.PLAN)
        observe()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 幂等缓存与懒建绑定随视图作废：视图树没了，下次重建必须重新渲染 / 重新 inflate
        // （否则新树会因「状态相同」被整段跳过 → 空白页；ViewStub 也无法二次 inflate）。
        renderedFingerprint = null
        firstItemsPending = true
        reviewBinding = null
        lastReview = null
        _binding = null
    }

    /**
     * 重新可见（keep-alive 下由 [PageFragment.onHiddenChanged]`(false)` 触发）：
     * 委托 VM 重算 + 重订阅今日键（跨零点 / 外部变更兜底）。纯本地，0 AI。
     */
    protected override fun onPageShown() {
        vm.onPageShown()
    }

    private fun selectTab(tab: PlanTab) {
        vm.showTab(tab)
        val isPlan = tab == PlanTab.PLAN

        // v6：选中态 text_1 / 600 + 下方 2dp primary 线（bg_seg_underline）；
        // 未选中 text_2 / 400 + 无底线。字重按契约 600 = sans-serif-medium + bold 合成。
        applyTabWeight(binding.tabPlan, isPlan)
        applyTabWeight(binding.tabReview, !isPlan)
        binding.tabPlan.setTextColor(color(if (isPlan) R.color.text_1 else R.color.text_2))
        binding.tabReview.setTextColor(color(if (isPlan) R.color.text_2 else R.color.text_1))
        binding.tabPlanUnderline.visibility = if (isPlan) View.VISIBLE else View.INVISIBLE
        binding.tabReviewUnderline.visibility = if (isPlan) View.INVISIBLE else View.VISIBLE

        binding.planGroup.visibility = if (isPlan) View.VISIBLE else View.GONE
        // 回顾组懒建（2026-10-08，性能）：默认停在计划 Tab，首开**不为**这块
        // `gone` 的内容付 inflate —— 那正是转场掉帧的大头。切到回顾才建，
        // 且建完由 [ensureReviewGroup] 立即回填缓存的数据。
        if (isPlan) {
            reviewBinding?.reviewGroup?.visibility = View.GONE
        } else {
            ensureReviewGroup().reviewGroup.visibility = View.VISIBLE
        }

        // 缺口行只属于计划 Tab
        binding.gapLabel.visibility = if (isPlan) View.VISIBLE else View.GONE
        binding.gapDivider.visibility = if (isPlan) View.VISIBLE else View.GONE

        if (!isPlan) UndoBar.hide(binding.undoBar)
    }

    /**
     * 取「回顾」组绑定，必要时先 inflate（唯一入口，保证只 inflate 一次）。
     *
     * inflate 后立刻用 [lastReview] 回填 —— 数据可能早在切 Tab 之前就到了
     * （`vm.review` 的 collector 一直在跑），不回填会看到一片空白。
     */
    private fun ensureReviewGroup(): GroupPlanReviewReviewBinding {
        reviewBinding?.let { return it }
        val view = binding.reviewStub.inflate()
        val b = GroupPlanReviewReviewBinding.bind(view)
        reviewBinding = b
        lastReview?.let { bindReview(b, it) }
        return b
    }

    /** 复盘三宫格 + 正文的绑定（懒建路径与 collector 共用，避免两处口径漂移）。 */
    private fun bindReview(b: GroupPlanReviewReviewBinding, r: ReviewUiState) {
        b.statIn.text = if (r.kcalIn > 0) r.kcalIn.toString() else "—"
        b.statOut.text = if (r.kcalOut > 0) r.kcalOut.toString() else "—"
        b.statWeight.text = if (r.weightKg > 0) trimNumber(r.weightKg) else "—"
        b.reviewText.text = r.content.ifBlank { getString(R.string.nodata) }
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    vm.plan.collect { p ->
                        // ══ 幂等渲染（2026-10-08，性能；2026-10-09 指纹化）══════════════
                        // `vm.plan` 由多个上游（计划表 / 训练表 / 目标 / 复盘）合成，任何
                        // 一处变化都会重发；而 [renderTimeline] 是 `removeAllViews()` +
                        // **逐条重新 inflate** 整条时间轴。
                        //
                        // 判据分两层（问题 1 的 R1-1）：
                        // ① **表头**（来源行 / 提示行 / 汇总行）成本极低且**必须**随瞬时
                        //    标志更新（否则「正在重排…」永远不出现）→ 每次都渲染；
                        // ② **时间轴**昂贵 → 只在**数据指纹**变化时重建。标志翻转不再
                        //    触发整条轴重建，自动重排的 3 次发射只剩 1 次真正重建。
                        renderPlanHeader(p)
                        renderTrainingSummary(p)

                        val fingerprint = p.dataFingerprint()
                        if (fingerprint == renderedFingerprint) return@collect
                        renderedFingerprint = fingerprint

                        // B1 滑动弹跳修复：数据变化触发的重建会把 contentScroll 的 scrollY
                        // 夹到 0。① 重建**前**记住当前滚动位置；② 渲染**结束后**用 `post`
                        // 到新子树 layout 之后统一还原（不 post 会被旧高度夹取）。
                        // 回调内只碰局部引用 `scroll`，不触碰可能已拆的 binding。
                        val savedY = binding.contentScroll.scrollY
                        binding.gapLabel.text = if (p.gapLeft > 0) {
                            getString(R.string.plan_gap_label, p.gapLeft)
                        } else {
                            getString(R.string.plan_reached)
                        }

                        // 首次进页：先只摆骨架（日头 + 空态/按钮态），条目延后到入场动画
                        // 之后填 —— 把逐条 inflate 挪出 180ms 转场窗口（问题 1 的 P1-2）。
                        if (firstItemsPending) {
                            firstItemsPending = false
                            renderTimelineShell(p)
                            val scroll = binding.contentScroll
                            scroll.post { scroll.scrollTo(0, savedY) }
                            // ⚠️ 回调用**指纹**闸门而不是无条件填：这 200ms 内若数据又变了，
                            //    collect 会走下面的 renderTimeline 重建整条轴（含新条目）；
                            //    此刻再追加旧 p 的条目就会「新旧两套叠在一起」。
                            binding.itemContainer.postDelayed(
                                {
                                    if (fingerprint == renderedFingerprint) {
                                        fillTimelineItems(p, savedY)
                                    }
                                },
                                ITEMS_AFTER_TRANSITION_MS,
                            )
                        } else {
                            renderTimeline(p)
                            val scroll = binding.contentScroll
                            scroll.post { scroll.scrollTo(0, savedY) }
                        }
                    }
                }

                launch {
                    vm.review.collect { r ->
                        // 回顾组可能还没建（计划 Tab 默认页）→ 先存着，
                        // 由 [ensureReviewGroup] 在首次切 Tab 时回填。
                        lastReview = r
                        reviewBinding?.let { bindReview(it, r) }
                    }
                }

                // 内联撤销条：写入成功后 5 秒可撤销（规范 §9.6 / PRD §15.7）
                launch {
                    vm.undo.collect { payload ->
                        val text = getString(
                            R.string.undo_recorded,
                            payload.typeName,
                            payload.valueText,
                        )
                        UndoBar.bind(
                            container = binding.undoBar,
                            leftText = binding.undoLeft,
                            action = binding.undoAction,
                            text = text,
                            announce = getString(R.string.undo_announce, payload.typeName),
                            onUndo = { vm.undo(payload.clientEventId) },
                        )
                        // 撤销条在滚动区顶部：滚回顶部，保证「撤得回」看得见（PRD §15.7）
                        binding.contentScroll.smoothScrollTo(0, 0)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 计划 Tab 渲染（统一时间轴）
    // ------------------------------------------------------------------

    /** 页顶：来源行 + 一行状态提示（重排中 / 失败 / 配额 / 兜底）。 */
    private fun renderPlanHeader(p: PlanUiState) {
        // 来源行：来源名 · HH:mm 生成（今日无计划条目时不显示）
        val hasPlanItems = p.entries.any { it.source == TimelineSource.PLAN }
        if (hasPlanItems) {
            // ⚠️ fallback（本地兜底）时用 plan_source_estimated（"本地简化 · 热量为估算"），
            //    让用户看得出 kcal 是**估算**；不复用 mode_simplified_local（提示行共用）。
            val srcName = if (p.source == TrainingPlanner.SOURCE_AI) {
                getString(R.string.plan_source_ai)
            } else {
                getString(R.string.plan_source_estimated)
            }
            val time = if (p.generatedAt > 0) hhmm(p.generatedAt) else "--:--"
            binding.planSourceLabel.text = getString(R.string.plan_source_line, srcName, time)
        }

        // A2（2026-10-09）：文案与可见性**按同一个 hint 值提交**。
        // 旧写法只在 `hint != null` 时写 text，可见性却在行尾无条件按 `hint` 设 ——
        // 「本地简化 → AI 计划」切换那一帧是「文案还是旧的、控件却已消失」的半更新态，
        // 参与问题 1 的「闪现」。现在两处同源同帧。
        val hint = when {
            p.updating -> getString(R.string.plan_auto_updating)
            // 有旧版 → 屏幕上确实还是旧版；无旧版 → 这是刚建的本地兜底，
            // 不能说「仍显示上一次的计划」（那是谎话）
            p.failed && p.fromCache -> getString(R.string.plan_update_failed)
            p.failed -> getString(R.string.plan_update_local_fallback)
            p.quotaExhausted -> getString(R.string.quota_exhausted_short)
            p.source == TrainingPlanner.SOURCE_FALLBACK -> getString(R.string.mode_simplified_local)
            else -> null
        }
        binding.planModeLabel.text = hint.orEmpty()
        binding.planModeLabel.visibility = if (hint == null) View.GONE else View.VISIBLE

        // B1：**先算后写** —— 所有 visibility 决策先算好，再于行尾统一提交，
        // 消除同帧「先塌后复」的中间态（最终态与改前一致）。
        binding.planSourceLabel.visibility = if (hasPlanItems) View.VISIBLE else View.GONE
    }

    /** 本周训练汇总行 + 训练空态的低调生成入口（互斥）。 */
    private fun renderTrainingSummary(p: PlanUiState) {
        val hasTraining = p.hasTraining
        // 本周训练重点（原「训练」Tab 页脚）：仅在已生成且有内容时显示
        val focus = p.trainingFocus
        val showFocus = hasTraining && focus.isNotBlank()

        if (hasTraining) {
            binding.trainingSummary.text =
                getString(R.string.status_train_week, p.trainingSessions, p.trainingDone)
        }
        if (showFocus) {
            binding.trainingFocus.text = getString(R.string.training_focus, focus)
        }

        if (!hasTraining) {
            binding.trainingIntro.text = getString(R.string.training_generate_intro, p.goalLabel)
            binding.trainingGoalFooter.text = getString(R.string.training_goal_footer, p.sessionsGoal)
            binding.trainingFailed.visibility = if (p.trainingFailed) View.VISIBLE else View.GONE

            binding.btnGenerateWeek.isEnabled = !p.generatingTraining
            binding.btnGenerateWeek.isClickable = !p.generatingTraining
            binding.btnGenerateWeek.text = getString(
                when {
                    p.generatingTraining -> R.string.training_generating
                    p.trainingFailed -> R.string.retry
                    else -> R.string.training_generate
                },
            )
            binding.btnGenerateWeek.setTextColor(
                color(if (p.generatingTraining) R.color.text_3 else R.color.primary),
            )
        }

        // B1：**先算后写** —— visibility 决策先算好，再于行尾统一提交（消除同帧「先塌后复」）。
        // ⚠️ trainingFailed 仅在 !hasTraining 时写（保留既有语义：hasTraining 态不动它）。
        binding.trainingSummary.visibility = if (hasTraining) View.VISIBLE else View.GONE
        binding.trainingFocus.visibility = if (showFocus) View.VISIBLE else View.GONE
        binding.trainingGenerateRow.visibility = if (hasTraining) View.GONE else View.VISIBLE
    }

    /**
     * 统一时间轴：按 `dayIndex` 分组，每组前插一条「日头」（今天 / 明天 / `M月D日 周X`）。
     *
     * 渲染约定：`timeLabel` 为空（训练日 / 认不出时段的锚点）→ 时间列 `INVISIBLE`
     * （保留 52dp 对齐，不塌陷）；每天**最后一条**的竖线置 `INVISIBLE`（不让竖线拖出
     * 组外）。「记一笔」由条目 `canLog` / `done` 驱动：可点（accent）/ 已记录（text_3
     * 禁用）/ 无（隐藏 —— 明天的锚点走这一支，它按设计不可记）。
     *
     * 一条都没有时走空态入口（问题 3 方案 C）：`今天还没有计划` + `生成今日计划`。
     */
    /**
     * 时间轴**骨架**（2026-10-09，问题 1 的「转场窗口内延后填轴」）：
     * 清空容器 + 摆好空态 / 按钮态，但**不填条目**。
     *
     * 首帧走这里，条目由 [fillTimelineItems] 在入场动画之后再填 ——
     * 逐条 `inflate` 是这块最贵的主线程工作，占着它就会撞上 180ms 转场动画。
     * 骨架与 [renderTimeline] 共用 [applyTimelineChrome]，两处口径不分叉。
     */
    private fun renderTimelineShell(p: PlanUiState) {
        binding.itemContainer.removeAllViews()
        applyTimelineChrome(p)
    }

    /**
     * 延后填轴：把**当时**那份状态的条目填进骨架。
     *
     * 传入 `p` 而不是重读 `vm.plan.value`：用户看到的首帧与随后填上的内容必须是
     * 同一份数据，否则又会造出一次「内容整体替换」（问题 1 的 R1-3 闪现）。
     * `_binding` 空值守卫：200ms 内用户可能已经退出本页。
     */
    private fun fillTimelineItems(p: PlanUiState, savedY: Int) {
        if (_binding == null) return
        if (p.entries.isEmpty()) return // 空态无条目可填，骨架已完整
        val lastIndex = p.entries.lastIndex
        var lastDay = -1
        p.entries.forEachIndexed { index, entry ->
            if (entry.dayIndex != lastDay) {
                lastDay = entry.dayIndex
                binding.itemContainer.addView(dayHeader(entry.dayIndex, p))
            }
            val isDayEnd = index == lastIndex || p.entries[index + 1].dayIndex != entry.dayIndex
            binding.itemContainer.addView(entryRow(entry, isDayEnd))
        }
        binding.planNote.text = p.note
        binding.planNote.visibility = if (p.note.isBlank()) View.GONE else View.VISIBLE
        val scroll = binding.contentScroll
        scroll.post { scroll.scrollTo(0, savedY) }
    }

    /**
     * 时间轴的外围控件（空态入口 / 常驻重排入口互斥）—— 骨架与全量渲染共用。
     *
     * 抽出来的理由：这段全是「按钮文案 + 启用态 + 可见性」，与「有多少条目」无关，
     * 两处各写一份必然漂移（正是本页此前的病根）。
     */
    private fun applyTimelineChrome(p: PlanUiState) {
        if (p.entries.isEmpty()) {
            binding.planEmptyRow.visibility = View.VISIBLE
            binding.planNote.visibility = View.GONE
            // 空态走 planEmptyRow 的生成入口 → 常驻重排行隐藏（互斥，避免两个入口同屏）
            binding.btnRerankToday.visibility = View.GONE
            binding.btnGenerateToday.isEnabled = !p.generatingToday
            binding.btnGenerateToday.isClickable = !p.generatingToday
            binding.btnGenerateToday.text = getString(
                if (p.generatingToday) R.string.plan_generating else R.string.plan_generate_today,
            )
            binding.btnGenerateToday.setTextColor(
                color(if (p.generatingToday) R.color.text_3 else R.color.primary),
            )
            return
        }
        binding.planEmptyRow.visibility = View.GONE

        // 常驻手动重排入口（用户 2026-10-06 要求恢复）：非空态恒显示；
        // 生成中的禁用/文案/着色与空态按钮同口径。先算后写（B1 纪律）。
        binding.btnRerankToday.visibility = View.VISIBLE
        binding.btnRerankToday.isEnabled = !p.generatingToday
        binding.btnRerankToday.isClickable = !p.generatingToday
        binding.btnRerankToday.text = getString(
            if (p.generatingToday) R.string.plan_generating else R.string.plan_rerank_today,
        )
        binding.btnRerankToday.setTextColor(
            color(if (p.generatingToday) R.color.text_3 else R.color.primary),
        )
    }

    /**
     * 单条时间轴行的容器（[renderTimeline] 的「先清空」+ 骨架 + 填条目）。
     *
     * 一条都没有时走空态入口（问题 3 方案 C）：`今天还没有计划` + `生成今日计划`。
     */
    private fun renderTimeline(p: PlanUiState) {
        renderTimelineShell(p)
        if (p.entries.isEmpty()) return
        fillTimelineItems(p, binding.contentScroll.scrollY)
    }

    /**
     * 日头：今天 / 明天 / `M月D日 周X`。
     *
     * ⚠️ 文案由 [PlanReviewViewModel] 统一格式化（`PlanUiState.dayLabels`）——三档口径
     *    只此一处；列表缺失（日界线未读到）时回落旧的「周X · 今天」。
     */
    private fun dayHeader(dayIndex: Int, p: PlanUiState): View {
        val v = layoutInflater.inflate(R.layout.item_timeline_day, binding.itemContainer, false)
        val dow = dayIndex + 1
        val label = p.dayLabels.getOrNull(dayIndex) ?: run {
            val name = dowLabel(dow)
            if (dow == p.todayDow) getString(R.string.timeline_today_label, name) else name
        }
        v.findViewById<TextView>(R.id.dayLabel).text = label
        return v
    }

    /** 单条时间轴行（复用 `item_plan_timeline.xml`）。 */
    private fun entryRow(entry: TimelineEntry, isDayEnd: Boolean): View {
        val row = layoutInflater.inflate(
            R.layout.item_plan_timeline, binding.itemContainer, false,
        )
        val time = row.findViewById<TextView>(R.id.tlTime)
        val rail = row.findViewById<View>(R.id.tlRail)
        val line = row.findViewById<View>(R.id.tlLine)
        val title = row.findViewById<TextView>(R.id.tlTitle)
        val detail = row.findViewById<TextView>(R.id.tlDetail)
        val meta = row.findViewById<TextView>(R.id.tlMeta)
        val logAct = row.findViewById<View>(R.id.tlLogAct)
        val logBtn = row.findViewById<TextView>(R.id.btnLogThis)

        // 时间列：训练日 timeLabel 为空 → INVISIBLE（保留列宽，保证竖线/圆点跨行对齐）
        time.text = entry.timeLabel
        time.visibility = if (entry.timeLabel.isNotBlank()) View.VISIBLE else View.INVISIBLE
        rail.visibility = View.VISIBLE
        // 每天最后一行的竖线不外露（圆点保留，不影响对齐）
        if (isDayEnd) line.visibility = View.INVISIBLE

        title.text = entry.title
        detail.text = entry.detail
        detail.visibility = if (entry.detail.isBlank()) View.GONE else View.VISIBLE

        // meta 与恢复度注记共用这一行：两者都是"可核对的补充事实"，且**互斥**
        // （计划条目只有 meta、训练日条目只有 recoveryNote）。仍按列表拼接而非
        // `ifBlank{}` 兜底 —— 万一将来两者同时出现，宁可显示两段也不能静默丢一段。
        val sub = listOf(entry.meta, entry.recoveryNote)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        meta.text = sub
        meta.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE

        when {
            entry.canLog -> {
                logAct.visibility = View.VISIBLE
                logBtn.isEnabled = true
                logBtn.isClickable = true
                logBtn.text = getString(R.string.log_this)
                logBtn.setTextColor(color(R.color.primary))
                logBtn.setOnClickListener { onLog(entry) }
            }
            entry.done -> {
                logAct.visibility = View.VISIBLE
                logBtn.isEnabled = false
                logBtn.isClickable = false
                logBtn.text = getString(R.string.recorded)
                logBtn.setTextColor(color(R.color.text_3))
            }
            else -> logAct.visibility = View.GONE
        }
        return row
    }

    /** 「记一笔」按来源分流：计划条目 → 直写建议；训练日 → 按计划原文直写。 */
    private fun onLog(entry: TimelineEntry) {
        when (entry.source) {
            TimelineSource.TRAINING -> vm.logTraining(entry.dayIndex + 1)
            else -> vm.logSuggestion(entry)
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun color(resId: Int): Int = ContextCompat.getColor(requireContext(), resId)

    /**
     * 分段 Tab 字重：选中 600（sans-serif-medium + bold 合成），未选中 400。
     * 契约 §2：本模块无字体资源，字重靠 fontFamily + textStyle 组合落地。
     */
    private fun applyTabWeight(tv: TextView, selected: Boolean) {
        tv.setTypeface(
            Typeface.create(
                if (selected) "sans-serif-medium" else "sans-serif",
                if (selected) Typeface.BOLD else Typeface.NORMAL,
            ),
        )
    }

    /** epoch millis → 本地 `HH:mm`（24 小时制两位补零，与时间轴同口径）。 */
    private fun hhmm(ts: Long): String {
        val t = java.time.Instant.ofEpochMilli(ts)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
        return String.format(java.util.Locale.US, "%02d:%02d", t.hour, t.minute)
    }

    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    // ⚠️ 星期短名用包级 `dowLabel()`（TrainingPlanner.kt 顶层，CLDR 本地化）——
    //    本类**不得**再定义同名成员，否则成员优先会遮蔽它、且是一份硬编码中文的私本。

    private companion object {
        /**
         * 首帧之后填充时间轴条目的延迟（毫秒）。
         *
         * 取 200ms 的口径与 [StatusDetailFragment] 一致：入场动画 180ms
         * （`res/anim/in_back.xml`）+ 余量。此刻动画已结束，逐条 inflate
         * 不再与转场抢主线程（真机问题 1 的「滑入收尾卡一下」）。
         */
        const val ITEMS_AFTER_TRANSITION_MS = 200L
    }
}
