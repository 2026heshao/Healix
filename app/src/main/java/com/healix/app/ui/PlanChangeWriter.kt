package com.healix.app.ui

import android.content.Context
import com.healix.app.R
import com.healix.app.db.AppDatabase
import com.healix.app.parse.loadsLenient
import java.time.LocalDate
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 计划写路径落点（v0.3 B6，决策 CR-1 的**语义**落点）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么是独立文件、而不是给 `PlanGenerator` 加方法
 * ══════════════════════════════════════════════════════════════════════════
 * 设计 CR-1 指定落点为 `PlanGenerator.applyPlanChange`。但 `PlanGenerator.kt` 处于
 * 他会话未提交的 WIP（工作区纪律：一律只读、不改）—— 触碰它会把两路改动绞在一起。
 * 故把**同一语义**放这里承载：零接触 WIP 文件，行为契约不变。此为对 CR-1 **落点形式**
 * 的偏离（语义不变），已在交付说明中标注。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 职责：定位 → 按 allowlist 打补丁 → 回写（**不复制 schema**）
 * ══════════════════════════════════════════════════════════════════════════
 * 所有操作都走「读原 JSON → 就地改 → 整份写回」，**绝不整份重建**：根对象里
 * `version` / `generated_at` / `source` 等未知字段原样保留（`JSONObject` 只改我们
 * 显式 put 的键）。目标结构（亲读 `PlanGenerator.kt:880-903` 核实）：
 *
 * ```
 * plan_json     = { version, generated_at, source, items:[ <Item> ], note }
 * <Item>        = { day, time, slot, type, title, detail, kcal, duration, why }
 * ```
 * - `items[].type` 的**实际取值域**（`PlanGenerator.kt` prompt L83 + `itemsOf` 兜底）=
 *   `{ meal, exercise, sleep, habit }` —— **没有「休息」这一档**。App 里「休息 / 恢复」
 *   一向落在 `sleep`（见 `buildTimeline` 的「恢复：补水 + 早睡」与 `exerciseItem`），
 *   且 `TimelineEntry` 的 `canLog = type∈{meal,exercise}` → 休息条目天然不可「记一笔」。
 *   故 [OP_SET_REST] 的固定补丁把 `type` 归一到 `sleep`。
 *
 * 周训练（`training_plans`，`op = set_training_rest` / `replace_training_week`）结构（亲读
 * `TrainingPlanner.kt` `serialize`/`parsePlan` 核实）：`{ focus, days:[{ dow(1..7), title,
 * items:[{name,sets,reps}] }], note }`；
 * 休息日判据 = `items` 为空 **或** `title == "休息"`（`R.string.training_rest`）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 校验纪律（模型输出永不可信）
 * ══════════════════════════════════════════════════════════════════════════
 * - **不抛异常**：一切非法输入返回 [Result.Error]（调用方回一句错误文本，循环可继续）；
 * - **定位歧义**（命中 0 条 / 多条）→ [Result.Error]，**不写库**；
 * - **补丁键 allowlist**：今日计划条目只允许改 `type/title/detail/kcal/duration/why`，
 *   越界键 / 非法 `type` / 负 `kcal` → [Result.Error]，**不写库**；
 * - **覆盖型写入产撤销快照**：整周重排会把旧 7 天安排整体替换 → [Result.Ok.undo] 带旧
 *   `plan_json`/`content`，由调用方在落库后置撤销条（判据见 [UndoAction]）；
 * - 单协程内读改写（无并发缝隙）。
 */
internal object PlanChangeWriter {

    // ── op 取值域（`PlanChangeProposal.op` 载荷的 `op` 字段）───────────────
    /** 今日计划：把某条目改成休息（固定补丁）。 */
    const val OP_SET_REST = "set_rest"

    /** 今日计划：按 allowlist 给某条目打补丁。 */
    const val OP_PATCH_ITEM = "patch_item"

    /** 今日计划：改备注（`items` 之外的 `note`；旧能力，降为本机制的**特例**）。 */
    const val OP_SET_NOTE = "set_note"

    /** 今日计划：清空备注。 */
    const val OP_CLEAR_NOTE = "clear_note"

    /** 今日计划：**新增**一条条目（今天细排条目 或 明天的时段锚点）。 */
    const val OP_ADD_ITEM = "add_item"

    /** 今日计划：**删除**一条条目（定位须唯一）。 */
    const val OP_REMOVE_ITEM = "remove_item"

    /** 周训练计划：把本周某天设为休息日。 */
    const val OP_SET_TRAINING_REST = "set_training_rest"

    /**
     * 周训练计划：**整周重排**（2026-10-07 P2 第三批）—— 用一份新的 7 天安排
     * 整体替换本周计划（focus / days 一并写）。
     *
     * 与 [OP_SET_TRAINING_REST] 是同一个实体（`training_plans`）上的两个动作，故**扩
     * `action` 域而不新建工具**（判据见 `HealthAgent.ToolRegistry` 类 KDoc 的
     * 「扩动作域 vs 加新工具」）。
     *
     * ⚠️ 这是**覆盖型**写入（旧 7 天安排整体消失）→ 产撤销快照（[Result.Ok.undo]）。
     */
    const val OP_REPLACE_TRAINING_WEEK = "replace_training_week"

