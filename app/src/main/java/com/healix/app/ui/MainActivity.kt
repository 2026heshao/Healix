package com.healix.app.ui

import android.content.Intent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.ActivityMainBinding
import com.healix.app.databinding.ItemEventBinding
import com.healix.app.db.EventEntity
import com.healix.app.db.PresetEntity
import com.healix.app.notify.EventText
import kotlinx.coroutines.launch

/**
 * 主界面（设计规范系统 4.1）。
 *
 * 核心原则：进入 300ms 后自动弹键盘并聚焦输入区 —— 这是"打开即记"的摩擦下限。
 */
class MainActivity : AppCompatActivity() {

    // lateinit 绑定与 vm 视图绑定均由 MainViewModel 持有状态
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: EventAdapter
    private lateinit var vm: MainViewModel

    /**
     * 最近一次的 UI 状态。
     *
     * 用于让 offlineBar 的点击行为与当前语义匹配 —— 同一条提示条
     * 承载"离线"和"未配置"两种语义，必须知道现在是哪一种才能决定
     * 点了之后是"重试"还是"去设置页"。
     */
    private var lastUiState: MainUiState = MainUiState.Idle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = MainViewModel(HealixApp.from(this))

        adapter = EventAdapter(
            onEdit = { entity ->
                EventEditSheet.newInstance(entity.clientEventId)
                    .show(supportFragmentManager, EventEditSheet.TAG)
            },
            onRetry = { entity -> vm.retry(entity) },
        )

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.setHasFixedSize(false)

        binding.dateLabel.text = HealixDate.todayLabel(this)

        binding.btnSend.setOnClickListener { submit() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.tabAssistant.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }
        binding.planBar.setOnClickListener {
            startActivity(Intent(this, PlanReviewActivity::class.java))
        }
        binding.nudgeBar.setOnClickListener { focusInput() }

        // 状态提示条点击：按当前语义分流（离线 → 重试；未配置 → 去设置）
        binding.offlineBar.setOnClickListener {
            if (lastUiState == MainUiState.NotConfigured) {
                startActivity(Intent(this, SettingsActivity::class.java))
            } else {
                vm.retryFailedPending()
            }
        }

        observe()

