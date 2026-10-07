package com.healix.app.ui

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.healix.app.R
import com.healix.app.databinding.ItemEventBinding
import com.healix.app.databinding.ItemRecordEmptyBinding
import com.healix.app.databinding.ItemRecordHeaderBinding
import com.healix.app.db.EventEntity
import com.healix.app.notify.EventText

/**
 * 记录页滚动区三段轻适配器（v0.3 B2 合并滚动区；v6 记录列表分组卡）。
 *
 * `binding.list` 的最终装配是
 * `ConcatAdapter(RecordHeaderAdapter, EventAdapter, RecordEmptyAdapter)`：
 * - 段 0 [RecordHeaderAdapter]：**恒 1 条**，承载 `item_record_header.xml`
 *   （v6：Filter chips + Hero 卡 + 指标 chip 行 + 主目标行 + 计划/监督提示条）。
 *   内容渲染由 [RecordFragment] 经 `onHeaderBound` 回传的 binding 直接写入 ——
 *   `onBindViewHolder` 刻意为空（header 是"常驻视图"而非"数据条目"）。
 * - 段 1 [EventAdapter]：事件行，v6 按同天同类型相邻聚成一张分组卡。
 * - 段 2 [RecordEmptyAdapter]：空态 footer（BP-1 裁定），仅事件为空时 1 条。
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

/**
 * 记录事件行适配器（组件规范 3.3 + 11.3 左滑）。
 *
 * v6 分组卡：`events` 为**今日**记录（`MainViewModel.events` 按 day_key 过滤，
 * 已按 ts DESC），把「同天 + 同类型」的相邻行聚进一张 surface 卡 ——
 * 由 position 判定该行在组内的位置，据此设置卡片背景
 * （solo / top / middle / bottom），并决定卡内分隔线是否显示。
 * 不同分组之间留 [R.dimen.rec_card_gap] 间距。
 *
 * 行内规格与「识别中 / 未识别 · 点此补充」两态**原样保留**（直写路径零弹窗）。
 *
 * v8：由 `MainActivity.kt` 迁入本文件（只服务记录页）。
 * v6：分组卡逻辑随之落在本文件（`RecordFragment` 不再承载适配器实现）。
 */