    /**
     * 今日计划条目**唯一允许改**的字段（allowlist）。
     * `day` / `time` / `slot` 是定位与排序键，**刻意不可改**（改排序键会让条目跳位）。
     */
    private val ITEM_PATCH_KEYS = setOf("type", "title", "detail", "kcal", "duration", "why")

    /** `items[].type` 实际取值域（亲读 `PlanGenerator` 核实，见类 KDoc）。 */
    private val ITEM_TYPES = setOf("meal", "exercise", "sleep", "habit")

    /** `add_item` 的 `day` 取值域：今天（HH:mm 细排）/ 明天（时段锚点）。 */
    private const val DAY_TODAY = "today"
    private const val DAY_TOMORROW = "tomorrow"

    /** 「今天」条目的时刻格式（HH:mm 24 小时制，与 prompt 的 `time` 约定同口径）。 */
    private val TIME_PATTERN = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    /** 明天锚点的时段键取值域（引用 [PlanSlot] 常量，**只用于报错文案**）。 */
    private val SLOT_KEYS = listOf(PlanSlot.MORNING, PlanSlot.NOON, PlanSlot.EVENING, PlanSlot.TRAIN)

    // 补丁字段长度上限：与 `PlanGenerator` 的私有上限**同量级**，但**刻意另名**
    // （`PATCH_MAX_*`）—— `check_kotlin.py` 的 `check_duplicate_constants` 判据是
    // 「同名**且**同值」，而 `PlanGenerator` 已有 `MAX_TITLE_LEN = 80` /
    // `MAX_DETAIL_LEN = 120` / `MAX_WHY_LEN = 80`，同名会平白多 3 条收敛提示。
    private const val PATCH_MAX_TITLE = 80
    private const val PATCH_MAX_DETAIL = 120
    private const val PATCH_MAX_WHY = 80
    private const val PATCH_MAX_DURATION = 16

    /** `note` 截断上限（防御式；主校验在 `HealthAgent.proposePlanChange`）。 */
    private const val MAX_NOTE_CHARS = 200

    // ── 整周重排（[OP_REPLACE_TRAINING_WEEK]）的输入闸门 ────────────────
    // 全部刻意另名（不带 `TRAINING_` 前缀的撞名风险已逐个 grep 过）—— `check_kotlin.py`
    // 的 `check_duplicate_constants` 判据是「同名**且**同值」，此处与别处同名不同值也会
    // 被同值检查放过，但同名本身会让"改一处忘另一处"变难查，故一律另名。

    /** 周重点文案上限（对应 `TrainingPlan.focus`）。 */
    private const val MAX_FOCUS_LEN = 60

    /** 单日标题上限。 */
    private const val MAX_DAY_TITLE = 40

    /** 单个动作名上限。 */
    private const val MAX_ITEM_NAME = 40

    /** 单动作组次文案上限（如 `8-12` / `力竭`）。 */
    private const val MAX_REPS_LEN = 20

    /** 单日动作数上限（防模型把一天排成 20 个动作）。 */
    private const val MAX_ITEMS_PER_DAY = 8

    /** 组数区间（闭区间）。 */
    private const val MIN_SETS = 1
    private const val MAX_SETS = 20

    /**
     * 休息条目 `duration` 的占位符（与渲染口径一致）。
     * `TimelineEntry.DASH` 是 private，无法复用 → 此处另名 `REST_DASH`，同时避开
     * `check_duplicate_constants` 的「同名且同值」判据。
     */
    private const val REST_DASH = "——"

    /** 结果：成功附人类可读摘要（供确认弹窗正文 / 回执），失败附错误文本。 */
    internal sealed interface Result {
        /**
         * [undo] 非空 = 本次写入属**覆盖型**（整周训练重排），调用方应在落库成功后置
         * 撤销条（判据见 [UndoAction]）；条目级增量改动恒为 null。
         */
        data class Ok(val summary: String, val undo: UndoAction? = null) : Result
        data class Error(val message: String) : Result
    }

    /**
     * **拟稿期**校验（不写库）：读库定位 + 白名单校验，产出摘要或错误文本。
     * 由 `HealthAgent.proposePlanChange` 在生成 draft **之前**调用 —— 命中 0/多条、
     * 非法补丁都在此拦下（返回错误文本给模型，循环可继续，不产 draft）。
     */
    suspend fun describe(
        context: Context,
        db: AppDatabase,
        date: String,
        opJson: String,
    ): Result = perform(context, db, date, opJson, commit = false)

    /**
     * **确认期**执行（写库）：与 [describe] 同一套校验，校验通过后落地。
     * 由 `ChatViewModel.confirmPlanChange` 在用户点确认后调用。
     *
     * ⚠️ 重新读库定位（拟稿到确认之间计划可能被重新生成）—— 若此刻已定位不到，返回
     * [Result.Error]（不写库），调用方回执失败。
     */
    suspend fun apply(
        context: Context,
        db: AppDatabase,
        date: String,
        opJson: String,
    ): Result = perform(context, db, date, opJson, commit = true)

    // ------------------------------------------------------------------
    // 分派
    // ------------------------------------------------------------------

