package com.healix.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.healix.app.databinding.RowSheetFieldBinding
import com.healix.app.databinding.SheetFieldEditBinding

/**
 * 可复用**字段输入**弹窗（§5.1 弹窗统一载体之一）。
 *
 * 背景：设置页 / 预设管理页原先用系统 `AlertDialog` 做字段编辑，与设计规范
 * 「无底色容器」原则冲突（`docs/Healix设计规范系统.md` §3.5 / 第 1328 行）。
 * 本组件复用 `EventEditSheet` 的容器规格（`bg_sheet` + [GrabberLayout] + 把手 +
 * `row_sheet_field` 字段行），承载**输入类**弹窗；**确认类**由 `ActionConfirmSheet`
 * 承载。列表选择类（`setItems`）本轮保留系统 AlertDialog（§5.1 边界）。
 *
 * 用法：`FieldSheet.newInstance(titleRes, specs)` → 设 [onResult] → `show(fm, TAG)`。
 * 用回调 `var`（瞬时弹窗，非跨配置持久场景），与既有 `EventEditSheet` /
 * `KnowledgeDocSheet` 的"Fragment 自持"风格一致，避免 FragmentResult 样板。
 *
 * ⚠️ 字段规格通过 `arguments` 传（`IntArray` / `Array<String>`），保证弹窗在
 * 配置变更 / 进程重建后仍能正确恢复 —— 不把 [FieldSpec] 作为成员直接持有。
 */
class FieldSheet : BottomSheetDialogFragment() {

    private var _binding: SheetFieldEditBinding? = null
    private val binding get() = _binding!!

    /** 确认时回传各字段的最终文本（顺序与 specs 一致）；取消不回调。 */
    var onResult: ((List<String>) -> Unit)? = null

    private val inputs = mutableListOf<EditText>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetFieldEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val titleRes = arguments?.getInt(ARG_TITLE, 0) ?: 0
        if (titleRes != 0) binding.sheetTitle.setText(titleRes)

        buildFields()

        // v6（11.5）：可下拖关闭；input/button 上的按下已被 GrabberLayout 排除
        binding.root.onDragDismiss = { dismiss() }
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnConfirm.setOnClickListener {
            onResult?.invoke(inputs.map { it.text.toString().trim() })
            dismiss()
        }
        binding.btnConfirm.bindPressScale()
    }

    /** 按 [ARG_LABELS] / [ARG_INITIALS] / [ARG_TYPES] 重建字段行（复用 row_sheet_field）。 */
    private fun buildFields() {
        binding.fieldContainer.removeAllViews()
        inputs.clear()
        for (spec in parsedSpecs()) {
            val b = RowSheetFieldBinding.inflate(layoutInflater, binding.fieldContainer, false)
            b.fieldLabel.setText(spec.labelRes)
            b.fieldValue.setText(spec.initial)
            b.fieldValue.inputType = spec.inputType
            // maxLength > 0 时**输入期**即硬截断（不能只在保存时截 —— 会静默丢数据）
            if (spec.maxLength > 0) {
                b.fieldValue.filters = arrayOf(android.text.InputFilter.LengthFilter(spec.maxLength))
            }
            b.fieldValue.setSelection(b.fieldValue.text.length)
            binding.fieldContainer.addView(b.root)
            inputs += b.fieldValue
        }
    }

    private fun parsedSpecs(): List<FieldSpec> {
        val labels = arguments?.getIntArray(ARG_LABELS) ?: IntArray(0)
        val initials = arguments?.getStringArray(ARG_INITIALS) ?: emptyArray()
        val types = arguments?.getIntArray(ARG_TYPES) ?: IntArray(0)
        val maxLengths = arguments?.getIntArray(ARG_MAXLENS) ?: IntArray(0)
        return labels.indices.map { i ->
            FieldSpec(
                labelRes = labels[i],
                initial = initials.getOrElse(i) { "" },
                inputType = types.getOrElse(i) { InputType.TYPE_CLASS_TEXT },
                maxLength = maxLengths.getOrElse(i) { 0 },
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 字段行规格（label 用资源 id，避免硬编码中文）。
     * `maxLength = 0` 表示不限制（默认，向后兼容既有调用点）。
     *
     * ⚠️ 必须作为 [FieldSheet] 的**直接嵌套类**，不能放进 companion object：
     * 外部（PersonalInfoActivity / PresetManageActivity / SettingsActivity）一律以
     * `FieldSheet.FieldSpec` 限定引用，而 companion object 内部声明的嵌套类
     * **无法经外层类名访问** —— CI run#35 的 29 条 `Unresolved reference 'FieldSpec'`
     * 及级联 `Symbol not found for FieldSheet.FieldSpec` 即由此而来。
     */
    data class FieldSpec(
        val labelRes: Int,
        val initial: String,
        val inputType: Int,
        val maxLength: Int = 0,
    )

    companion object {
        const val TAG = "FieldSheet"

        private const val ARG_TITLE = "field_title_res"
        private const val ARG_LABELS = "field_labels"
        private const val ARG_INITIALS = "field_initials"
        private const val ARG_TYPES = "field_types"
        private const val ARG_MAXLENS = "field_max_lens"

        fun newInstance(titleRes: Int, specs: List<FieldSpec>): FieldSheet =
            FieldSheet().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TITLE, titleRes)
                    putIntArray(ARG_LABELS, specs.map { it.labelRes }.toIntArray())
                    putStringArray(ARG_INITIALS, specs.map { it.initial }.toTypedArray())
                    putIntArray(ARG_TYPES, specs.map { it.inputType }.toIntArray())
                    putIntArray(ARG_MAXLENS, specs.map { it.maxLength }.toIntArray())
                }
            }
    }
}
