package com.healix.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.FragmentKnowledgeBinding
import com.healix.app.databinding.RowKnowledgeDocBinding
import com.healix.app.db.KnowledgeDocEntity
import com.healix.app.db.KnowledgeStatus
import kotlinx.coroutines.launch

/**
 * 知识库管理页（设计规范系统 10.3）。v8 T03：由 [KnowledgeBaseActivity] 迁为 Fragment。
 *
 * 关键行为：
 * - 上传 = SAF `ACTION_OPEN_DOCUMENT`（照 ExportWriter 的 SAF 先例，不申请存储权限），
 *   选中 **0 弹窗** 立即入列顶部（status=pending，第二行「解析中」），IO 线程后台解析；
 * - 新文档插列表顶部（最新在最上，与记录列表一致）；
 * - 四态全部由第二行文字承载（10.2），解析失败/扫描版一律中性色；
 * - 「＋ 添加 PDF 文件」纯文字项固定列表末尾，不用 FAB（9.7 ③ 先例）。
 *
 * 迁移两处要点：
 * 1. `supportFragmentManager` → `childFragmentManager`（文档操作弹窗随本页出栈）；
 * 2. 后台解析挂 **宿主的** `lifecycleScope` 而非 Fragment 的 —— 解析是「选中即开始」
 *    的持久任务，用户回退不该把它取消掉，否则文档会永远卡在「解析中」
 *    （Activity 时代是同一语义：那时离开即销毁 Activity，同样会丢）。
 *
 * v0.3 B3：改继承 [PageFragment]，但**不覆写** `onPageShown()` —— 数据为 Room Flow
 *   （`observeAll()`），天然实时，重新可见无需手动刷新。
 */
class KnowledgeBaseFragment : PageFragment() {

    private var _binding: FragmentKnowledgeBinding? = null
    private val binding get() = _binding!!

    private val repo by lazy { HealixApp.from(requireContext()).knowledgeRepository }

    /** 10.5 增量朗读缓存：doc.id → 上次朗读的状态文案（内存即可，不存库）。 */
    private val announcedStates = mutableMapOf<Long, String>()

