package com.healix.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.ActivityPresetManageBinding
import com.healix.app.databinding.RowPresetManageBinding
import com.healix.app.db.PresetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 预设管理页（微扩展 C 闭环）：聊天页快捷条消费预设，这里管创建/编辑/删除。
 *
 * 事实基线：此前全 App 没有任何预设写入路径（快捷条 observeTop 永远为空），
 * 本页是唯一的创建入口 —— 新建只填名称 + kcal，foodsJson 留默认 "[]"，
 * 与 logPreset（ChatViewModel/MainViewModel）的取值完全兼容。
 *
 * 交互：点行 = 编辑（名称 + kcal 就地弹窗，SettingsActivity.showDialog 同款）；
 * 长按 = 删除（二次确认，删除不可恢复 —— 预设非事件数据，无软删链路）。
 * 列表走 Room Flow 响应式，写库后即时刷新，不需要手动 notify。
 */
internal class PresetManageActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var binding: ActivityPresetManageBinding
    private val adapter = PresetAdapter(
        onEdit = { showEditor(it) },
        onDelete = { confirmDelete(it) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPresetManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAdd.setOnClickListener { showEditor(null) }

        binding.presetList.layoutManager = LinearLayoutManager(this)
        binding.presetList.adapter = adapter

        // Room Flow 主线程 collect：与 MinePage.observeKnowledgeCount 同款（已验证可用）
        val db = HealixApp.from(this).database
        lifecycleScope.launch {
            db.presetDao().observeAll().collect { list ->
                adapter.submit(list)
                binding.emptyHint.visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * 编辑器弹窗（§5.1 统一）：preset == null 表示新建。走 [FieldSheet] 底色容器
     * 载体（取代系统 AlertDialog）。名称必填（空则不落库，直接关 —— 与
     * SettingsActivity.editApiKey「空输入不保存」同一口径）；kcal 可空，空/非法按 0 处理。
     */
    private fun showEditor(preset: PresetEntity?) {
        val specs = listOf(
            FieldSheet.FieldSpec(
                R.string.preset_field_name,
                preset?.name.orEmpty(),
                InputType.TYPE_CLASS_TEXT,
            ),
            FieldSheet.FieldSpec(
                R.string.preset_field_kcal,
                if (preset != null && preset.kcal > 0) preset.kcal.toString() else "",
                InputType.TYPE_CLASS_NUMBER,
            ),
        )
        val sheet = FieldSheet.newInstance(
            if (preset == null) R.string.preset_new_title else R.string.preset_edit_title,
            specs,
        )
        sheet.onResult = { raw ->
            val name = raw.getOrNull(0).orEmpty().trim()
            val kcal = raw.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            if (name.isNotEmpty()) save(preset, name, kcal)
        }
        sheet.show(supportFragmentManager, FieldSheet.TAG)
    }

    /** 落库（编辑复用 id 走 REPLACE；新建 id=0 自增）。useCount/lastUsed 保留原值。 */
    private fun save(preset: PresetEntity?, name: String, kcal: Int) {
        val db = HealixApp.from(this).database
        lifecycleScope.launch(Dispatchers.IO) {
            db.presetDao().upsert(
                PresetEntity(
                    id = preset?.id ?: 0,
                    name = name,
                    kcal = kcal,
                    useCount = preset?.useCount ?: 0,
                    lastUsedAt = preset?.lastUsedAt ?: 0,
                ),
            )
        }
    }

    /** 删除二次确认（§5.1 统一）：走 [ActionConfirmSheet] 底色容器（取代系统 AlertDialog）。 */
    private fun confirmDelete(preset: PresetEntity) {
        val sheet = ActionConfirmSheet.newInstance(
            getString(R.string.delete),
            getString(R.string.preset_delete_confirm, preset.name),
        )
        sheet.onConfirm = {
            val db = HealixApp.from(this).database
            lifecycleScope.launch(Dispatchers.IO) {
                db.presetDao().delete(preset.id)
            }
        }
        sheet.show(supportFragmentManager, ActionConfirmSheet.TAG)
    }

    /** v6 11.2：二级页返回走 in_back 转场（ResourceActivity 同款）。 */
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.in_back, R.anim.out_back)
    }
}

/**
 * 列表适配器：名称 + 副行（kcal / 使用次数，kcal=0 且未使用时隐藏副行）。
 * 点按 = 编辑，长按 = 删除确认（行为在 Activity 侧回调）。
 */
private class PresetAdapter(
    val onEdit: (PresetEntity) -> Unit,
    val onDelete: (PresetEntity) -> Unit,
) : RecyclerView.Adapter<PresetAdapter.Holder>() {

    private var items: List<PresetEntity> = emptyList()

    fun submit(list: List<PresetEntity>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(val binding: RowPresetManageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = RowPresetManageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val preset = items[position]
        holder.binding.name.text = preset.name
        holder.binding.sub.text = subText(holder.binding.sub, preset)
        holder.binding.sub.visibility =
            if (preset.kcal > 0 || preset.useCount > 0) View.VISIBLE else View.GONE
        holder.binding.root.setOnClickListener { onEdit(preset) }
        holder.binding.root.setOnLongClickListener {
            onDelete(preset)
            true
        }
    }

    /** 副行文案四态：kcal+次数 / 仅 kcal / 仅次数 / 隐藏（上面判空）。 */
    private fun subText(view: TextView, preset: PresetEntity): String = when {
        preset.kcal > 0 && preset.useCount > 0 ->
            view.context.getString(R.string.preset_sub_used_kcal, preset.kcal, preset.useCount)
        preset.kcal > 0 -> view.context.getString(R.string.preset_sub_kcal, preset.kcal)
        else -> view.context.getString(R.string.preset_sub_used, preset.useCount)
    }
}
