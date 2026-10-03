package com.healix.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.RowSheetFieldBinding
import com.healix.app.databinding.SheetConfirmBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 抽取结果确认 / 编辑（组件规范 3.5）。
 *
 * **这是强制项，不是可选装饰**（总方案第四节第 6 条）：
 * 模型输出不能静默入库 —— 实测只有约 16–17/20 符合 schema，
 * 不让人过一眼就落库，等于把估算值当事实存下来。
 *
 * 复用于三个入口：主界面点列表项、对话页 propose_log 确认、通知栏撤销前的预览。
 */
class EventEditSheet : BottomSheetDialogFragment() {

    private var _binding: SheetConfirmBinding? = null
    private val binding get() = _binding!!

    private val fields = linkedMapOf<String, EditText>()
    private var clientEventId: String? = null
    private var entityId: Long = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetConfirmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        clientEventId = arguments?.getString(ARG_CLIENT_EVENT_ID)
        val cid = clientEventId
        if (cid == null) {
            dismiss()
            return
        }

        val container = HealixApp.from(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            val entity = withContext(Dispatchers.IO) { container.eventRepository.find(cid) }
            if (entity == null) {
                dismiss()
                return@launch
            }
            binding.rawText.text = entity.rawText
            entityId = entity.id

            val isFromChat = entity.origin == "ai_suggestion" || entity.source == "ai_suggestion"
            binding.sourceLabel.visibility = if (isFromChat) View.VISIBLE else View.GONE

            buildFields(entity)
        }

        binding.btnConfirm.setOnClickListener { save(container) }
        binding.btnReparse.setOnClickListener { reparse() }
        binding.btnDelete.setOnClickListener { delete() }
    }

    /** 按类型决定显示哪些字段 —— 不显示无意义的字段（如 meal 不显示 sleep_h）。 */
    private fun buildFields(entity: com.healix.app.db.EventEntity) {
        binding.fieldContainer.removeAllViews()
        fields.clear()

        addField(KEY_TYPE, R.string.field_type, entity.type)
        addField(KEY_TIME, R.string.field_time, entity.timeHint)

        when (entity.type) {
            "meal" -> {
                addField(KEY_FOODS, R.string.field_foods, parseFoods(entity.foods).joinToString("、"))
                addField(KEY_KCAL, R.string.field_kcal, entity.kcal.toString())
            }
            "exercise" -> {
                addField(KEY_EXERCISE, R.string.field_exercise, entity.exercise)
                addField(KEY_AMOUNT, R.string.field_amount, entity.amount)
                addField(KEY_KCAL, R.string.field_kcal, entity.kcal.toString())
            }
            "body" -> addField(KEY_WEIGHT, R.string.field_weight, trim(entity.weightKg))
            "sleep" -> addField(KEY_SLEEP, R.string.field_sleep, trim(entity.sleepH))
            "illness" -> addField(KEY_SYMPTOM, R.string.field_symptom, entity.symptom)
            else -> {
                addField(KEY_AMOUNT, R.string.field_amount, entity.amount)
                addField(KEY_KCAL, R.string.field_kcal, entity.kcal.toString())
            }
        }
    }

    private fun addField(key: String, labelRes: Int, initial: String) {
        val b = RowSheetFieldBinding.inflate(layoutInflater, binding.fieldContainer, false)
        b.fieldLabel.setText(labelRes)
        b.fieldValue.setText(initial)
        b.fieldValue.inputType = if (key == KEY_KCAL || key == KEY_WEIGHT || key == KEY_SLEEP) {
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        binding.fieldContainer.addView(b.root)
        fields[key] = b.fieldValue
    }

    private fun save(container: HealixApp) {
        val cid = clientEventId ?: return
        val values = fields.mapValues { it.value.text.toString().trim() }

        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                container.eventRepository.applyUserEdit(
                    clientEventId = cid,
                    type = values[KEY_TYPE].orEmpty().ifBlank { "other" },
                    timeHint = values[KEY_TIME].orEmpty(),
                    foods = values[KEY_FOODS].orEmpty(),
                    exercise = values[KEY_EXERCISE].orEmpty(),
                    amount = values[KEY_AMOUNT].orEmpty(),
                    kcal = values[KEY_KCAL]?.toIntOrNull() ?: 0,
                    symptom = values[KEY_SYMPTOM].orEmpty(),
                    weightKg = values[KEY_WEIGHT]?.toDoubleOrNull() ?: 0.0,
                    sleepH = values[KEY_SLEEP]?.toDoubleOrNull() ?: 0.0,
                )
            }
            dismiss()
        }
    }

    /** 重新识别：复用原 clientEventId 再走一次抽取链，不新增行。 */
    private fun reparse() {
        val cid = clientEventId ?: return
        val container = HealixApp.from(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val entity = withContext(Dispatchers.IO) { container.eventRepository.find(cid) }
            if (entity != null) {
                withContext(Dispatchers.IO) { container.eventRepository.retry(entity) }
            }
            dismiss()
        }
    }

    private fun delete() {
        val cid = clientEventId ?: return
        val container = HealixApp.from(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { container.eventRepository.undo(cid) }
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun parseFoods(json: String): List<String> = try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun trim(v: Double): String =
        if (v == 0.0) "" else if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    companion object {
        const val TAG = "EventEditSheet"

        private const val ARG_CLIENT_EVENT_ID = "client_event_id"

        private const val KEY_TYPE = "type"
        private const val KEY_TIME = "time"
        private const val KEY_FOODS = "foods"
        private const val KEY_EXERCISE = "exercise"
        private const val KEY_AMOUNT = "amount"
        private const val KEY_KCAL = "kcal"
        private const val KEY_SYMPTOM = "symptom"
        private const val KEY_WEIGHT = "weight"
        private const val KEY_SLEEP = "sleep"

        fun newInstance(clientEventId: String): EventEditSheet =
            EventEditSheet().apply {
                arguments = Bundle().apply { putString(ARG_CLIENT_EVENT_ID, clientEventId) }
            }
    }
}
