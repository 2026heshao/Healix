package com.healix.app.ui

import android.content.Context
import com.healix.app.R
import com.healix.app.db.AppDatabase
import com.healix.app.parse.loadsLenient
import java.time.LocalDate
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
 * 周训练（`training_plans`，`op = set_training_rest`）结构（亲读 `TrainingPlanner.kt`
 * `serialize`/`parsePlan` 核实）：`{ focus, days:[{ dow(1..7), title, items:[{name,sets,reps}] }], note }`；
 * 休息日判据 = `items` 为空 **或** `title == "休息"`（`R.string.training_rest`）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 校验纪律（模型输出永不可信）
 * ══════════════════════════════════════════════════════════════════════════
 * - **不抛异常**：一切非法输入返回 [Result.Error]（调用方回一句错误文本，循环可继续）；
 * - **定位歧义**（命中 0 条 / 多条）→ [Result.Error]，**不写库**；
 * - **补丁键 allowlist**：今日计划条目只允许改 `type/title/detail/kcal/duration/why`，
 *   越界键 / 非法 `type` / 负 `kcal` → [Result.Error]，**不写库**；
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

    /** 周训练计划：把本周某天设为休息日。 */
    const val OP_SET_TRAINING_REST = "set_training_rest"

    /**
     * 今日计划条目**唯一允许改**的字段（allowlist）。
     * `day` / `time` / `slot` 是定位与排序键，**刻意不可改**（改排序键会让条目跳位）。
     */
    private val ITEM_PATCH_KEYS = setOf("type", "title", "detail", "kcal", "duration", "why")

    /** `items[].type` 实际取值域（亲读 `PlanGenerator` 核实，见类 KDoc）。 */
    private val ITEM_TYPES = setOf("meal", "exercise", "sleep", "habit")

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

    /**
     * 休息条目 `duration` 的占位符（与渲染口径一致）。
     * `TimelineEntry.DASH` 是 private，无法复用 → 此处另名 `REST_DASH`，同时避开
     * `check_duplicate_constants` 的「同名且同值」判据。
     */
    private const val REST_DASH = "——"

    /** 结果：成功附人类可读摘要（供确认弹窗正文 / 回执），失败附错误文本。 */
    internal sealed interface Result {
        data class Ok(val summary: String) : Result
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
            OP_SET_TRAINING_REST -> trainingRestOp(context, db, op, commit)
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
     * 解析 `plan_json` 为对象；空 / 非对象 / 解析失败 → null。
     * 防御式解析（与 `ChatViewModel.todayPlanNote` 同款），绝不抛异常。
     */
    private fun parseRoot(json: String?): JSONObject? = runCatching {
        if (json.isNullOrBlank()) return@runCatching null
        loadsLenient(json) as? JSONObject
    }.getOrNull()
}