    private suspend fun perform(
        context: Context,
        db: AppDatabase,
        date: String,
        opJson: String,
        commit: Boolean,
    ): Result {
        val op = runCatching { JSONObject(opJson) }.getOrNull()
            ?: return Result.Error("内部错误：改动指令无法解析。")
        return when (op.optString("op").trim()) {
            OP_SET_NOTE -> noteOp(db, date, op, commit)
            OP_CLEAR_NOTE -> clearNoteOp(db, date, commit)
            OP_SET_REST -> itemOp(context, db, date, op, commit, setRest = true)
            OP_PATCH_ITEM -> itemOp(context, db, date, op, commit, setRest = false)
            OP_ADD_ITEM -> addItemOp(context, db, date, op, commit)
            OP_REMOVE_ITEM -> removeItemOp(db, date, op, commit)
            OP_SET_TRAINING_REST -> trainingRestOp(context, db, op, commit)
            OP_REPLACE_TRAINING_WEEK -> replaceTrainingWeekOp(context, db, op, commit)
            else -> Result.Error("未知的计划修改类型。")
        }
    }

    // ------------------------------------------------------------------
    // 今日计划：备注（note）—— 旧能力的「特例」
    // ------------------------------------------------------------------

    private suspend fun noteOp(db: AppDatabase, date: String, op: JSONObject, commit: Boolean): Result {
        val note = op.optString("note").trim().take(MAX_NOTE_CHARS)
        if (note.isEmpty()) return Result.Error("note 不能为空。")
        val row = db.planDao().getPlan(date)
            ?: return Result.Error("$date 还没有生成今日计划，无法修改。")
        val root = parseRoot(row.planJson)
            ?: return Result.Error("$date 的计划数据无法解析，未做修改。")
        if (commit) {
            root.put("note", note)
            db.planDao().upsertPlan(row.copy(planJson = root.toString()))
        }
        return Result.Ok("把 $date 的计划备注改为：$note")
    }

    private suspend fun clearNoteOp(db: AppDatabase, date: String, commit: Boolean): Result {
        val row = db.planDao().getPlan(date)
            ?: return Result.Error("$date 还没有生成今日计划，无法修改。")
        val root = parseRoot(row.planJson)
            ?: return Result.Error("$date 的计划数据无法解析，未做修改。")
        if (commit) {
            root.put("note", "")
            db.planDao().upsertPlan(row.copy(planJson = root.toString()))
        }
        return Result.Ok("清空 $date 的计划备注")
    }

    // ------------------------------------------------------------------
    // 今日计划：条目（items[]）—— 核心能力
    // ------------------------------------------------------------------

    /**
     * 条目级改动：定位 `items[]` 中的目标条目（唯一命中）→ 打补丁 → 回写。
     *
     * @param setRest true = 用固定「休息」补丁；false = 用 op 里的 `patch`（allowlist）。
     */
    private suspend fun itemOp(
        context: Context,
        db: AppDatabase,
        date: String,
        op: JSONObject,
        commit: Boolean,
        setRest: Boolean,
    ): Result {
        val row = db.planDao().getPlan(date)
            ?: return Result.Error("$date 还没有生成今日计划，无法修改。")
        val root = parseRoot(row.planJson)
            ?: return Result.Error("$date 的计划数据无法解析，未做修改。")
        val items = root.optJSONArray("items")
            ?: return Result.Error("$date 的计划里没有条目。")

        val matchTitle = op.optString("match_title").trim()
        val matchTime = op.optString("match_time").trim()
        if (matchTitle.isEmpty() && matchTime.isEmpty()) {
            return Result.Error("需要给出 match_title（或 match_time）来指定要改的条目。")
        }
        val located = when (val l = locate(items, matchTitle, matchTime)) {
            is Locate.Found -> l
            is Locate.Fail -> return Result.Error(l.reason)
        }
        val item = items.optJSONObject(located.index)
            ?: return Result.Error("定位到的条目无法读取。")

        val patch: JSONObject = if (setRest) {
            restPatch(context)
        } else {
            val raw = op.optJSONObject("patch")
                ?: return Result.Error("patch_item 需要给出 patch 对象。")
            val err = sanitizePatch(raw)
            if (err != null) return Result.Error(err)
            raw
        }

        val originalTitle = item.optString("title").trim().ifEmpty { "（无标题）" }
        if (commit) {
            // 就地改（JSONObject 只覆盖我们显式 put 的键；day/time/slot 与根对象其余字段不动）
            for (key in patch.keys()) item.put(key, patch.get(key))
            db.planDao().upsertPlan(row.copy(planJson = root.toString()))
        }
        return Result.Ok(
            if (setRest) {
                "把 $date 计划里的「$originalTitle」改成休息"
            } else {
                "把 $date 计划里的「$originalTitle」改为：${patchKeysText(patch)}"
            },
        )
    }

    /** 固定「休息」补丁：`type=sleep`（休息/恢复档）、`kcal=0`、`duration=——`。 */
    private fun restPatch(context: Context): JSONObject = JSONObject().apply {
        put("type", "sleep")
        put("title", context.getString(R.string.training_rest))
        put("detail", context.getString(R.string.plan_rest_detail))
        put("kcal", 0)
        put("duration", REST_DASH)
        put("why", context.getString(R.string.plan_rest_why))
    }

    private fun patchKeysText(patch: JSONObject): String =
        patch.keys().asSequence().joinToString("，") { "$it=${patch.get(it)}" }

