package com.healix.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.SheetKnowledgeDocBinding
import com.healix.app.db.KnowledgeDocEntity
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * 文档操作弹窗（设计规范系统 10.3 ③）。
 *
 * 复用 ConfirmSheet 容器规格（bg_sheet + 把手），不新造弹窗组件。
 * 删除必须二次确认：点「删除文档」后**原地切换**内容为确认态
 * （`announceForAccessibility` 朗读），不用系统 AlertDialog（无底色容器原则）。
 * 这是本模块唯一的确认弹窗 —— 文档没有撤销条。
 */
class KnowledgeDocSheet : BottomSheetDialogFragment() {

    private var _binding: SheetKnowledgeDocBinding? = null
    private val binding get() = _binding!!

    private var doc: KnowledgeDocEntity? = null
    private var confirmMode = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetKnowledgeDocBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val docId = arguments?.getLong(ARG_DOC_ID) ?: 0L
        viewLifecycleOwner.lifecycleScope.launch {
            val db = HealixApp.from(requireContext()).database
            val entity = db.knowledgeDocDao().getById(docId)
            if (entity == null) {
                dismiss()
                return@launch
            }
            doc = entity
            renderNormal()
        }

        binding.btnOpen.setOnClickListener {
            doc?.let { openOriginal(it) }
        }
        binding.btnDelete.setOnClickListener { onDeleteClicked() }
        binding.btnCancel.setOnClickListener {
            if (confirmMode) {
                confirmMode = false
                renderNormal()
            } else {
                dismiss()
            }
        }
    }

    /** 常态：标题 = 文件名，元信息 = 页数 · 大小 · 添加日期。 */
    private fun renderNormal() {
        val d = doc ?: return
        confirmMode = false
        binding.sheetTitle.text = d.title
        binding.sheetMeta.visibility = View.VISIBLE
        binding.sheetMeta.text = buildString {
            append(context?.getString(R.string.knowledge_meta, d.pageCount, sizeLabel(d)) ?: "")
            append(" · ")
            append(dateLabel(d.addedAt))
        }
    }

    /**
     * 删除二次确认：原地切换内容（10.3 ③），并朗读确认文案（10.5）。
     */
    private fun renderConfirm() {
        confirmMode = true
        binding.sheetTitle.setText(R.string.knowledge_delete_hint)
        binding.sheetMeta.visibility = View.GONE
        view?.announceForAccessibility(getString(R.string.knowledge_delete_hint))
    }

    private fun onDeleteClicked() {
        val d = doc ?: return
        if (!confirmMode) {
            renderConfirm()
            return
        }
        val repo = HealixApp.from(requireContext()).knowledgeRepository
        viewLifecycleOwner.lifecycleScope.launch {
            repo.delete(d)
            dismiss()
        }
    }

    /** 「打开原文」交给系统 PDF 查看器（10.9：不做文档内全文阅读器）。 */
    private fun openOriginal(d: KnowledgeDocEntity) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(d.uri), "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            // 无 PDF 查看器时静默关闭动作 —— 文件本身从未损坏
        }
    }

    private fun sizeLabel(d: KnowledgeDocEntity): String =
        getString(R.string.knowledge_size_mb, d.sizeBytes / 1024.0 / 1024.0)

    private fun dateLabel(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "KnowledgeDocSheet"

        private const val ARG_DOC_ID = "doc_id"

        fun newInstance(docId: Long): KnowledgeDocSheet =
            KnowledgeDocSheet().apply {
                arguments = Bundle().apply { putLong(ARG_DOC_ID, docId) }
            }
    }
}
