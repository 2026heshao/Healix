package com.healix.app.rules

import android.content.Context
import com.healix.app.R
import com.healix.app.db.EventEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 就医准备材料（v8 需求 9 功能 3；借鉴 Bearable）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 它解决什么
 * ══════════════════════════════════════════════════════════════════════════
 * 用户报告里价值最高的一件事是「自上次就诊以来发生了什么」（旧研究 2.2）——
 * 而 Healix 已经有全部事实（`events` 的症状 / 体重 / 睡眠 / 饮食 / 运动），
 * 却**只有原始 JSON 导出**（`ExportWriter`）。本类把一段时间窗里的事实整理成
 * **一页能读、能打印、能交给医生**的纯文本。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 边界纪律（旧研究 R6 / PRD §5.7）
 * ══════════════════════════════════════════════════════════════════════════
 * **只汇总事实，不做任何医学判断**：不输出诊断、不输出风险百分比、不宣称因果、
 * 不给建议。每一行都能回到 `events` 里逐条核对。结尾必须带免责声明。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么**不用 AI**（与调研文档的一处刻意取舍）
 * ══════════════════════════════════════════════════════════════════════════
 * 调研把 AI 列为"可选（有则措辞更顺，无则本地模板）"。这里**只做本地模板**：
 * ① 摘要的价值在"事实齐全、可核对"，措辞顺不顺是次要的；
 * ② 走一次 AI = 一行 `llm_calls` = 消耗当日抽取配额，而这是用户**随时可能点**的
 *    入口（不是低频后台任务）；
 * ③ 新增一个 prompt 会动到字节冻结的 `PROMPT_*` 契约链（见项目硬约束）。
 * 所以离线路径是**唯一**路径，而不是降级路径 —— PRD 验收项"离线 + 未配置 provider
 * 下走本地降级"因此天然成立。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 结构：[summarize] 纯计算 / [render] 贴文案
 * ══════════════════════════════════════════════════════════════════════════
 * *汇总口径*（统计数字）与 *文案* 分开：[summarize] 无 Context 无 IO，可在
 * `testDebugUnitTest` 里逐条断言；[render] 只是把数字贴进 `strings.xml` 模板。
 */
object MedicalSummary {

    // event.type 取值（**不复用**别处的常量名 —— `check_duplicate_constants`
    // 判据是「同名且同值」，`TYPE_EXERCISE` / `EVENT_MEAL` 等已被占用）
    private const val EV_ILLNESS = "illness"
    private const val EV_BODY = "body"
    private const val EV_SLEEP = "sleep"
    private const val EV_MEAL = "meal"
    private const val EV_EXERCISE = "exercise"

    /** 单行事实文本的上限：症状是自由文本，几百字的一行会把整页挤垮。 */
    private const val MAX_ROW_TEXT = 60

    /** 数值展示保留 1 位小数（体重 / 睡眠 / 日均热量都用它）。 */
    private const val DECIMALS = 10.0

    /** 症状时间线的一行。 */
    data class Row(val day: String, val text: String)

    /** 「N 次记录 + 区间 + 平均 + 最新值」形态（体重 / 睡眠共用）。 */
    data class Series(
        val count: Int,
        val min: Double,
        val max: Double,
        val avg: Double,
        val latest: Double,
        val latestDay: String,
    )

    /** 「N 条记录 + 热量合计」形态（饮食 / 运动共用）。 */
    data class Kcal(val count: Int, val days: Int, val totalKcal: Int)

    /**
     * 一页摘要的结构化内容（**纯数据**）。
     * `null` 表示"该时段内没有这一类记录" → 渲染时整段省略。
     */
    data class Doc(
        val from: String,
        val to: String,
        val illness: List<Row>,
        val weight: Series?,
        val sleep: Series?,
        val meal: Kcal?,
        val exercise: Kcal?,
    ) {
        /** 时间窗内一条记录都没有 → 只渲染标题 + 空态文案。 */
        val isEmpty: Boolean
            get() = illness.isEmpty() && weight == null && sleep == null &&
                meal == null && exercise == null
    }

