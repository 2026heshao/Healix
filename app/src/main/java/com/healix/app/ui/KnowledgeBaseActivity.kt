package com.healix.app.ui

import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.ActivityKnowledgeBinding
import com.healix.app.databinding.RowKnowledgeDocBinding
import com.healix.app.db.KnowledgeDocEntity
import com.healix.app.db.KnowledgeStatus
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * 知识库管理页（设计规范系统 10.3）。
 *
 * 关键行为：
 * - 上传 = SAF `ACTION_OPEN_DOCUMENT`（照 ExportWriter 的 SAF 先例，不申请存储权限），
 *   选中 **0 弹窗** 立即入列顶部（status=pending，第二行「解析中」），IO 线程后台解析；
 * - 新文档插列表顶部（最新在最上，与记录列表一致）；
 * - 四态全部由第二行文字承载（10.2），解析失败/扫描版一律中性色；
 * - 「＋ 添加 PDF 文件」纯文字项固定列表末尾，不用 FAB（9.7 ③ 先例）。
 */
class KnowledgeBaseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKnowledgeBinding
    private val repo by lazy { HealixApp.from(this).knowledgeRepository }

    /** SAF 选文件：launcher 必须在 Activity 创建阶段注册。 */
    private val pickPdf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKnowledgeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAdd.setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }
        binding.btnPick.setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }

        observe()
    }

    private fun onPicked(uri: android.net.Uri) {
        lifecycleScope.launch {
            val docId = repo.enqueue(uri)
            repo.parse(docId) // 纯本地解析，飞行模式下可用（QA K7）
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HealixApp.from(this@KnowledgeBaseActivity).database
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
            row.docTitle.contentDescription = rowContentDescription(doc)

            renderSubtitle(row, doc)

            // 整行点击 = 操作弹窗；第二行可点 = 重试/引导（10.2，两者不冲突）
            row.docRow.setOnClickListener {
                KnowledgeDocSheet.newInstance(doc.id)
                    .show(supportFragmentManager, KnowledgeDocSheet.TAG)
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
                        .show(supportFragmentManager, KnowledgeDocSheet.TAG)
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
                        .show(supportFragmentManager, KnowledgeDocSheet.TAG)
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
        lifecycleScope.launch {
            repo.parse(doc.id)
        }
    }

    /** 10.5 无障碍：文档行朗读「标题，状态/页数」。 */
    private fun rowContentDescription(doc: KnowledgeDocEntity): String {
        val ctx = this
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

    /** v6 11.2：二级页返回走 in_back 转场（覆盖返回键与手势返回）。 */
    override fun finish() {
        super.finish()
        TabBar.backOut(this)
    }
}
