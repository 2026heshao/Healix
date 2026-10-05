package com.healix.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.FragmentDebugBinding
import com.healix.app.databinding.ItemLlmCallBinding
import com.healix.app.db.LlmCallEntity
import com.healix.app.perf.PerfProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * 调试页（设计规范系统 4.5）。v8 T03：由 [DebugActivity] 迁为 Fragment。
 *
 * 这是装在手机上后**唯一的排障入口** —— 没有它，App 就是黑盒。
 * 数据源是 llm_calls 表，数值必须与表一致（设计 QA 清单要求）。
 *
 * 迁移等价性：`onCreate` → [onViewCreated]；collect 挂
 * `viewLifecycleOwner`（视图销毁即停，宿主 Activity 常驻不再当作页生命周期）；
 * `finish()` → `popBackStack()`。
 */
class DebugFragment : Fragment() {

    private var _binding: FragmentDebugBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: LlmCallAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDebugBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = LlmCallAdapter()
        binding.callList.layoutManager = LinearLayoutManager(requireContext())
        binding.callList.adapter = adapter
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        val container = HealixApp.from(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    container.database.llmCallDao().observeRecent(20).collect { list ->
                        adapter.submit(list)
                        binding.emptyHint.visibility =
                            if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    // 汇总：今日调用次数 / 失败数 / P95 延迟
                    val stats = withContext(Dispatchers.IO) {
                        val since = startOfToday()
                        val dao = container.database.llmCallDao()
                        Triple(dao.countSince(since), dao.countFailedSince(since), p95())
                    }
                    binding.summaryLine.text = getString(
                        R.string.debug_summary, stats.first, stats.second, stats.third,
                    )
                }
                launch {
                    // 今日 token 合计（§4.1）：输入 / 输出（SUM 返回 Long，无数据为 0）
                    val tokens = withContext(Dispatchers.IO) {
                        val since = startOfToday()
                        val dao = container.database.llmCallDao()
                        dao.inputTokensSince(since) to dao.outputTokensSince(since)
                    }
                    binding.tokenLine.text = getString(
                        R.string.debug_tokens, tokens.first, tokens.second,
                    )
                }
            }
        }

        setupPerfProbe()
    }

    /**
     * 帧率探针（清单3 R4）：开关行 + 导出行。默认关闭（标志文件缺失 = 关）。
     * 状态持久化走 filesDir 标志文件（[PerfProbe.isEnabled]），**不走 settings 键**
     * （诊断状态 ≠ 用户配置）。导出复用 [DocumentWriter] 管线（REQUEST_PROBE →
     * Kind.EXPORT，宿主提示语零改动）。
     */
    private fun setupPerfProbe() {
        binding.probeSwitch.label.setText(R.string.debug_probe)
        val probeSwitch = binding.probeSwitch.switchWidget
        probeSwitch.isChecked = PerfProbe.isEnabled(requireContext())
        probeSwitch.setOnCheckedChangeListener { _, checked ->
            PerfProbe.setEnabled(requireContext(), checked)
        }
        // 行点击 = 同义拨动开关（放大触控目标）；行内 Switch 点击照常直拨
        binding.probeSwitch.root.setOnClickListener {
            probeSwitch.isChecked = !probeSwitch.isChecked
        }

        binding.probeExport.label.setText(R.string.debug_probe_export)
        binding.probeExport.chevron.visibility = View.VISIBLE
        binding.probeExport.root.setOnClickListener { exportProbeLog() }
    }

    /** 读探针日志（IO 线程）→ 交 [DocumentWriter] 走 SAF 存文件。 */
    private fun exportProbeLog() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val payload = withContext(Dispatchers.IO) { PerfProbe.readLog(ctx) }
            DocumentWriter.launch(
                activity = requireActivity(),
                requestCode = DocumentWriter.REQUEST_PROBE,
                mime = DocumentWriter.MIME_TEXT,
                fileName = "perf_probe.log",
                payload = payload,
            )
        }
    }

    private fun startOfToday(): Long =
        java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()

    private suspend fun p95(): String {
        val since = startOfToday()
        val list = withContext(Dispatchers.IO) {
            HealixApp.from(requireContext()).database.llmCallDao()
                .latenciesSince(since).sorted()
        }
        if (list.isEmpty()) return "—"
        val idx = (list.size * 0.95).toInt().coerceAtMost(list.size - 1)
        return "%.1fs".format(list[idx] / 1000.0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class LlmCallAdapter : RecyclerView.Adapter<LlmCallAdapter.VH>() {

    private var items: List<LlmCallEntity> = emptyList()

    @Suppress("NotifyDataSetChanged")
    fun submit(list: List<LlmCallEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemLlmCallBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false,
        )
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemLlmCallBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(c: LlmCallEntity) {
            val ctx = b.root.context

            b.timeText.text = Instant.ofEpochMilli(c.ts)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .let { "%02d:%02d".format(it.hour, it.minute) }

            b.purposeText.text = when (c.purpose) {
                "extract" -> "抽取"
                "plan" -> "计划"
                "review" -> "复盘"
                "ask" -> "问数"
                "agent_loop" -> "对话"
                else -> c.purpose
            }

            // status 用文字："ok" 用 text_2，异常用 negative
            b.statusText.text = if (c.httpCode != null && c.httpCode != 200) {
                c.httpCode.toString()
            } else {
                c.status
            }
            val isOk = c.status == "ok"
            b.statusText.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    ctx, if (isOk) R.color.text_2 else R.color.negative,
                )
            )

            b.latencyText.text = "%.1fs".format(c.latencyMs / 1000.0)

            b.noteText.text = when {
                c.attempts > 1 -> ctx.getString(R.string.debug_retry_times, c.attempts)
                !isOk -> ctx.getString(R.string.debug_degraded)
                else -> ""
            }
        }
    }
}