    /**
     * 纯计算：把 [events] 里落在 `[from, to]`（含两端，按 `day_key` 比较）的记录
     * 汇总成 [Doc]。
     *
     * 本函数**不依赖查询**：`deleted_at` 与日区间都在这里再过滤一次（`EventDao.listInRange`
     * 已经过滤过），这样它可以用任意输入直接单测，也避免"换个查询就悄悄改了口径"。
     *
     * ⚠️ 体重 / 睡眠只收 `> 0` 的行：0 是兜底值不是真实测量（与 `EventDao.weightRowsInRange`
     * 同口径），把 0 算进 min 会让"最低体重 0 kg"这种明显错误出现在给医生看的材料上。
     */
    fun summarize(events: List<EventEntity>, from: String, to: String): Doc {
        val inRange = events.filter {
            it.deletedAt == null && it.dayKey >= from && it.dayKey <= to
        }

        // 症状时间线：**按时间升序**（时间线口径，医生顺着读）
        val illness = inRange
            .filter { it.type == EV_ILLNESS }
            .sortedWith(compareBy({ it.dayKey }, { it.ts }))
            .map { Row(day = it.dayKey, text = oneLine(it.symptom.ifBlank { it.rawText })) }

        val weight = seriesOf(inRange.filter { it.type == EV_BODY }, { it.weightKg })
        val sleep = seriesOf(inRange.filter { it.type == EV_SLEEP }, { it.sleepH })
        val meal = kcalOf(inRange.filter { it.type == EV_MEAL })
        val exercise = kcalOf(inRange.filter { it.type == EV_EXERCISE })

        return Doc(from, to, illness, weight, sleep, meal, exercise)
    }

    /** 「N 次 + 区间 + 平均 + 最新」；一条有效记录都没有 → null。 */
    private fun seriesOf(rows: List<EventEntity>, value: (EventEntity) -> Double): Series? {
        val valid = rows.filter { value(it) > 0.0 }.sortedWith(compareBy({ it.dayKey }, { it.ts }))
        if (valid.isEmpty()) return null
        val values = valid.map(value)
        val last = valid.last()
        return Series(
            count = valid.size,
            // `minOrNull` / `maxOrNull`：`min()` / `max()` 在 Kotlin 1.4 起已废弃
            // （集合为空时抛异常），本项目一律用 OrNull 版（见 StatusDetailFragment）。
            min = values.minOrNull() ?: return null,
            max = values.maxOrNull() ?: return null,
            avg = values.sum() / values.size,
            latest = value(last),
            latestDay = last.dayKey,
        )
    }

    /** 「N 条 + N 天有记录 + 合计热量」。 */
    private fun kcalOf(rows: List<EventEntity>): Kcal? {
        if (rows.isEmpty()) return null
        return Kcal(
            count = rows.size,
            days = rows.map { it.dayKey }.distinct().size,
            totalKcal = rows.sumOf { it.kcal },
        )
    }

    /** 折叠空白 + 截断：症状是自由文本，可能带换行（会破坏"一行一条"的对齐）。 */
    private fun oneLine(raw: String): String {
        val flat = raw.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= MAX_ROW_TEXT) flat else flat.take(MAX_ROW_TEXT) + "…"
    }

    /** 贴文案：[Doc] → 可直接保存为 `.txt` 的纯文本。 */
    fun render(ctx: Context, doc: Doc, now: Long): String {
        val sb = StringBuilder()
        sb.appendLine(ctx.getString(R.string.medical_doc_title))
        sb.appendLine(ctx.getString(R.string.medical_doc_range, doc.from, doc.to))
        sb.appendLine(ctx.getString(R.string.medical_doc_generated, stamp(now)))
        sb.appendLine()

        if (doc.isEmpty) {
            sb.appendLine(ctx.getString(R.string.medical_preview_empty))
            sb.appendLine()
        }

        if (doc.illness.isNotEmpty()) {
            sb.appendLine(ctx.getString(R.string.medical_sec_illness, doc.illness.size))
            for (row in doc.illness) {
                sb.appendLine(ctx.getString(R.string.medical_row, row.day, row.text))
            }
            sb.appendLine()
        }

        doc.weight?.let { s ->
            sb.appendLine(
                ctx.getString(
                    R.string.medical_sec_weight,
                    s.count, num(s.min), num(s.max), num(s.avg), num(s.latest), s.latestDay,
                ),
            )
            sb.appendLine()
        }

        doc.sleep?.let { s ->
            sb.appendLine(
                ctx.getString(
                    R.string.medical_sec_sleep,
                    s.count, num(s.avg), num(s.min), num(s.max),
                ),
            )
            sb.appendLine()
        }

        doc.meal?.let { k ->
            val avg = if (k.days > 0) k.totalKcal.toDouble() / k.days else 0.0
            sb.appendLine(
                ctx.getString(R.string.medical_sec_meal, k.count, k.days, k.totalKcal, num(avg)),
            )
            sb.appendLine()
        }

        doc.exercise?.let { k ->
            sb.appendLine(ctx.getString(R.string.medical_sec_exercise, k.count, k.totalKcal))
            sb.appendLine()
        }

        sb.append(ctx.getString(R.string.medical_footnote))
        return sb.toString()
    }

    /** 保留 1 位小数；整数不拖 `.0`。 */
    private fun num(v: Double): String {
        val rounded = (v * DECIMALS).roundToInt() / DECIMALS
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun stamp(now: Long): String =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).format(STAMP)
}
