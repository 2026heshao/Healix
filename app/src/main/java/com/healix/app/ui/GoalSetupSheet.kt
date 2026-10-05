package com.healix.app.ui

import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.healix.app.R
import com.healix.app.databinding.SheetGoalSetupBinding

/**
 * 需求 5：目标引导弹窗（**首启引导** 与 **记录页「去调整」编辑态** 共用一个组件）。
 *
 * 载体复用 [FieldSheet] 风格（`BottomSheetDialogFragment`）而非系统 `AlertDialog` ——
 * 规避需求 3 的按钮坑，且贴合「无底色容器」规范（`docs/Healix设计规范系统.md` §3.5）。
 *
 * 交互：选主目标（增重 / 减重 / 保持）→ 可选填目标体重 → 「保存」；
 * 或「跳过」。**任何未经「保存」的关闭**（点跳过 / 下拖 / 点外部 / 返回）
 * 都回调 `onDone(null, null)` —— 宿主据此只写 `GOAL_SETUP_DONE` 标记，
 * 保证首启引导**只弹一次**、不反复骚扰；首页「主目标」行始终留着「去调整」入口。
 *
 * v8 问题 4：新增**编辑态**（[newInstanceForEdit]）—— 记录页「去调整 ›」开的是这一支：
 * 预填当前主目标模式与目标体重、换标题文案、**不给「跳过」**（没有"跳过修改"的语义，
 * 关掉即等于不改）。保存路径与首启引导**完全同一条**，不新增第二种写库逻辑。
 * 这与规范 §①「去调整 → GoalSetupSheet 编辑态，保存后就地返回记录页」一致。
 *
 * 用法：
 * - 首启：`GoalSetupSheet.newInstance().apply { onDone = {...} }.show(childFragmentManager, TAG)`
 * - 编辑：`GoalSetupSheet.newInstanceForEdit(mode, weightKg).apply { onDone = {...} }.show(...)`
 */
class GoalSetupSheet : BottomSheetDialogFragment() {

    private var _binding: SheetGoalSetupBinding? = null
    private val binding get() = _binding!!

    /**
     * 结果回调。
     *
     * @param modeIndex 主目标模式（0=增重 / 1=减重 / 2=保持）；**null = 跳过**（不设主目标）
     * @param weightKg 目标体重（kg）；null = 未填或跳过
     */
    var onDone: ((modeIndex: Int?, weightKg: Double?) -> Unit)? = null

    private var selected: Int = SettingsViewModel.GOAL_MODE_GAIN

    /** 是否经「保存」完成 —— 否则关闭一律视作跳过（见 [onDismiss]）。 */
    private var saved = false

    /** 编辑态（记录页「去调整」）：预填当前值、不给「跳过」。首启引导为 false。 */
    private var editMode = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetGoalSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editMode = requireArguments().getBoolean(ARG_EDIT, false)
        if (editMode) {
            selected = requireArguments().getInt(ARG_MODE, SettingsViewModel.GOAL_MODE_GAIN)
            binding.sheetTitle.setText(R.string.goal_setup_title_edit)
            binding.sheetDesc.setText(R.string.goal_setup_desc_edit)
            // 编辑态没有「跳过」的语义：关掉弹层 = 保持原样，不留一个会让人误以为"清空主目标"的入口
            binding.btnSkip.visibility = View.GONE
        }

        // 目标体重字段：数值（带小数），标签与占位来自 strings.xml
        binding.weightField.fieldLabel.setText(R.string.goal_setup_weight_label)
        binding.weightField.fieldValue.hint = getString(R.string.goal_setup_weight_hint)
        binding.weightField.fieldValue.inputType =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        if (editMode) {
            val w = requireArguments().getDouble(ARG_WEIGHT, 0.0)
            if (w > 0.0) binding.weightField.fieldValue.setText(trimNumber(w))
        }

        listOf(
            binding.optionGain to SettingsViewModel.GOAL_MODE_GAIN,
            binding.optionLoss to SettingsViewModel.GOAL_MODE_LOSS,
            binding.optionKeep to SettingsViewModel.GOAL_MODE_KEEP,
        ).forEach { (option, mode) ->
            option.setOnClickListener {
                selected = mode
                renderSelection()
            }
        }
        renderSelection()

        // 下拖关闭（GrabberLayout 已排除 input / button 上的误拖）
        binding.root.onDragDismiss = { dismiss() }

        binding.btnSkip.setOnClickListener { dismiss() }
        binding.btnSave.setOnClickListener {
            val w = binding.weightField.fieldValue.text?.toString()?.trim()?.toDoubleOrNull()
            saved = true
            onDone?.invoke(selected, if (w != null && w > 0) w else null)
            dismiss()
        }
        binding.btnSave.bindPressScale()
    }

    /** 选中项文字色 = accent + 加粗；未选中 = text_2（无彩色面，规范 §2.3）。 */
    private fun renderSelection() {
        val options = listOf(
            binding.optionGain to SettingsViewModel.GOAL_MODE_GAIN,
            binding.optionLoss to SettingsViewModel.GOAL_MODE_LOSS,
            binding.optionKeep to SettingsViewModel.GOAL_MODE_KEEP,
        )
        for ((view, mode) in options) {
            val on = mode == selected
            view.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (on) R.color.accent else R.color.text_2,
                ),
            )
            view.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 任何关闭都要给宿主一个结果 —— 否则标记不落、每次启动都弹（"反复骚扰"）。
     * 未经「保存」的关闭视为跳过（`modeIndex = null`）。
     */
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!saved) onDone?.invoke(null, null)
    }

    /** 去掉无意义的小数尾巴：70.0 → "70"，70.5 → "70.5"。 */
    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    companion object {
        const val TAG = "GoalSetupSheet"

        private const val ARG_EDIT = "edit"
        private const val ARG_MODE = "mode"
        private const val ARG_WEIGHT = "weight"

        /** 首启引导（无预填、含「跳过」）。 */
        fun newInstance(): GoalSetupSheet = GoalSetupSheet()

        /**
         * 编辑态（v8 问题 4）：记录页「去调整 ›」开这一支。
         * 预填 [modeIndex] 与 [weightKg]（`<=0` 视为未设，留空）。
         */
        fun newInstanceForEdit(modeIndex: Int, weightKg: Double): GoalSetupSheet =
            GoalSetupSheet().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_EDIT, true)
                    putInt(ARG_MODE, modeIndex)
                    putDouble(ARG_WEIGHT, weightKg)
                }
            }
    }
}