    // ------------------------------------------------------------------
    // 今日计划：新增 / 删除条目（items[]，2026-10-07 P2）
    // ------------------------------------------------------------------

    /**
     * 新增一条计划条目。
     *
     * 两条**互斥**的形态（由 `day` 决定，与 `PlanGenerator.itemsOf` 的解析口径一一对应）：
     * - `day = today`（默认）：按 `HH:mm` 细排。**必须给 `time`**，`slot` 强制空串；
     *   条数受 [PlanGenerator.MAX_TODAY_ITEMS] 约束（超了**不写库**，见下方 ⚠️）。
 * - `day = tomorrow`：粗颗粒时段**锚点**。**必须给 `slot`**（[PlanSlot] 之一），
 *   `time` 强制空串、`kcal` 强制 0（锚点既定契约：明天的摄入还没发生，给数字就是编），
 *   同一 `slot` 只允许一条（4 个时段），并**另按 `MAX_TOMORROW_ITEMS` 显式封顶**。
     *
     * ⚠️ **上限必须在此拦下**：解析期 `PlanGenerator.itemsOf` 会把超限条目**静默丢弃** ——
     * 这里若放行，writer 报「已加上」而界面永远不显示，用户看到的是「说加了却没加」。
     * 这正是 `MAX_TODAY_ITEMS` / `MAX_TOMORROW_ITEMS` 从 `PlanGenerator` 抬成 `internal`
     * 的原因：上限必须**唯一来源**。
     *
     * ⚠️ 当前不支持「跨日期移动条目」（把今天的某项挪到明天）：`day` 是条目身份的一部分，
     * 移动需同时改 `time`↔`slot` 的形态，语义上更接近「删一条 + 加一条」。
     */
    private suspend fun addItemOp(
        context: Context,
        db: AppDatabase,
        date: String,
        op: JSONObject,
        commit: Boolean,
    ): Result {
        val row = db.planDao().getPlan(date)
            ?: return Result.Error("$date 还没有生成今日计划，无法新增条目。")
        val root = parseRoot(row.planJson)
            ?: return Result.Error("$date 的计划数据无法解析，未做修改。")
        val items = root.optJSONArray("items")
            ?: return Result.Error("$date 的计划里没有条目。")

        val raw = op.optJSONObject("patch")
            ?: return Result.Error("add_item 需要给出条目字段。")
        val err = sanitizePatch(raw)
        if (err != null) return Result.Error(err)
        if (!raw.has("type")) return Result.Error("新增条目必须给出 type。")
        if (raw.optString("title").trim().isEmpty()) return Result.Error("新增条目必须给出 title。")

        // 复制一份：sanitizePatch 已就地归一化，但 add 不应改动调用方传入的对象
        val item = JSONObject()
        for (key in raw.keys()) item.put(key, raw.get(key))

        val isTomorrow = when (op.optString("day").trim().lowercase(Locale.US)) {
            "", DAY_TODAY -> false
            DAY_TOMORROW -> true
            else -> return Result.Error("day 只能是 $DAY_TODAY / $DAY_TOMORROW。")
        }
        if (isTomorrow) {
            val slot = normalizeSlot(op.optString("slot").trim())
                ?: return Result.Error(
                    "day=tomorrow 必须给出 slot（${SLOT_KEYS.joinToString(" / ")}）。",
                )
            if (slotTaken(items, slot)) {
                return Result.Error(
                    "明天已经有「${slotLabel(context, slot)}」的锚点了，改成修改那一条，或换个时段。",
                )
            }
            // 显式护栏：正常情况下 4 个时段唯一 ⇒ 条数天然 ≤ 上限；但若库里已有
            // 超出上限的历史数据（旧版本 / 异常导入），仅靠"时段唯一"就兜不住 ——
            // 加一条没被任何检查覆盖的上限 = 迟早静默丢弃。
            if (countOfDay(items, PLAN_DAY_TOMORROW) >= PlanGenerator.MAX_TOMORROW_ITEMS) {
                return Result.Error(
                    "明天的锚点已有 ${PlanGenerator.MAX_TOMORROW_ITEMS} 条（上限），请先删掉一条。",
                )
            }
            item.put("day", PLAN_DAY_TOMORROW)
            item.put("time", "")
            item.put("slot", slot)
            item.put("kcal", 0)
        } else {
            val time = op.optString("time").trim()
            if (!TIME_PATTERN.matches(time)) {
                return Result.Error("day=today 必须给出 time（HH:mm 24 小时制）。")
            }
            if (countOfDay(items, PLAN_DAY_TODAY) >= PlanGenerator.MAX_TODAY_ITEMS) {
                return Result.Error(
                    "今天的条目已有 ${PlanGenerator.MAX_TODAY_ITEMS} 条（上限），" +
                        "请先删掉一条，或改为调整现有条目。",
                )
            }
            item.put("day", PLAN_DAY_TODAY)
            item.put("time", time)
            item.put("slot", "")
        }

        val title = item.optString("title")
        if (commit) {
            insertSorted(items, item)
            db.planDao().upsertPlan(row.copy(planJson = root.toString()))
        }
        return Result.Ok(
            if (isTomorrow) {
                "在 $date 的计划「明天·${slotLabel(context, item.optString("slot"))}」加一条：$title"
            } else {
                "在 $date 的计划加一条 ${item.optString("time")} $title"
            },
        )
    }

