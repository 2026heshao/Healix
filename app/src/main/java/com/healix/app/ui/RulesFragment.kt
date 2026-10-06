package com.healix.app.ui

import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.healix.app.R
import com.healix.app.databinding.FragmentRulesBinding
import com.healix.app.databinding.RowRuleManageBinding
import com.healix.app.db.AiRuleEntity
import com.healix.app.db.RULE_TEXT_MAX_LEN
import kotlinx.coroutines.launch

/**
 * 规则库二级页（v0.3 B4）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 这是什么
 * ══════════════════════════════════════════════════════════════════════════
 * 用户自定义「输出偏好」的多行列表（长度 / 风格 / 语气 / 人格可调）。规则整段拼进
 * 对话 system prompt（见 `ChatEngine.userRulesBlock`）—— 与硬边界冲突时一律以硬边界为准
 * （决策 D2：忌口 / 疼痛 / 医疗安全 / 隐私四条不可被用户规则覆盖）。
 *
 * 交互：
 * - **点行** = 编辑文本；**长按** = 上移 / 下移 / 删除；
 * - **右侧开关** = 启停（关掉 = 不注入，但**不删除**，随时可再开）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 基类与刷新（决策 RC-1）
 * ══════════════════════════════════════════════════════════════════════════
 * 继承 W1 的 [PageFragment]（keep-alive 二级页基类）。**不覆写** `onPageShown()`：
 * 数据源是 Room `Flow`（[RulesViewModel.rules] → `observeAll()`），天然实时、跨页
 * 编辑也会自动回写 —— 与 [PresetManageFragment] 同一理由（它同样不覆写）。
 * 继承 [PageFragment] 满足 RC-1，同时保留"被覆盖页不重走 onResume"的刷新正解契约。
 *
 * ⚠️ 无 `fragment-ktx`：取 VM 用 `ViewModelProvider(this)[RulesViewModel::class.java]`；
 *    弹层挂 `childFragmentManager`（编辑用 [FieldSheet]，删除确认用 [ActionConfirmSheet]）。
 */
internal class RulesFragment : PageFragment() {

    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding!!

    private val vm: RulesViewModel by lazy {
        ViewModelProvider(this)[RulesViewModel::class.java]
    }

    private val adapter = RuleAdapter(
        onEdit = { showEditor(it) },
        onToggle = { rule, enabled -> vm.setEnabled(rule, enabled) },
        onActions = { showActions(it) },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { NavHost.back(requireContext()) }
        binding.btnAdd.setOnClickListener { showEditor(null) }

        binding.ruleList.layoutManager = LinearLayoutManager(requireContext())
        binding.ruleList.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.rules.collect { list ->
                    adapter.submit(list)
                    binding.emptyHint.visibility =
                        if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    /**
     * 编辑 / 新建弹窗（循 [PresetManageFragment.showEditor] 先例）：`rule == null` = 新建。
     * 走 [FieldSheet] 底色容器（取代系统 AlertDialog）。文本必填（空则不落库）；
     * 输入期即硬截断 [RULE_TEXT_MAX_LEN]（不只保存时截 —— 会静默丢数据）。
     */
    private fun showEditor(rule: AiRuleEntity?) {
        val specs = listOf(
            FieldSheet.FieldSpec(
                R.string.rules_field_text,
                rule?.text.orEmpty(),
                InputType.TYPE_CLASS_TEXT,
                RULE_TEXT_MAX_LEN,
            ),
        )
        val sheet = FieldSheet.newInstance(
            if (rule == null) R.string.rules_new_title else R.string.rules_edit_title,
            specs,
        )
        sheet.onResult = { raw ->
            val text = raw.firstOrNull().orEmpty().trim()
            if (rule == null) vm.add(text) else vm.update(rule, text)
        }
        sheet.show(childFragmentManager, FieldSheet.TAG)
    }

    /**
     * 长按行 → 动作表（上移 / 下移 / 删除）。
     *
     * 排序走 [RulesViewModel.move]（交换 `sort_order`）；删除走 [ActionConfirmSheet]
     * 二次确认（规则无软删链路，删除不可恢复）。
     */
    private fun showActions(rule: AiRuleEntity) {
        val items = arrayOf<CharSequence>(
            getString(R.string.rules_move_up),
            getString(R.string.rules_move_down),
            getString(R.string.delete),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rules_actions_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> vm.move(rule, up = true)
                    1 -> vm.move(rule, up = false)
                    else -> confirmDelete(rule)
                }
            }
            .setNegativeButton(R.string.cancel, null as DialogInterface.OnClickListener?)
            .show()
    }

    /** 删除二次确认：走 [ActionConfirmSheet] 底色容器。 */
    private fun confirmDelete(rule: AiRuleEntity) {
        val sheet = ActionConfirmSheet.newInstance(
            getString(R.string.delete),
            getString(R.string.rules_delete_confirm, rule.text),
        )
        sheet.onConfirm = { vm.delete(rule) }
        sheet.show(childFragmentManager, ActionConfirmSheet.TAG)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * 规则列表适配器：正文（单行省略）+ 右侧启停开关。
 * 点按 = 编辑；长按 = 上移 / 下移 / 删除（行为在 Fragment 侧回调）。
 *
 * ⚠️ `Switch` 在 ViewHolder 复用下必须先摘监听再 `isChecked` 再挂回 —— 否则回收时
 *    程序化 `setChecked` 会触发上一条规则的回调，造成"滑动列表误改启停态"。
 */
private class RuleAdapter(
    val onEdit: (AiRuleEntity) -> Unit,
    val onToggle: (AiRuleEntity, Boolean) -> Unit,
    val onActions: (AiRuleEntity) -> Unit,
) : RecyclerView.Adapter<RuleAdapter.Holder>() {

    private var items: List<AiRuleEntity> = emptyList()

    fun submit(list: List<AiRuleEntity>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(val binding: RowRuleManageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = RowRuleManageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val rule = items[position]
        holder.binding.ruleText.text = rule.text
        // 先摘监听 → 回填 → 再挂回（防复用触发的写回环）。
        holder.binding.ruleSwitch.setOnCheckedChangeListener(null)
        holder.binding.ruleSwitch.isChecked = rule.enabled == 1
        holder.binding.ruleSwitch.setOnCheckedChangeListener { _, checked ->
            onToggle(rule, checked)
        }
        holder.binding.ruleText.setOnClickListener { onEdit(rule) }
        holder.binding.ruleText.setOnLongClickListener {
            onActions(rule)
            true
        }
    }
}