        // 进入 300ms 后自动弹键盘（规范硬要求：打开即弹键盘、光标在输入框）
        binding.input.postDelayed({ focusInput() }, 300)
    }

    override fun onResume() {
        super.onResume()
        // 通知栏录入可能在本页不可见时发生；Room Flow 会自动推新数据，
        // 这里只需在回到前台时确认常驻通知副标题与今日状态一致。
        vm.refreshNudgeSubtitle()
    }

    private fun focusInput() {
        binding.input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun submit() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.input.setText("")
        vm.submit(text)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    vm.events.collect { list ->
                        adapter.submit(list)
                        binding.emptyState.visibility =
                            if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    vm.summary.collect { s ->
                        binding.gapValue.text = if (s.gap >= 0) {
                            getString(R.string.gap_format, s.gap)
                        } else {
                            getString(R.string.gap_negative_format, s.gap)
                        }
                        binding.summaryLine.text = getString(
                            R.string.summary_format, s.kcalIn, s.kcalOut, s.target,
                        )
                        val pct = if (s.target > 0) s.kcalIn * 100 / s.target else 0
                        binding.progressLine.progress = pct.coerceIn(0, 100)

                        // 计划提示条：缺口为 0 时改为「今日已达标」
                        binding.planBar.text = if (s.gap > 0) {
                            getString(R.string.plan_gap, s.gap) + "　" + getString(R.string.view_advice)
                        } else {
                            getString(R.string.plan_reached)
                        }
                    }
                }

                launch {
                    vm.uiState.collect { state ->
                        lastUiState = state
                        when (state) {
                            MainUiState.Idle -> {
                                binding.spinner.visibility = View.GONE
                                binding.stateLabel.visibility = View.GONE
                            }
                            MainUiState.Parsing -> {
                                binding.spinner.visibility = View.VISIBLE
                                binding.stateLabel.visibility = View.VISIBLE
                                binding.stateLabel.text = getString(R.string.state_parsing)
                            }
                            is MainUiState.Queued -> {
                                binding.spinner.visibility = View.VISIBLE
                                binding.stateLabel.visibility = View.VISIBLE
                                // 限流必须显示预估秒数，不静默转圈（规范 3.10）
                                binding.stateLabel.text =
                                    getString(R.string.state_queued, state.seconds)
                            }
                            MainUiState.Offline -> {
                                binding.spinner.visibility = View.GONE
                                binding.stateLabel.visibility = View.GONE
                                binding.offlineBar.text = getString(R.string.state_offline)
                                binding.offlineBar.visibility = View.VISIBLE
                            }
                            MainUiState.NotConfigured -> {
                                // 未配置 ≠ 离线。用同一条提示条，但文案与动作不同：
                                // 点一下直接去设置页（而不是让用户自己找）
                                binding.spinner.visibility = View.GONE
                                binding.stateLabel.visibility = View.GONE
                                binding.offlineBar.text = getString(R.string.no_provider_config)
                                binding.offlineBar.visibility = View.VISIBLE
                            }
                        }
                    }
                }

                launch { vm.presets.collect { renderPresets(it) } }

                launch {
                    vm.todayCount.collect { count ->
                        // 监督提示条：今日无记录时出现（被动监督，不依赖后台定时器）
                        binding.nudgeBar.visibility =
                            if (count == 0) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    /**
     * 预设横条：点一下 = 一条记录，完全不打字、不调 AI（功能补充 2.1）。
     * 这是全 App 摩擦最低的路径。
     */
    private fun renderPresets(list: List<PresetEntity>) {
        binding.presetRow.removeAllViews()
        val visible = list.isNotEmpty()
        binding.presetScroll.visibility = if (visible) View.VISIBLE else View.GONE
        binding.presetDivider.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        for (preset in list) {
            val tv = layoutInflater.inflate(R.layout.item_preset, binding.presetRow, false)
                as android.widget.TextView
            tv.text = preset.name
            tv.setOnClickListener { vm.logPreset(preset) }
            binding.presetRow.addView(tv)
        }
    }
}

/**
 * 记录列表适配器。
 * 无卡片、无阴影、无彩色徽章 —— 靠 6px 圆点 + 1dp 分隔线组织信息。
 */
class EventAdapter(
    private val onEdit: (EventEntity) -> Unit,
    private val onRetry: (EventEntity) -> Unit,
) : RecyclerView.Adapter<EventAdapter.VH>() {

    private var items: List<EventEntity> = emptyList()

    fun submit(list: List<EventEntity>) {
        items = list
        notifyItemRangeChanged(0, items.size)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val b = ItemEventBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false,
        )
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemEventBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(e: EventEntity) {
            val ctx = b.root.context

            // 类型 + 时间（第一行，13sp text_2）
            b.typeLabel.text = EventText.typeName(ctx, e.type)
            b.timeLabel.text = HealixDate.timeLabel(e.ts)

            // 6px 圆点按类型着色
            b.dot.background.setTint(EventText.typeColor(ctx, e.type))

            // 正文：raw_text（15sp text_1，最多 2 行）
            b.bodyText.text = e.rawText

            // 摘要：按类型口径，无信息则隐藏（不留空行）
            val summary = EventText.summary(ctx, e)
            b.summaryText.text = summary
            b.summaryText.visibility = if (summary.isNullOrEmpty()) View.GONE else View.VISIBLE

            // 三态：pending 显示"识别中"，failed 显示错误 + 重试
            when (e.parseStatus) {
                PARSE_PENDING -> {
                    b.pendingText.visibility = View.VISIBLE
                    b.errorText.visibility = View.GONE
                }
                PARSE_FAILED -> {
                    b.pendingText.visibility = View.GONE
                    b.errorText.visibility = View.VISIBLE
                    b.errorText.text = ctx.getString(R.string.state_failed)
                    b.errorText.setOnClickListener { onRetry(e) }
                }
                else -> {
                    b.pendingText.visibility = View.GONE
                    b.errorText.visibility = View.GONE
                }
            }

            // 整行可点 → 编辑（复用 ConfirmSheet）
            b.root.setOnClickListener { onEdit(e) }

            // 最后一行不画分隔线
            b.divider.visibility =
                if (bindingAdapterPosition == items.size - 1) View.GONE else View.VISIBLE
        }
    }

    private companion object {
        const val PARSE_PENDING = "pending"
        const val PARSE_FAILED = "failed"
    }
}

/** 日期 / 时间格式化。集中一处，避免各 Activity 各写一遍。 */
internal object HealixDate {

    private val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun todayLabel(context: android.content.Context): String {
        val d = java.time.LocalDate.now()
        return "${d.monthValue}月${d.dayOfMonth}日 ${WEEKDAYS[d.dayOfWeek.value - 1]}"
    }

    fun timeLabel(ts: Long): String {
        val t = java.time.Instant.ofEpochMilli(ts)
            .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        return "%02d:%02d".format(t.hour, t.minute)
    }

    /** 会话日期标签：今天 / 昨天 / M月d日 */
    fun sessionLabel(date: java.time.LocalDate): String {
        val today = java.time.LocalDate.now()
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> "${date.monthValue}月${date.dayOfMonth}日"
        }
    }
}