    /** SAF 选文件：launcher 必须在 Fragment 创建阶段注册（state=INITIALIZED 才允许）。 */
    private val pickPdf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && isAdded) onPicked(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentKnowledgeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { NavHost.back(requireContext()) }
        binding.btnAdd.setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }
        binding.btnPick.setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }

        observe()
    }

    private fun onPicked(uri: android.net.Uri) {
        // 宿主作用域：解析必须活过本页（见类注释 2）
        requireActivity().lifecycleScope.launch {
            val docId = repo.enqueue(uri)
            repo.parse(docId) // 纯本地解析，飞行模式下可用（QA K7）
        }
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                HealixApp.from(requireContext()).database
                    .knowledgeDocDao().observeAll().collect { docs ->
                        render(docs)
                    }
            }
        }
    }

    private fun render(docs: List<KnowledgeDocEntity>) {
        val container = binding.docContainer
        container.removeAllViews()

        // 空态：隐藏「＋ 添加」纯文字项，主按钮接管（10.3 ②）
        binding.emptyState.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE
        binding.btnAdd.visibility = if (docs.isEmpty()) View.GONE else View.VISIBLE

        docs.forEach { doc ->
            val row = RowKnowledgeDocBinding.inflate(layoutInflater, container, false)
            row.docTitle.text = doc.title
            val desc = rowContentDescription(doc)
            row.docTitle.contentDescription = desc
            maybeAnnounceStateChange(row, doc, desc)

            renderSubtitle(row, doc)

            // 整行点击 = 操作弹窗；第二行可点 = 重试/引导（10.2，两者不冲突）
            row.docRow.setOnClickListener {
                KnowledgeDocSheet.newInstance(doc.id)
                    .show(childFragmentManager, KnowledgeDocSheet.TAG)
            }
            container.addView(row.root)
        }
    }

    /** 四态第二行：全部中性色，无 negative（QA K3/K4/K5）。 */
    private fun renderSubtitle(row: RowKnowledgeDocBinding, doc: KnowledgeDocEntity) {
        val ctx = row.docSubtitle.context
        row.docSubtitle.setOnClickListener(null)

        when {
            doc.status == KnowledgeStatus.PENDING || doc.status == KnowledgeStatus.PARSING -> {
                row.docSubtitle.setText(R.string.knowledge_parsing)
                row.docSubtitle.setTextColor(ContextCompat.getColor(ctx, R.color.text_3))
            }

            doc.status == KnowledgeStatus.FAILED &&
                doc.lastError == KnowledgeStatus.ERR_TOO_LARGE -> {
                row.docSubtitle.setText(R.string.knowledge_too_large)
                row.docSubtitle.setTextColor(ContextCompat.getColor(ctx, R.color.text_2))
                // 可点 → 弹窗引导删除（10.2 超限态）
                row.docSubtitle.setOnClickListener {
                    KnowledgeDocSheet.newInstance(doc.id)
                        .show(childFragmentManager, KnowledgeDocSheet.TAG)
                }
            }

            doc.status == KnowledgeStatus.FAILED -> {
                row.docSubtitle.setText(R.string.knowledge_failed)
                row.docSubtitle.setTextColor(ContextCompat.getColor(ctx, R.color.text_2))
                // 可点重试：重跑解析，成功后原地变就绪（QA K4）
                row.docSubtitle.setOnClickListener { retry(doc) }
            }

            // 就绪：纯元信息；chunk_count=0 即扫描版（QA K3）
            doc.chunkCount == 0 -> {
                row.docSubtitle.setText(R.string.knowledge_scanned)
                row.docSubtitle.setTextColor(ContextCompat.getColor(ctx, R.color.text_2))
                // 可点 → 弹窗引导换文字版或删除（10.2）
                row.docSubtitle.setOnClickListener {
                    KnowledgeDocSheet.newInstance(doc.id)
                        .show(childFragmentManager, KnowledgeDocSheet.TAG)
                }
            }

            else -> {
                val sizeMb = getString(R.string.knowledge_size_mb, doc.sizeBytes / 1024.0 / 1024.0)
                row.docSubtitle.text = getString(R.string.knowledge_meta, doc.pageCount, sizeMb)
                row.docSubtitle.setTextColor(ContextCompat.getColor(ctx, R.color.text_2))
            }
        }
    }

    private fun retry(doc: KnowledgeDocEntity) {
        // 同上：重试也是一次持久解析任务，不该被本页出栈取消
        requireActivity().lifecycleScope.launch {
            repo.parse(doc.id)
        }
    }

    /**
     * 10.5 增量朗读：文档行状态原地回填时（解析中 → N 页 · 大小 / 解析失败），
     * 朗读新状态一次；同一状态只读一次，重复刷新不重复读。
     * 文案复用 [rowContentDescription]（与 contentDescription 同源）。
     * 首次出现只记录不朗读 —— 否则进页会朗读整个列表。
     */
    private fun maybeAnnounceStateChange(
        row: RowKnowledgeDocBinding,
        doc: KnowledgeDocEntity,
        desc: String,
    ) {
        val last = announcedStates[doc.id]
        if (last == desc) return
        if (announcedStates.containsKey(doc.id)) {
            row.root.announceForAccessibility(desc)
        }
        announcedStates[doc.id] = desc
    }

    /** 10.5 无障碍：文档行朗读「标题，状态/页数」。 */
    private fun rowContentDescription(doc: KnowledgeDocEntity): String {
        val ctx = requireContext()
        return when {
            doc.status == KnowledgeStatus.PENDING || doc.status == KnowledgeStatus.PARSING ->
                "${doc.title}，${ctx.getString(R.string.knowledge_parsing)}"

            doc.chunkCount == 0 && doc.status == KnowledgeStatus.READY ->
                "${doc.title}，${ctx.getString(R.string.knowledge_scanned)}"

            else -> {
                val sizeMb = ctx.getString(R.string.knowledge_size_mb, doc.sizeBytes / 1024.0 / 1024.0)
                "${doc.title}，${ctx.getString(R.string.knowledge_meta, doc.pageCount, sizeMb)}。点击查看操作"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 朗读缓存随视图一起丢弃：下次进页是「首次出现」语义，不该朗读整列
        announcedStates.clear()
        _binding = null
    }
}
