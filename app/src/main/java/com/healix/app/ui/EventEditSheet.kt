package com.healix.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.RowSheetFieldBinding
import com.healix.app.databinding.SheetConfirmBinding
import com.healix.app.db.EventEntity
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
 *
 * ══════════════════════════════════════════════════════════════════════════
 * v8 需求 9 功能 2：「上次值」记忆回显（来源 Hevy / MyFitnessPal）
 * ══════════════════════════════════════════════════════════════════════════
 * 本次抽取**没给出内容**的字段（空串，或数值型的 `0`），若同类型上一条记录有该字段的
 * 非空值，就把它预填进输入框并渲染为 **`text_3` 灰字**；用户一旦改动该字段即恢复
 * `text_1` 常色 —— 于是"这个值是我填的、还是沿用上次的"一眼可辨。
 *
 * 三条边界（都不是风格问题）：
 * 1. **不新增数据库列、不改协议**：来源就是 `events` 自己的历史行
 *    （`EventRepository.latestByType`，见 `EventDao.latestByTypeExcluding`）。
 * 2. **排除本行**：本行 `ts` 最大，不排除就会把自己的值当"上次值"回显给自己。
 * 3. **不伪造数据**：灰字值只有在本弹窗被**确认**时才落库，而本弹窗本就是
 *    「模型输出必须人工过一眼」的强制闸门 —— 值全程可见、可改，不存在静默写入。
 *    没有可比的历史时保持原样（空 / `0`），不凭空造一个数出来。
 */
class EventEditSheet : BottomSheetDialogFragment() {

    private var _binding: SheetConfirmBinding? = null
    private val binding get() = _binding!!

    private val fields = linkedMapOf<String, EditText>()
    private var clientEventId: String? = null
    private var entityId: Long = 0

    /** R2-1：类型选择行当前选中的枚举值（null = 用户没改过，沿用原类型）。 */
    private var typeSelected: String? = null

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

            // 需求 9 功能 2：同类型上一条（排除本行）→ 本次没抽出的字段走灰字预填。
            // 取不到（无历史 / 读库异常）就传 null，弹窗照常按现状渲染。
            val previous = withContext(Dispatchers.IO) {
                container.eventRepository.latestByType(entity.type, cid)
            }

