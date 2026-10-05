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
 * 需求 5：首次进入目标引导弹窗。
 *
 * 载体复用 [FieldSheet] 风格（`BottomSheetDialogFragment`）而非系统 `AlertDialog` ——
 * 规避需求 3 的按钮坑，且贴合「无底色容器」规范（`docs/Healix设计规范系统.md` §3.5）。
 *
 * 交互：选主目标（增重 / 减重 / 保持）→ 可选填目标体重 → 「保存」；
 * 或「跳过」。**任何未经「保存」的关闭**（点跳过 / 下拖 / 点外部 / 返回）
 * 都回调 `onDone(null, null)` —— 宿主据此只写 `GOAL_SETUP_DONE` 标记，
 * 保证引导**只弹一次**、不反复骚扰；首页「主目标」行始终留着「去调整」入口。
 *
 * 用法：`GoalSetupSheet.newInstance().apply { onDone = {...} }.show(childFragmentManager, TAG)`。
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

        // 目标体重字段：数值（带小数），标签与占位来自 strings.xml
        binding.weightField.fieldLabel.setText(R.string.goal_setup_weight_label)
        binding.weightField.fieldValue.hint = getString(R.string.goal_setup_weight_hint)
        binding.weightField.fieldValue.inputType =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

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

    companion object {
        const val TAG = "GoalSetupSheet"

        fun newInstance(): GoalSetupSheet = GoalSetupSheet()
    }
}
