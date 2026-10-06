package com.healix.app.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.healix.app.databinding.SheetActionConfirmBinding

/**
 * 可复用**确认**弹窗（§5.1 弹窗统一载体之一）。
 *
 * 复用 `EventEditSheet` 的容器规格（`bg_sheet` + [GrabberLayout] + 把手），
 * 承载"标题 + 单行消息 + 确认/取消"。用于 proposal 确认 / 预设删除确认，
 * 取代系统 `AlertDialog`（无底色容器原则，`docs/Healix设计规范系统.md` §3.5）。
 *
 * 用法：`ActionConfirmSheet.newInstance(title, message)` → 设 [onConfirm] →
 * `show(fm, TAG)`。取消（含下拖关闭）不回调 [onConfirm]；如设了 [onCancel] 则回调它。
 */
class ActionConfirmSheet : BottomSheetDialogFragment() {

    private var _binding: SheetActionConfirmBinding? = null
    private val binding get() = _binding!!

    /** 点「确认」时回调；点「取消」或下拖关闭不回调。 */
    var onConfirm: (() -> Unit)? = null

    /**
     * 取消（点「取消」/ 下拖关闭）时回调；点「确认」不回调。可选，默认无操作。
     *
     * 用途（v0.3 B6）：拟稿取消后要把 `tool_calls.approved` 回填为 0。
     */
    var onCancel: (() -> Unit)? = null

    /** 是否已点过「确认」——用来在 [onDismiss] 里区分"取消"与"确认后正常关闭"。 */
    private var confirmed = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetActionConfirmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val title = arguments?.getString(ARG_TITLE).orEmpty()
        if (title.isNotEmpty()) binding.sheetTitle.text = title
        binding.sheetMessage.text = arguments?.getString(ARG_MESSAGE).orEmpty()

        binding.root.onDragDismiss = { dismiss() }
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnConfirm.setOnClickListener {
            confirmed = true
            onConfirm?.invoke()
            dismiss()
        }
        binding.btnConfirm.bindPressScale()
    }

    /**
     * 关闭（取消 / 下拖 / 返回键）时回调 [onCancel]；确认则跳过。
     *
     * ⚠️ `onDismiss` 也可能因配置变更 / 进程重建而触发，那种情况下会**误判为取消**
     *    （回填 `approved = 0`）。这是可接受的边缘：回填只影响审计归因，不触碰业务数据。
     */
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!confirmed) onCancel?.invoke()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ActionConfirmSheet"

        private const val ARG_TITLE = "confirm_title"
        private const val ARG_MESSAGE = "confirm_message"

        fun newInstance(title: String, message: String): ActionConfirmSheet =
            ActionConfirmSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_MESSAGE, message)
                }
            }
    }
}
