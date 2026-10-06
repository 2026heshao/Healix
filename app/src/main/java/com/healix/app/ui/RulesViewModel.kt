package com.healix.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healix.app.HealixApp
import com.healix.app.db.AiRuleEntity
import com.healix.app.db.RULE_TEXT_MAX_LEN
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 规则库页 ViewModel（v0.3 B4）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 职责
 * ══════════════════════════════════════════════════════════════════════════
 * - [rules]：`observeAll()` 的响应式列表（Room `Flow` → `StateFlow`），页面直接订阅，
 *   任何写库后即时刷新，**不需要手动 notify**；
 * - 增 / 改 / 启停 / 删 / 排序：全部经 `viewModelScope + Dispatchers.IO` 写库
 *   （不阻塞主线程，与 `PresetManageFragment` 同一范式）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么排序也放在这里
 * ══════════════════════════════════════════════════════════════════════════
 * `sortOrder` 是**表内相对序**，交换必须读全量再写两行 —— 这是数据不变量，
 * 不该散在 Fragment 里。UI 只表达"上移 / 下移"意图，具体交换由 [move] 落库。
 *
 * ⚠️ 长度上限引用共享常量 [RULE_TEXT_MAX_LEN]（写侧唯一来源）；落库前
 *    `trim + take` —— **空白规则不落库**（与设置页「空输入不保存」同一口径）。
 */
internal class RulesViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = HealixApp.from(app).database.aiRuleDao()

    /** 全部规则（含停用），按 `sort_order` 升序 —— 与页面展示顺序一致。 */
    val rules: StateFlow<List<AiRuleEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 新增一条规则（默认启用，排在队尾）。空白输入不落库。 */
    fun add(text: String) {
        val clean = text.trim().take(RULE_TEXT_MAX_LEN)
        if (clean.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            // 队尾：现有最大 sort_order + 1（空表取 0）。
            val next = (dao.listAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
            dao.upsert(
                AiRuleEntity(
                    id = 0,
                    text = clean,
                    enabled = 1,
                    sortOrder = next,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** 编辑一条规则的文本（保留启用态与顺序）。空白输入不落库。 */
    fun update(rule: AiRuleEntity, text: String) {
        val clean = text.trim().take(RULE_TEXT_MAX_LEN)
        if (clean.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsert(rule.copy(text = clean))
        }
    }

    /** 启停一条规则（1 = 生效 / 0 = 停用）。停用 ≠ 删除，重新启用即恢复。 */
    fun setEnabled(rule: AiRuleEntity, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.setEnabled(rule.id, if (enabled) 1 else 0)
        }
    }

    /** 删除一条规则（物理删除 —— 规则非事件数据，无软删链路）。 */
    fun delete(rule: AiRuleEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.delete(rule.id)
        }
    }

    /**
     * 上移 / 下移一条规则：与相邻规则**交换 `sort_order`**。
     *
     * 已在首 / 末位（无可交换邻居）时静默不动。两行 `sortOrder` 恰好相等（历史脏数据）
     * 时退化为"用目标位置序号拉开"，保证交换后顺序确定。
     */
    fun move(rule: AiRuleEntity, up: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = dao.listAll()
            val idx = all.indexOfFirst { it.id == rule.id }
            if (idx < 0) return@launch
            val swapIdx = if (up) idx - 1 else idx + 1
            if (swapIdx < 0 || swapIdx >= all.size) return@launch
            val a = all[idx]
            val b = all[swapIdx]
            val ao = a.sortOrder
            val bo = b.sortOrder
            val equal = ao == bo
            dao.upsert(a.copy(sortOrder = if (equal) swapIdx else bo))
            dao.upsert(b.copy(sortOrder = if (equal) idx else ao))
        }
    }
}
