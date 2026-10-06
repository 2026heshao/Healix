package com.healix.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.healix.app.databinding.ItemRecordEmptyBinding
import com.healix.app.databinding.ItemRecordHeaderBinding

/**
 * 记录页滚动区两段轻适配器（v0.3 B2 / 需求 2：合并滚动区）。
 *
 * `binding.list` 的最终装配是
 * `ConcatAdapter(RecordHeaderAdapter, EventAdapter, RecordEmptyAdapter)`：
 * - 段 0 [RecordHeaderAdapter]：**恒 1 条**，承载 `item_record_header.xml`
 *   （目标区 + 预设横条 + 状态行 + 监督/计划提示条）。内容渲染由
 *   [RecordFragment] 经 `onHeaderBound` 回传的 binding 直接写入 ——
 *   `onBindViewHolder` 刻意为空（header 是"常驻视图"而非"数据条目"，
 *   复用时无需重绑）。
 * - 段 2 [RecordEmptyAdapter]：空态 footer（BP-1 裁定，取代原覆盖层），
 *   仅事件为空时 1 条。
 * - 段 1 [EventAdapter]：零改动（BP-3），ConcatAdapter 原样包裹。
 *
 * 坐标双空间约定：`bindingAdapterPosition`（子适配器坐标）与根坐标不同 ——
 * `smoothScrollToPosition` 这类根坐标调用须 `+ HEADER_ITEM_COUNT`。
 */
internal class RecordHeaderAdapter(
    private val onHeaderBound: (ItemRecordHeaderBinding) -> Unit,
) : RecyclerView.Adapter<RecordHeaderAdapter.VH>() {

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemRecordHeaderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        // 首次（也是唯一一次）创建时回传 binding：Fragment 存引用并补渲染 +
        // 挂点击监听（flows 可能先于本次回调到达，见 RecordFragment 补渲染注释）。
        b.also(onHeaderBound)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = Unit

    class VH(val b: ItemRecordHeaderBinding) : RecyclerView.ViewHolder(b.root)
}

/**
 * 空态 footer 适配器（BP-1：取代原 `emptyState` 覆盖层）。
 *
 * [empty] 初始为 **false**（对齐原覆盖层 XML 初始 `visibility="gone"`：
 * 首个事件 Flow 发射前不显示，避免"暂无记录"在首帧闪现）；
 * 由 `setEmpty(events.isEmpty())` 驱动。
 */
internal class RecordEmptyAdapter : RecyclerView.Adapter<RecordEmptyAdapter.VH>() {

    private var empty = false

    fun setEmpty(v: Boolean) {
        if (v == empty) return
        empty = v
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = if (empty) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemRecordEmptyBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = Unit

    class VH(val b: ItemRecordEmptyBinding) : RecyclerView.ViewHolder(b.root)
}