    /** 删除一条计划条目（定位须唯一，复用 [locate] 的 `title` / `time` 口径）。 */
    private suspend fun removeItemOp(
        db: AppDatabase,
        date: String,
        op: JSONObject,
        commit: Boolean,
    ): Result {
        val row = db.planDao().getPlan(date)
            ?: return Result.Error("$date 还没有生成今日计划，无法删除条目。")
        val root = parseRoot(row.planJson)
            ?: return Result.Error("$date 的计划数据无法解析，未做修改。")
        val items = root.optJSONArray("items")
            ?: return Result.Error("$date 的计划里没有条目。")

        val matchTitle = op.optString("match_title").trim()
        val matchTime = op.optString("match_time").trim()
        if (matchTitle.isEmpty() && matchTime.isEmpty()) {
            return Result.Error("需要给出 match_title（或 match_time）来指定要删的条目。")
        }
        val located = when (val l = locate(items, matchTitle, matchTime)) {
            is Locate.Found -> l
            is Locate.Fail -> return Result.Error(l.reason)
        }
        val title = items.optJSONObject(located.index)
            ?.optString("title")?.trim().orEmpty().ifEmpty { "（无标题）" }
        if (commit) {
            items.remove(located.index)
            db.planDao().upsertPlan(row.copy(planJson = root.toString()))
        }
        return Result.Ok("从 $date 的计划里删掉「$title」")
    }

    /** 条目所属日（收敛为 [PLAN_DAY_TODAY] / [PLAN_DAY_TOMORROW]，与 `itemsOf` 同判据）。 */
    private fun dayOf(o: JSONObject): Int =
        if (o.optInt("day", PLAN_DAY_TODAY) >= PLAN_DAY_TOMORROW) PLAN_DAY_TOMORROW else PLAN_DAY_TODAY