            buildFields(entity, previous)
        }

        binding.btnConfirm.setOnClickListener { save(container) }
        binding.btnReparse.setOnClickListener { reparse() }
        binding.btnDelete.setOnClickListener { delete() }

        // v6（11.5）：弹盘可下拖关闭 —— 超过 80dp 松手即关，否则弹回。
        // input / button 上的按下已被 GrabberLayout 排除（防表单误拖）。
        binding.root.onDragDismiss = { dismiss() }
        binding.btnConfirm.bindPressScale()
    }

    /**
     * 按类型决定显示哪些字段 —— 不显示无意义的字段（如 meal 不显示 sleep_h）。
     *
     * @param previous 同类型上一条记录（排除本行；null = 无历史）。只用来给**本次没抽出
     *                 内容**的字段做灰字预填，见类的 KDoc「需求 9 功能 2」。
     */
    private fun buildFields(entity: EventEntity, previous: EventEntity?) {
        binding.fieldContainer.removeAllViews()
        fields.clear()

        // R2-1（2026-10-10）：类型行不再是「让用户手输英文枚举」的文本框 ——
        // 实测弹窗里裸显 `other`，用户根本不可能知道合法值是
        // meal/exercise/body/sleep/illness。改为**只读行 + 点击弹选择表**：
        // 展示中文标签（EventText.typeName），保存前把中文映射回枚举。
        addTypeField(entity.type)
        addField(KEY_TIME, R.string.field_time, entity.timeHint)

        when (entity.type) {
            "meal" -> {
                addField(
                    KEY_FOODS, R.string.field_foods,
                    parseFoods(entity.foods).joinToString("、"),
                    carried = previous?.let { parseFoods(it.foods).joinToString("、") }.orEmpty(),
                )
                addField(
                    KEY_KCAL, R.string.field_kcal, entity.kcal.toString(),
                    carried = positiveInt(previous?.kcal),
                )
            }
            "exercise" -> {
                addField(
                    KEY_EXERCISE, R.string.field_exercise, entity.exercise,
                    carried = previous?.exercise.orEmpty(),
                )
                addField(
                    KEY_AMOUNT, R.string.field_amount, entity.amount,
                    carried = previous?.amount.orEmpty(),
                )
                addField(
                    KEY_KCAL, R.string.field_kcal, entity.kcal.toString(),
                    carried = positiveInt(previous?.kcal),
                )
            }
            "body" -> addField(
                KEY_WEIGHT, R.string.field_weight, trim(entity.weightKg),
                carried = positiveDouble(previous?.weightKg),
            )
            "sleep" -> addField(
                KEY_SLEEP, R.string.field_sleep, trim(entity.sleepH),
                carried = positiveDouble(previous?.sleepH),
            )
            "illness" -> addField(
                KEY_SYMPTOM, R.string.field_symptom, entity.symptom,
                carried = previous?.symptom.orEmpty(),
            )
            else -> {
                addField(
                    KEY_AMOUNT, R.string.field_amount, entity.amount,
                    carried = previous?.amount.orEmpty(),
                )
                addField(
                    KEY_KCAL, R.string.field_kcal, entity.kcal.toString(),
                    carried = positiveInt(previous?.kcal),
                )
            }
        }
    }

    /**
     * 追加一行字段。
     *
     * @param carried 「上次值」候选（空串 = 没有可比的历史）。仅当 [initial] **本次没有内容**
     *                （空串或数值型的 `0`）时才启用 —— 已经有本次识别结果时，历史值无权覆盖它。
     */
    private fun addField(key: String, labelRes: Int, initial: String, carried: String = "") {
        val b = RowSheetFieldBinding.inflate(layoutInflater, binding.fieldContainer, false)
        b.fieldLabel.setText(labelRes)

        val carriedUsed = carried.isNotBlank() && (initial.isBlank() || isZeroish(initial))
        b.fieldValue.setText(if (carriedUsed) carried else initial)
        b.fieldValue.inputType = if (key == KEY_KCAL || key == KEY_WEIGHT || key == KEY_SLEEP) {
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        if (carriedUsed) markAsCarried(b.fieldValue)

        binding.fieldContainer.addView(b.root)
        fields[key] = b.fieldValue
    }

    /**
     * R2-1：类型行 —— 只读展示中文类型名，点击弹选择表（含「未识别的其他」共 6 项）。
     * 选中后把**枚举值**存回 [typeSelected]（不进 fields 输入框，避免用户看到/改坏英文值）；
     * [save] 优先取它，没选过就沿用 entity 原类型。
     */
    private fun addTypeField(currentType: String) {
        val b = RowSheetFieldBinding.inflate(layoutInflater, binding.fieldContainer, false)
        b.fieldLabel.setText(R.string.field_type)
        b.fieldValue.setText(com.healix.app.notify.EventText.typeName(requireContext(), currentType))
        b.fieldValue.inputType = InputType.TYPE_NULL
        b.fieldValue.keyListener = null
        b.fieldValue.isFocusable = false
        b.fieldValue.isClickable = true
        typeSelected = currentType
        b.fieldValue.setOnClickListener {
            val types = listOf("meal", "exercise", "body", "sleep", "illness", "other")
            val labels = types.map { com.healix.app.notify.EventText.typeName(requireContext(), it) }
                .toTypedArray()
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.field_type)
                .setItems(labels) { _, which ->
                    typeSelected = types[which]
                    b.fieldValue.setText(labels[which])
                }
                .show()
        }
        binding.fieldContainer.addView(b.root)
    }

    /**
     * 把输入框标成「沿用上次」态：`text_3` 灰字；**用户一改动即恢复 `text_1`**。
     *
     * 监听器在 `setText` **之后**挂上，所以预填本身不会触发恢复（否则灰字永远看不到）。
     */
    private fun markAsCarried(input: EditText) {
        input.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_3))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                input.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_1))
                input.removeTextChangedListener(this)
            }
        })
    }

    /** 数值串是否等价于「没有内容」：本次抽不出来时数值字段留的是 `0` / `0.0`。 */
    private fun isZeroish(s: String): Boolean = s == "0" || s == "0.0"

    /** 正数才有意义 → 非正数转空串（不做「上次值」）；格式与 [trim] 同口径。 */
    private fun positiveInt(v: Int?): String = if (v == null || v <= 0) "" else v.toString()

    /** 同上，Double 版。 */
    private fun positiveDouble(v: Double?): String = if (v == null || v <= 0.0) "" else trim(v)

    private fun save(container: HealixApp) {
        val cid = clientEventId ?: return
        val values = fields.mapValues { it.value.text.toString().trim() }

        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                container.eventRepository.applyUserEdit(
                    clientEventId = cid,
                    // R2-1：类型来自选择行（枚举值），不再是用户手输的自由文本。
                    type = typeSelected ?: "other",
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
