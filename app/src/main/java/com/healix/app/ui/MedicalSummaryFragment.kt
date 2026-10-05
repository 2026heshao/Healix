package com.healix.app.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.FragmentMedicalSummaryBinding
import com.healix.app.db.SettingsKeys
import com.healix.app.parse.dayKeyOf
import com.healix.app.parse.dayStartHourOf
import com.healix.app.rules.MedicalSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 就医准备材料（v8 需求 9 功能 3；入口在「我的」页数据组，见 [MineFragment]）。
 *
 * 动作序列：选时间窗（近 1 / 3 / 6 个月）→ 预览一页可读摘要 → 「保存为文本」经 SAF
 * 存成 `.txt`（复用 [DocumentWriter]，不申请任何存储权限）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 三条硬边界
 * ══════════════════════════════════════════════════════════════════════════
 * 1. **不调 AI**：摘要是本地模板渲染（[MedicalSummary]），0 配额消耗、断网可用 ——
 *    这是"离线路径"而非"降级路径"，理由见 `MedicalSummary` 的 KDoc。
 * 2. **不做医学判断**：只汇总用户自己记录的事实，每行都能回库核对。
 * 3. **日键走唯一入口**：今日 `day_key` 必须经 `dayStartHourOf` + `dayKeyOf`
 *    （与快捷录入 / QuotaGuard 同口径）—— 用户在设置里改过日界线后，若这里用
 *    `LocalDate.now()`，同一批记录会落在不同的时间窗里。
 */
class MedicalSummaryFragment : Fragment() {

    private var _binding: FragmentMedicalSummaryBinding? = null
    private val binding get() = _binding!!

    /**
     * 时间窗档位。用 enum 而不是三个 boolean —— 选中态只有**一个**来源，
     * 不会出现"两个都高亮"或"一个都没高亮"的中间态。
     */
    enum class WindowChoice(val months: Long, val labelRes: Int) {
        MONTHS_1(1, R.string.medical_window_1),
        MONTHS_3(3, R.string.medical_window_3),
        MONTHS_6(6, R.string.medical_window_6),
    }

    /** 当前时间窗。默认近 3 个月：覆盖一次常规复诊间隔，又不至于长到没重点。 */
    private var window: WindowChoice = WindowChoice.MONTHS_3

    /** 当前已渲染的摘要文本；`null` = 还没生成出来（此时「保存」不可用）。 */
    private var rendered: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMedicalSummaryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { NavHost.back(requireContext()) }
        binding.winMonth1.setOnClickListener { select(WindowChoice.MONTHS_1) }
        binding.winMonth3.setOnClickListener { select(WindowChoice.MONTHS_3) }
        binding.winMonth6.setOnClickListener { select(WindowChoice.MONTHS_6) }
        binding.btnSave.setOnClickListener { save() }

        select(WindowChoice.MONTHS_3)
    }

    /** 切档：先更新选中态再重算（重算是本地读库 + 纯函数，无需 loading 态）。 */
    private fun select(choice: WindowChoice) {
        window = choice
        renderOptions()
        reload()
    }

    /** 三个档位一次遍历：选中 `accent`、其余 `text_2`（唯一强调色纪律）。 */
    private fun renderOptions() {
        val items: List<Pair<TextView, WindowChoice>> = listOf(
            binding.winMonth1 to WindowChoice.MONTHS_1,
            binding.winMonth3 to WindowChoice.MONTHS_3,
            binding.winMonth6 to WindowChoice.MONTHS_6,
        )
        for ((label, choice) in items) {
            label.setText(choice.labelRes)
            label.setTextColor(
                color(if (choice == window) R.color.accent else R.color.text_2),
            )
        }
    }

    /** 读库 + 汇总 + 渲染（全在 IO 线程），完成后回主线程贴文本。 */
    private fun reload() {
        // 先把 context 取到手：进 IO 之后再 requireContext() 可能在 Fragment 已 detach 时抛异常
        val appContext: Context = requireContext().applicationContext
        val months = window.months

        rendered = null
        binding.btnSave.isEnabled = false
        binding.preview.text = ""

        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { build(appContext, months) }
            val b = _binding ?: return@launch // 视图已销毁（切页 / 退栈）
            rendered = text
            b.preview.text = text ?: getString(R.string.medical_preview_failed)
            b.btnSave.isEnabled = text != null
        }
    }

    /**
     * 组装摘要；任何一步失败返回 `null`（调用方显示"读取失败"并禁用保存）——
     * 宁可明说失败，也不给用户一份缺了半截的材料。
     *
     * ⚠️ 必须是 `suspend`：体内 `settingsDao().get(...)` 与 `eventDao().listInRange(...)`
     *    都是挂起方法。调用点 `withContext(Dispatchers.IO) { build(...) }` 的 block 本身
     *    是挂起上下文，所以这里标 suspend 即可（本机无 JDK，静态检查器查不出这类
     *    跨函数挂起调用 —— 提交前靠人工核对，见项目记忆的「已知盲区」）。
     */
    private suspend fun build(appContext: Context, months: Long): String? = runCatching {
        val db = HealixApp.from(appContext).database
        val today = dayKeyOf(
            System.currentTimeMillis(),
            dayStartHourOf(db.settingsDao().get(SettingsKeys.DAY_START)),
        )
        val from = LocalDate.parse(today).minusMonths(months).toString()
        val events = db.eventDao().listInRange(from, today)
        val doc = MedicalSummary.summarize(events, from, today)
        MedicalSummary.render(appContext, doc, System.currentTimeMillis())
    }.getOrNull()

    /** 交给 [DocumentWriter]（REQUEST_MEDICAL）——选位置 + 写文件全在那边。 */
    private fun save() {
        val text = rendered ?: return
        DocumentWriter.launch(
            activity = requireActivity(),
            requestCode = DocumentWriter.REQUEST_MEDICAL,
            mime = DocumentWriter.MIME_TEXT,
            fileName = fileName(),
            payload = text,
        )
    }

    /** 文件名是**位置信息**不是界面文案，故留在代码里（与 `ExportWriter.fileName` 同口径）。 */
    private fun fileName(): String = "healix-medical-${LocalDate.now()}.txt"

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(requireContext(), resId)
}