class EventAdapter(
    private val onEdit: (EventEntity) -> Unit,
    private val onRetry: (EventEntity) -> Unit,
    private val onDelete: (EventEntity) -> Unit,
    private val swipe: SwipeController,
) : RecyclerView.Adapter<EventAdapter.VH>() {

    private var items: List<EventEntity> = emptyList()

    /** 分组卡左右外边距（screen_margin）与组间间距（rec_card_gap），首个 VH 创建时缓存。 */
    private var sideMargin = 0
    private var cardGap = 0

    /**
     * 隐私：隐藏热量数字（规范 §9.7 ④）。为 true 时 meal / exercise 的摘要
     * 不再显示 kcal，改为显示用户自己填的数量文本；没有数量就整行隐藏。
     */
    var hideKcal: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    fun submit(list: List<EventEntity>) {
        items = list
        notifyItemRangeChanged(0, items.size)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        if (sideMargin == 0) {
            val res = parent.resources
            sideMargin = res.getDimensionPixelSize(R.dimen.screen_margin)
            cardGap = res.getDimensionPixelSize(R.dimen.rec_card_gap)
        }
        val b = ItemEventBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(items[position], position)

    /** 同组判据：同天（dayKey）+ 同类型。 */
    private fun sameGroup(a: EventEntity, b: EventEntity): Boolean =
        a.dayKey == b.dayKey && a.type == b.type

    inner class VH(private val b: ItemEventBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(e: EventEntity, position: Int) {
            val ctx = b.root.context

            // ── v6 分组卡几何：按 position 判定组内位置，设卡片背景 + 外边距 + 分隔线 ──
            val isStart = position == 0 || !sameGroup(items[position - 1], e)
            val isEnd = position == items.size - 1 || !sameGroup(items[position + 1], e)
            val cardBg = when {
                isStart && isEnd -> R.drawable.bg_card_solo
                isStart -> R.drawable.bg_card_top
                isEnd -> R.drawable.bg_card_bottom
                else -> R.drawable.bg_card_middle
            }
            b.eventRoot.setBackgroundResource(cardBg)
            val lp = b.eventRoot.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.marginStart = sideMargin
                lp.marginEnd = sideMargin
                lp.bottomMargin = if (isEnd) cardGap else 0
                b.eventRoot.layoutParams = lp
            }
            // 卡内分隔线只在组内相邻行之间显示（组末行不画）
            b.divider.visibility = if (isEnd) View.GONE else View.VISIBLE

            // ── 复用防残留：滑开态 ViewHolder 被复用到新 item 时，swipeItem
            //    可能带着上一次的 -144dp 平移。bind 前先取消残留动画、归位平移，
            //    并解除 SwipeController 对这个视图的滑开跟踪。──
            b.swipeItem.animate().cancel()
            b.swipeItem.translationX = 0f
            swipe.release(b.swipeItem)

            // ── v6 左滑：拖拽跟随 + 按压缩放，同一个触摸监听承载两种反馈 ──
            b.swipeItem.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE).setDuration(120).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                swipe.onTouch(v, ev)
                false // 不消费：点击 / 长按照旧
            }
            b.actEdit.setOnClickListener {
                if (!swipe.clickAllowed()) return@setOnClickListener
                swipe.closeAll()
                onEdit(e)
            }
            b.actDelete.setOnClickListener {
                if (!swipe.clickAllowed()) return@setOnClickListener
                swipe.closeAll()
                onDelete(e)
            }

            // 类型 + 时间（第一行，13sp text_2）
            b.typeLabel.text = EventText.typeName(ctx, e.type)
            b.timeLabel.text = HealixDate.timeLabel(e.ts)

            // 6px 圆点按类型着色
            b.dot.background.setTint(EventText.typeColor(ctx, e.type))

            // 正文：raw_text（15sp text_1，最多 2 行）
            b.bodyText.text = e.rawText

            // 摘要：按类型口径，无信息则隐藏（不留空行）
            val summary = EventText.summary(ctx, e, hideKcal)
            b.summaryText.text = summary
            b.summaryText.visibility = if (summary.isNullOrEmpty()) View.GONE else View.VISIBLE

            // 三态：pending 显示"识别中"，failed 显示「未识别 · 点此补充」
            when (e.parseStatus) {
                PARSE_PENDING -> {
                    // 「识别中」13sp text_3、**不可点**、整行仍可点进编辑（规范 §3.3）
                    b.pendingText.visibility = View.VISIBLE
                    b.errorText.visibility = View.GONE
                }
                PARSE_FAILED -> {
                    b.pendingText.visibility = View.GONE
                    b.errorText.visibility = View.VISIBLE

                    // ⚠️ 失败 ≠ 错误（PRD §15.5 / 规范 §3.10）：
                    //   原文已落库、day_key 已算对 → "这条还没算完"，不是数据丢了。
                    //   error **只留给"数据真的可能丢"**：DB 写入 / 更新失败。
                    val dataLoss = e.lastError?.let {
                        it.startsWith("db_insert_failed") || it.startsWith("db_update_failed")
                    } == true

                    if (dataLoss) {
                        b.errorText.setText(R.string.state_save_failed)
                        b.errorText.setTextColor(ContextCompat.getColor(ctx, R.color.error))
                        b.errorText.setOnClickListener { onRetry(e) }
                    } else {
                        b.errorText.setText(R.string.state_unrecognized)
                        b.errorText.setTextColor(ContextCompat.getColor(ctx, R.color.text_2))
                        // 「补充」= 进编辑弹窗，交给整行的 onEdit 处理
                        b.errorText.setOnClickListener(null)
                        b.errorText.isClickable = false
                    }
                }
                else -> {
                    b.pendingText.visibility = View.GONE
                    b.errorText.visibility = View.GONE
                }
            }

            // 整行可点 → 编辑（复用 ConfirmSheet）。刚拖完的 300ms 内不触发（V4）。
            b.swipeItem.setOnClickListener {
                if (!swipe.clickAllowed()) return@setOnClickListener
                onEdit(e)
            }
        }
    }

    private companion object {
        const val PARSE_PENDING = "pending"
        const val PARSE_FAILED = "failed"

        /** 按压缩放幅度（规范 11.4，原型 scale .985）。 */
        const val PRESS_SCALE = 0.985f
    }
}
