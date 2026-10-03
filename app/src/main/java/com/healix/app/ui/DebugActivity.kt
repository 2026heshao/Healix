package com.healix.app.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.ActivityDebugBinding
import com.healix.app.databinding.ItemLlmCallBinding
import com.healix.app.db.LlmCallEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * 调试页（设计规范系统 4.5）。
 *
 * 这是装在手机上后**唯一的排障入口** —— 没有它，App 就是黑盒。
 * 数据源是 llm_calls 表，数值必须与表一致（设计 QA 清单要求）。
 */
class DebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding
    private lateinit var adapter: LlmCallAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = LlmCallAdapter()
        binding.callList.layoutManager = LinearLayoutManager(this)
        binding.callList.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }

        val container = HealixApp.from(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
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
            }
        }
    }

    private fun startOfToday(): Long =
        java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()

    private suspend fun p95(): String {
        val since = startOfToday()
        val list = withContext(Dispatchers.IO) {
            HealixApp.from(this@DebugActivity).database.llmCallDao()
                .latenciesSince(since).sorted()
        }
        if (list.isEmpty()) return "—"
        val idx = (list.size * 0.95).toInt().coerceAtMost(list.size - 1)
        return "%.1fs".format(list[idx] / 1000.0)
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
