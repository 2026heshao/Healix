package com.healix.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.healix.app.R
import com.healix.app.databinding.RowSettingValueBinding
import com.healix.app.databinding.SheetAddGoalBinding

/**
 * v8 需求 4：「添加目标」弹窗。
 *
 * 用途：目标组左滑删除 = 归档（`status='archived'`，数据不丢），本弹窗列出**当前未启用**
 * 的目标槽位，选中即恢复生效（[SettingsViewModel.ensureSlotActive] —— 曾归档的按原值恢复，
 * 从未创建过的按 `GoalDefaults` 补齐）。
 *
 * 载体复用 [GoalSetupSheet] / [FieldSheet] 的 `BottomSheetDialogFragment` 风格（§3.5）。
 *
 * 数据传递：槽位 `key` / 展示名由宿主（[SettingsFragment]）经 `arguments` 传入 ——
 * 弹窗自身**不读库**，只负责"选哪个"；写库与刷新留在 ViewModel。
 *
 * 用法：`AddGoalSheet.newInstance(keys, labels).apply { onPick = {...} }
 *       .show(childFragmentManager, AddGoalSheet.TAG)`。
 */
class AddGoalSheet : BottomSheetDialogFragment() {

    private var _binding: SheetAddGoalBinding? = null
    private val binding get() = _binding!!

    /** 选中某槽位时回调其 `key`；宿主据此调用 `vm.ensureSlotActive`。 */
    var onPick: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetAddGoalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val keys = arguments?.getStringArrayList(ARG_KEYS).orEmpty()
        val labels = arguments?.getStringArrayList(ARG_LABELS).orEmpty()

        // 下拖关闭（GrabberLayout 已排除 input / button 上的误拖）
        binding.root.onDragDismiss = { dismiss() }
        binding.btnCancel.setOnClickListener { dismiss() }

        binding.optionContainer.removeAllViews()
        if (keys.isEmpty()) {
            // 兜底：宿主一般不会在无可选项时打开本弹窗
            binding.emptyText.visibility = View.VISIBLE
            return
        }

        keys.forEachIndexed { index, key ->
            val row = RowSettingValueBinding.inflate(layoutInflater, binding.optionContainer, false)
            row.label.text = labels.getOrNull(index).orEmpty()
            row.chevron.visibility = View.VISIBLE
            row.root.setOnClickListener {
                onPick?.invoke(key)
                dismiss()
            }
            binding.optionContainer.addView(row.root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AddGoalSheet"

        private const val ARG_KEYS = "keys"
        private const val ARG_LABELS = "labels"

        fun newInstance(keys: List<String>, labels: List<String>): AddGoalSheet =
            AddGoalSheet().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_KEYS, ArrayList(keys))
                    putStringArrayList(ARG_LABELS, ArrayList(labels))
                }
            }
    }
}