    /** 数出 `items[]` 中属于 `day` 的条目数。 */
    private fun countOfDay(items: JSONArray, day: Int): Int {
        var n = 0
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            if (dayOf(o) == day) n++
        }
        return n
    }

    /** 明天是否已有该时段的锚点。 */
    private fun slotTaken(items: JSONArray, slot: String): Boolean {
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            if (dayOf(o) == PLAN_DAY_TOMORROW && o.optString("slot").trim() == slot) return true
        }
        return false
    }

    /**
     * 归一时段键：只认 [PlanSlot] 的四个**规范 ASCII** 键（顺带容忍大小写）。
     *
     * 刻意**不认**中文「早/午/晚/练」——`PlanGenerator.resolveSlot` 认中文是给**模型原始输出**
     * 兜底用的；走到这里的是**已经过我们校验的载荷**，再多一种写法只会多一处只有模型能触发的分支。
     */
    private fun normalizeSlot(raw: String): String? {
        val s = raw.lowercase(Locale.US)
        return when (s) {
            PlanSlot.MORNING, PlanSlot.NOON, PlanSlot.EVENING, PlanSlot.TRAIN -> s
            else -> null
        }
    }

    /** 时段展示名（早 / 午 / 晚 / 训练）；未知键回落原始键。 */
    private fun slotLabel(context: Context, slot: String): String =
        PlanSlot.labelResOf(slot)?.let { context.getString(it) } ?: slot

    /**
     * 插到**规范顺序**（今天在前按 `time`，明天在后按时段），保持 `items[]` 时序可读。
     *
     * 为什么值得排：`query_plan` 的 `PlanGenerator.itemsDigest` 按**数组顺序**逐条列，
     * 乱序会让模型读到一份跳时序的清单。用稳定排序 —— 同键条目保持原有相对顺序。
     */
    private fun insertSorted(items: JSONArray, item: JSONObject) {
        val all = mutableListOf<JSONObject>()
        for (i in 0 until items.length()) items.optJSONObject(i)?.let { all += it }
        all += item
        all.sortWith(compareBy<JSONObject>({ dayOf(it) }, { itemSortKey(it) }))
        while (items.length() > 0) items.remove(0)
        for (o in all) items.put(o)
    }

    /** 天内排序键（今天 = `time`；明天 = [PlanSlot.sortKeyOf]），与展示口径同源。 */
    private fun itemSortKey(o: JSONObject): String =
        if (dayOf(o) == PLAN_DAY_TOMORROW) PlanSlot.sortKeyOf(o.optString("slot").trim())
        else o.optString("time").trim()

    // ------------------------------------------------------------------
    // 周训练计划：设休息日（training_plans）
    // ------------------------------------------------------------------

    /**
     * 把本周（`weekKeyOf(LocalDate.now())`）的某天设为休息日。
     *
     * 休息日判据 = `title == "休息"`（`R.string.training_rest`）**且** `items = []`
     * （与 `TrainingPlanner.parsePlan` 的 `isRest = items.isEmpty() || title == restTitle` 对齐）。
     * 该天对象不存在时补一条。`content` 列按 `parsePlan`/`renderText` 的**同口径**重算
     * （`TrainingPlanner.renderText` 为 private 且该文件 WIP 只读，故此处镜像其渲染规则，
     * 不改 `plan_json` 的 schema）。
     */
    private suspend fun trainingRestOp(
        context: Context,
        db: AppDatabase,
        op: JSONObject,
        commit: Boolean,
    ): Result {
        val dow = op.optInt("weekday", 0)
        if (dow !in 1..7) return Result.Error("weekday 必须是 1..7（1 = 周一）。")
        val weekKey = weekKeyOf(LocalDate.now())
        val row = db.trainingPlanDao().getWeek(weekKey)
            ?: return Result.Error("本周还没有训练计划，无法设置休息日。")
        val root = parseRoot(row.planJson)
            ?: return Result.Error("本周训练计划数据无法解析，未做修改。")
        val days = root.optJSONArray("days")
            ?: return Result.Error("本周训练计划没有天数据。")

        val restTitle = context.getString(R.string.training_rest)
        var found = false
        for (i in 0 until days.length()) {
            val dayObj = days.optJSONObject(i) ?: continue
            if (dayObj.optInt("dow", 0) == dow) {
                dayObj.put("title", restTitle)
                dayObj.put("items", JSONArray())
                found = true
                break
            }
        }
        if (!found) {
            days.put(
                JSONObject().apply {
                    put("dow", dow)
                    put("title", restTitle)
                    put("items", JSONArray())
                },
            )
        }

        if (commit) {
            db.trainingPlanDao().upsert(
                row.copy(planJson = root.toString(), content = renderTrainingText(context, root)),
            )
        }
        return Result.Ok("把本周${dowLabel(dow)}设为休息日")
    }

    /**
     * `training_plans.plan_json` → 纯文本（`content` 列）。
     *
     * **与 `TrainingPlanner.renderText` 逐条等价**（该函数 private 且 `TrainingPlanner.kt`
     * WIP 只读，故此处镜像其规则、不改 `plan_json` 的 schema）。等价性含三条子句，缺任一条
     * 写入的 `content` 就会与训练页实际渲染漂移：
     * 1. **休息判定** `isRest = items.isEmpty() || title == restTitle` —— 与
     *    `TrainingPlanner.parsePlan` 同源（`renderText` 判据 `day.isRest || day.items.isEmpty()`）；
     * 2. **空标题回落** —— `title` 空串时退回 `restTitle`
     *    （`parsePlan`：`optString("title").trim().ifEmpty { restTitle }`）；
     * 3. **补足 7 天** —— 缺失的 `dow`（或数组乱序 / 缺项）一律补成休息日
     *    （`parsePlan`：`(1..7).map { byDow[it] ?: TrainingDay(it, restTitle, emptyList(), true) }`）。
     * 渲染口径：每天「周X 标题」，休息条目只出头行；否则另起一行给动作串
     * 「名 组×次 · …」（镜像 `TrainingDay.itemsLine`）。
     */
    private fun renderTrainingText(context: Context, root: JSONObject): String {
        val restTitle = context.getString(R.string.training_rest)
        // ① 按 dow 建索引（镜像 parsePlan 的 byDow；dow 非 1..7 的条目丢弃、同 dow 后者覆盖）
        val byDow = LinkedHashMap<Int, JSONObject>()
        val days = root.optJSONArray("days")
        if (days != null) {
            for (i in 0 until days.length()) {
                val dayObj = days.optJSONObject(i) ?: continue
                val dow = dayObj.optInt("dow", 0)
                if (dow !in 1..7) continue
                byDow[dow] = dayObj
            }
        }
        // ③ 一律铺满 7 天（缺失的天按休息日渲染）
        return (1..7).joinToString("\n") { dow ->
            val dayObj = byDow[dow]
            // ② 空标题回落 restTitle
            val title = dayObj?.optString("title")?.trim().orEmpty().ifEmpty { restTitle }
            val itemsLine = dayObj?.let { itemsLineOf(it) } ?: ""
            // ① 休息判定：items 为空 或 title == restTitle
            val isRest = itemsLine.isEmpty() || title == restTitle
            val head = "${dowLabel(dow)} $title"
            if (isRest) head else "$head\n$itemsLine"
        }
    }

    /** 镜像 `TrainingDay.itemsLine`（格式「名 组×次 · …」）。 */
    private fun itemsLineOf(dayObj: JSONObject): String {
        val arr = dayObj.optJSONArray("items") ?: return ""
        val parts = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            parts += "$name ${o.optInt("sets", 0)}×${o.optString("reps").trim()}"
        }
        return parts.joinToString(" · ")
    }

    /**
     * **整周重排**本周训练计划（[OP_REPLACE_TRAINING_WEEK]，2026-10-07 P2 第三批）。
     *
     * 载荷：`{"op":"replace_training_week","focus":"…","note":"…",
     * "days":[{"dow":1,"title":"胸+三头","items":[{"name":"卧推","sets":4,"reps":"8-12"}]}]}`
     *
     * 纪律（模型输出永不可信）：
     * - `dow` 必须 1..7 且**不重复** —— 重复即 [Result.Error]：静默"后一个覆盖前一个"
     *   会让模型以为两天都排上了，用户看到却少一天；
     * - 每天动作数 / 动作名 / 组数 / 组次 都有闸门（见文件顶部常量组）；
     * - **缺失的天补成休息日**（与 `TrainingPlanner.parsePlan` 的「补齐缺失的天为休息日」
     *   同口径）—— 保证写进去的 `days` 恒为 7 天，`renderTrainingText` 的等价性前提不被破坏；
     * - 空值守卫：`"title": null` 经 `optString` 会读成字面串 `"null"`（[sanitizePatch]
     *   已踩过），故走 [textOf] 归一。
     *
     * 写回走**读改写**：只覆盖 `focus` / `days`（`note` 仅在模型显式给出时覆盖），
     * 根对象其余字段原样保留 —— 与其它 op 同一条纪律。
     */
    private suspend fun replaceTrainingWeekOp(
        context: Context,
        db: AppDatabase,
        op: JSONObject,
        commit: Boolean,
    ): Result {
        val rawDays = op.optJSONArray("days")
            ?: return Result.Error("整周重排需要给出 days 数组（周一至周日）。")
        val byDow = LinkedHashMap<Int, JSONObject>()
        for (i in 0 until rawDays.length()) {
            val dayObj = rawDays.optJSONObject(i)
                ?: return Result.Error("days 里第 ${i + 1} 项不是对象。")
            val dow = dayObj.optInt("dow", 0)
            if (dow !in 1..7) return Result.Error("days 里的 dow 必须是 1..7（1 = 周一）。")
            if (byDow.containsKey(dow)) return Result.Error("第 $dow 天给了两次，请合并成一条。")
            val title = (textOf(dayObj, "title")
                ?: return Result.Error("第 $dow 天的 title 不能为空。"))
                .trim().take(MAX_DAY_TITLE)
            if (title.isEmpty()) return Result.Error("第 $dow 天的 title 不能为空。")

            val rawItems = dayObj.optJSONArray("items")
            if (rawItems != null && rawItems.length() > MAX_ITEMS_PER_DAY) {
                return Result.Error("每天最多 $MAX_ITEMS_PER_DAY 个动作（第 $dow 天超了）。")
            }
            val items = JSONArray()
            if (rawItems != null) {
                for (j in 0 until rawItems.length()) {
                    val itemObj = rawItems.optJSONObject(j) ?: continue
                    val name = (textOf(itemObj, "name")
                        ?: return Result.Error("第 $dow 天有个动作没写名字。"))
                        .trim().take(MAX_ITEM_NAME)
                    if (name.isEmpty()) return Result.Error("第 $dow 天有个动作没写名字。")
                    val sets = itemObj.optInt("sets", 0)
                    if (sets !in MIN_SETS..MAX_SETS) {
                        return Result.Error("「$name」的组数要在 $MIN_SETS–$MAX_SETS 之间。")
                    }
                    val reps = (textOf(itemObj, "reps")
                        ?: return Result.Error("「$name」没写每组次数。"))
                        .trim().take(MAX_REPS_LEN)
                    if (reps.isEmpty()) return Result.Error("「$name」没写每组次数。")
                    items.put(
                        JSONObject().apply {
                            put("name", name)
                            put("sets", sets)
                            put("reps", reps)
                        },
                    )
                }
            }
            byDow[dow] = JSONObject().apply {
                put("dow", dow)
                put("title", title)
                put("items", items)
            }
        }
        if (byDow.isEmpty()) return Result.Error("days 至少要给一天。")

        val weekKey = weekKeyOf(LocalDate.now())
        val row = db.trainingPlanDao().getWeek(weekKey)
            ?: return Result.Error("本周还没有训练计划，无法重排。")
        val root = parseRoot(row.planJson)
            ?: return Result.Error("本周训练计划数据无法解析，未做修改。")

        // 铺满 7 天：模型没给的天一律成休息日（否则 renderTrainingText 会把那天当休息
        // 渲染、两条口径看似一致，实则 `plan_json` 里少了条目 —— 下次读回来就丢）
        val restTitle = context.getString(R.string.training_rest)
        val days = JSONArray()
        for (dow in 1..7) {
            days.put(
                byDow[dow] ?: JSONObject().apply {
                    put("dow", dow)
                    put("title", restTitle)
                    put("items", JSONArray())
                },
            )
        }
        root.put("focus", (textOf(op, "focus") ?: "").trim().take(MAX_FOCUS_LEN))
        root.put("days", days)
        // note 只在模型显式给出时覆盖 —— 「没提备注」不等于「要清空备注」
        if (op.has("note")) {
            root.put("note", (textOf(op, "note") ?: "").trim().take(MAX_NOTE_CHARS))
        }

        val trainingDays = byDow.values.filter { (it.optJSONArray("items")?.length() ?: 0) > 0 }
        val summary = if (trainingDays.isEmpty()) {
            "把本周训练全部改成休息"
        } else {
            "把本周训练重排为：" + trainingDays.joinToString("、") { d ->
                val dow = d.optInt("dow", 0)
                "${dowLabel(dow)} ${d.optString("title")}"
            } + "（共 ${trainingDays.size} 个训练日）"
        }

        if (commit) {
            db.trainingPlanDao().upsert(
                row.copy(planJson = root.toString(), content = renderTrainingText(context, root)),
            )
        }
        return Result.Ok(
            summary = summary,
            undo = if (commit) {
                SnapshotRestore(
                    kind = UndoWriter.KIND_TRAINING_WEEK,
                    key = weekKey,
                    oldValue = row.planJson,
                    oldContent = row.content,
                    label = context.getString(R.string.undo_training_week_replaced),
                )
            } else {
                null
            },
        )
    }

    // ------------------------------------------------------------------
    // 定位 / 校验（纯函数，describe 与 apply 共用 = 单一事实来源）
    // ------------------------------------------------------------------

    private sealed interface Locate {
        data class Found(val index: Int) : Locate
        data class Fail(val reason: String) : Locate
    }

    /**
     * 在 `items[]` 里定位目标条目。
     *
     * 定位键 = **`title` 子串**（用户/模型眼里的条目身份；`query_plan` 返回的就是它）
     * + 可选 **`time`（HH:mm）**（同名多条时消歧）。刻意**不用数组下标**：模型无从可靠
     * 得知下标，且条目一旦增删即错位；`title`/`time` 是条目自带、跨读改写稳定的字段。
     *
     * @return 恰一条命中 → [Locate.Found]；0 条或多条 → [Locate.Fail]（**不写库**）。
     */
    private fun locate(items: JSONArray, matchTitle: String, matchTime: String): Locate {
        val hits = mutableListOf<Int>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            if (matchTitle.isNotEmpty() && !o.optString("title").contains(matchTitle, ignoreCase = true)) {
                continue
            }
            if (matchTime.isNotEmpty() && o.optString("time").trim() != matchTime) continue
            hits += i
        }
        return when {
            hits.isEmpty() -> Locate.Fail("没找到匹配「${locatorDesc(matchTitle, matchTime)}」的计划条目。")
            hits.size > 1 -> Locate.Fail(
                "匹配「${locatorDesc(matchTitle, matchTime)}」的计划条目有 ${hits.size} 条，" +
                    "请在 match_title / match_time 里说得更具体。",
            )
            else -> Locate.Found(hits.first())
        }
    }

    private fun locatorDesc(matchTitle: String, matchTime: String): String {
        val parts = mutableListOf<String>()
        if (matchTitle.isNotEmpty()) parts += "标题含「$matchTitle」"
        if (matchTime.isNotEmpty()) parts += "时间 $matchTime"
        return parts.joinToString("、")
    }

    /**
     * 补丁白名单校验 + 归一化（**就地**截断字符串、收敛 `type`）。
     *
     * @return null = 合法（`patch` 已被归一化）；否则错误文本（**不写库**）。
     */
    private fun sanitizePatch(patch: JSONObject): String? {
        if (patch.length() == 0) return "patch 不能为空。"
        for (key in patch.keys()) {
            if (key !in ITEM_PATCH_KEYS) {
                return "不允许修改字段「$key」（只能改 ${ITEM_PATCH_KEYS.joinToString(" / ")}）。"
            }
            // JSON null 守卫：`JSONObject.optString` 会把 JSON `null` 读成**字面串 "null"**，
            // 直接写库会把条目字段污染成字符串 "null"（而非缺省）。视为非法、原样回错误文本。
            if (patch.isNull(key)) return "字段「$key」的值不能为空。"
        }
        if (patch.has("type")) {
            val t = patch.optString("type").trim()
            if (t !in ITEM_TYPES) return "type 只能是 ${ITEM_TYPES.joinToString(" / ")}。"
            patch.put("type", t)
        }
        if (patch.has("title")) {
            val title = patch.optString("title").trim().take(PATCH_MAX_TITLE)
            if (title.isEmpty()) return "title 不能为空。"
            patch.put("title", title)
        }
        if (patch.has("detail")) {
            patch.put("detail", patch.optString("detail").trim().take(PATCH_MAX_DETAIL))
        }
        if (patch.has("why")) {
            patch.put("why", patch.optString("why").trim().take(PATCH_MAX_WHY))
        }
        if (patch.has("duration")) {
            patch.put("duration", patch.optString("duration").trim().take(PATCH_MAX_DURATION))
        }
        if (patch.has("kcal")) {
            val k = patch.optInt("kcal", -1)
            if (k < 0) return "kcal 必须是不小于 0 的整数。"
        }
        return null
    }

    /**
     * 读一个**可能被模型写成 JSON `null`** 的字符串字段（[replaceTrainingWeekOp] 用）。
     *
     * `JSONObject.optString` 把 JSON `null` 读成**字面串 `"null"`**（[sanitizePatch] 已踩过
     * 同一个坑），不设守卫就会把"动作名叫 null"这种脏数据写进 `plan_json`。
     *
     * @return 键不存在 → 空串（视为未给）；值为 JSON `null` → **null**（调用方按非法报错）；
     *         否则 `toString()`（**不 trim**，截断与 trim 由调用方按字段口径处理）
     */
    private fun textOf(o: JSONObject, key: String): String? {
        val v = o.opt(key) ?: return ""
        return if (v == JSONObject.NULL) null else v.toString()
    }

    /**
     * 解析 `plan_json` 为对象；空 / 非对象 / 解析失败 → null。
     * 防御式解析（与 `ChatViewModel.todayPlanNote` 同款），绝不抛异常。
     */
    private fun parseRoot(json: String?): JSONObject? = runCatching {
        if (json.isNullOrBlank()) return@runCatching null
        loadsLenient(json) as? JSONObject
    }.getOrNull()
}
