package com.healix.app.agent

import android.content.Context
import com.healix.app.HealixApp
import com.healix.app.db.ChatMessageEntity
import com.healix.app.db.GoalEntity
import com.healix.app.db.GoalMetrics
import com.healix.app.db.SettingsKeys
import com.healix.app.db.ToolCallEntity
import com.healix.app.net.ChatMessage
import com.healix.app.net.ChatRequest
import com.healix.app.net.ChatResult
import com.healix.app.net.ErrKind
import com.healix.app.net.OpenAiCompatProvider
import com.healix.app.net.ProviderConfig
import com.healix.app.net.ToolCall
import com.healix.app.net.ToolDef
import com.healix.app.net.ToolFunctionDef
import com.healix.app.parse.loadsLenient
import com.healix.app.repo.EventRepository
import com.healix.app.ui.ChatEngine
import com.healix.app.ui.PROMPT_VER_CHAT_TOOL
import com.healix.app.ui.PlanChangeWriter
import com.healix.app.ui.PromptMode
import com.healix.app.ui.TodaySummary
import com.healix.app.ui.TrainingPlanner
import com.healix.app.ui.dowLabel
import java.util.UUID
import org.json.JSONObject

/**
 * Agent 产出的**待确认草案**（v0.3 B6 泛化）：一切"需要用户点确认才生效"的动作。
 *
 * ⚠️ 这**不是**已入库的副作用 —— 只是"我打算这么做"的说明。用户点确认后由
 * ChatViewModel 走各自的写路径落库；点取消/下拖则什么都不发生。
 * Agent 自身**无权直接写入** events / daily_plans / goals 表。
 *
 * public（非 internal）：ChatViewModel 的公开流 [ChatViewModel.proposal] 要暴露它
 * —— Kotlin 禁止 public 成员暴露 internal 类型。
 *
 * ⚠️ `callUid` 是**相对设计草图的最小必要扩展**：设计 §1.3.3 要求确认/取消后
 *    `ToolCallDao.markApproved(call_uid, …)`，而 §3.3 的草案数据类未携带关联键 ——
 *    没有它就无法把"这次确认"回填到对应的 `tool_calls` 行。故各草案统一携带
 *    [callUid]（= 对应 `tool_calls.call_uid`）。
 */
sealed interface AgentProposal {
    /** 关联的 `tool_calls.call_uid`，供确认/取消后回填 `approved`。 */
    val callUid: String

    /** 供 UI 展示的人类可读摘要（`ActionConfirmSheet` 的 message）。 */
    val summary: String
}

/**
 * 拟一条记录草稿（`propose_log`）。
 *
 * `rawText` 是用户原话，确认后由 ChatViewModel 调 [EventRepository.submit]
 * 走完整抽取链（pending → done），与「记一笔」共用同一条数据管道。
 */
data class LogProposal(
    val rawText: String,
    override val callUid: String = "",
) : AgentProposal {
    override val summary: String get() = rawText
}

/**
 * 拟改计划（`propose_plan_change`）：今日计划**条目级**改动 / 备注，或周训练设休息日。
 *
 * @property date 目标日期 `yyyy-MM-dd`（今日计划类；`set_training_rest` 落到本周）
 * @property op   机器可应用的改动载荷（**规范化 JSON 串**）。取值域见
 *                [PlanChangeWriter]：`op` ∈ {`set_rest` / `patch_item` / `set_note` /
 *                `clear_note` / `set_training_rest`}
 */
data class PlanChangeProposal(
    val date: String,
    val op: String,
    override val callUid: String,
    override val summary: String,
) : AgentProposal

/** 拟改某个目标值（`propose_goal_change`）。 */
data class GoalChangeProposal(
    val metric: String,
    val value: Double,
    override val callUid: String,
    override val summary: String,
) : AgentProposal

/** 拟删除一条记录（`propose_record_delete`，服务端已解析出唯一 `clientEventId`）。 */
data class RecordDeleteProposal(
    val clientEventId: String,
    override val callUid: String,
    override val summary: String,
) : AgentProposal

/** Agent 循环的结果（调用方 ChatViewModel 按分支落库 / 回退）。 */
internal sealed interface AgentOutcome {

    /**
     * 模型给出最终文本回答（未调工具，或工具答完后收尾）。
     *
     * [toolsUsed] = 本轮 agent 实际执行的工具调用次数（>0 时气泡末行显示轻角标，§4.5）。
     */
    data class Done(val text: String, val toolsUsed: Int = 0) : AgentOutcome

    /**
     * 已拟稿（记录 / 计划 / 目标 / 删除），等用户确认。
     * text 是随附说明（落库为 assistant 消息）；proposal 是待确认草案（v0.3 B6 泛化）。
     */
    data class ProposalPending(val text: String, val proposal: AgentProposal) : AgentOutcome

    /** 明确限流：UI 显示"排队中"，不进降级链（等待本身是预期行为）。 */
    data class RateLimited(val message: String) : AgentOutcome

    /** 其它失败 → 调用方回退单轮 ChatEngine（降级链第二级）。 */
    data object Failed : AgentOutcome
}

/**
 * Agent 的工具权限（v0.3 B5/B6，决策 D4：全默认开）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 纵深防御的第二道（不能只靠 UI 拦）
 * ══════════════════════════════════════════════════════════════════════════
 * `HealthAgent` 读出当前开关 → 组成本对象 → 传给 [ToolRegistry.execute]；
 * **未授权时执行层直接拒绝**（返回"没有权限"文本，**不产 draft**）。
 * 即便模型被诱导构造出写调用，也拿不到任何副作用。
 *
 * 判定口径与设置页一致：键不存在 = 开（`!= "false"`）。
 */
data class ToolPermissions(
    val toolsEnabled: Boolean = true,
    val writePlan: Boolean = true,
    val writeRecord: Boolean = true,
    val writeGoal: Boolean = true,
) {
    companion object {
        /** 从 `settings` 读出当前权限（默认全开）。IO 读库，调用方在协程内。 */
        suspend fun load(context: Context): ToolPermissions {
            val dao = HealixApp.from(context).database.settingsDao()
            return ToolPermissions(
                toolsEnabled = dao.get(SettingsKeys.AI_TOOLS_ENABLED) != "false",
                writePlan = dao.get(SettingsKeys.AI_TOOL_WRITE_PLAN) != "false",
                writeRecord = dao.get(SettingsKeys.AI_TOOL_WRITE_RECORD) != "false",
                writeGoal = dao.get(SettingsKeys.AI_TOOL_WRITE_GOAL) != "false",
            )
        }
    }
}

/**
 * 一次工具执行的结果（v0.3 B5/B6）。
 *
 * @property text        回给模型的文本（中文紧凑 / 错误说明）
 * @property proposal    非空 = 已拟稿（调用方应终止循环转人工确认）
 * @property callUid     本次调用的关联 id（写 `tool_calls` + 回填 `approved` 用）
 * @property needsConfirm 是否需用户确认（拟稿类工具 = true，只读 = false）
 */
internal data class ToolExecResult(
    val text: String,
    val proposal: AgentProposal?,
    val callUid: String,
    val needsConfirm: Boolean,
)

/**
 * 工具注册表（S3–S4；v0.3 B5 加 3 只读 + B6 加 3 写）。
 *
 * 设计取舍：
 * - **只读为主**：query_events / query_stats / query_plan / query_goal /
 *   query_training_week 只查不写；写入口（propose_log / propose_plan_change /
 *   propose_goal_change / propose_record_delete）**只产草稿**（见 [AgentProposal]）
 *   —— 有界自主的边界在这里划死。
 * - 工具结果一律转成**中文紧凑文本**回给模型：省 token，且「来源标注」规则天然成立。
 * - 参数解析失败 / 未知名 / **无权限** 一律**不抛异常**，返回一句错误说明 ——
 *   模型能看到错误就有机会自纠，循环也不至于断。
 */
internal object ToolRegistry {

    const val NAME_QUERY_EVENTS = "query_events"
    const val NAME_QUERY_STATS = "query_stats"
    const val NAME_QUERY_PLAN = "query_plan"
    const val NAME_QUERY_GOAL = "query_goal"
    const val NAME_QUERY_TRAINING_WEEK = "query_training_week"
    const val NAME_PROPOSE_LOG = "propose_log"
    const val NAME_PROPOSE_PLAN_CHANGE = "propose_plan_change"
    const val NAME_PROPOSE_GOAL_CHANGE = "propose_goal_change"
    const val NAME_PROPOSE_RECORD_DELETE = "propose_record_delete"

    /** `yyyy-MM-dd` 校验正则（与 `query_events` 既有先例同款）。 */
    private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

    /** 可写目标指标（**不含 `primary`** —— 它的 `target_value` 编码的是模式，不是数值）。 */
    private val WRITABLE_GOAL_METRICS = setOf(
        GoalMetrics.KCAL_DAILY,
        GoalMetrics.WEIGHT_KG,
        GoalMetrics.SESSIONS_PER_WEEK,
        GoalMetrics.TRAIN_MINUTES_PER_WEEK,
        GoalMetrics.SLEEP_H,
        GoalMetrics.WATER_ML,
    )

    /**
     * 计划备注长度上限（防模型回超长串）。
     *
     * ⚠️ 刻意不叫 `MAX_NOTE_LEN`：`PlanGenerator` 内已有同名同值的私有常量，而
     *    `check_kotlin.py` 的 `check_duplicate_constants` 判据是「同名**且**同值」——
     *    本常量属工具层、与计划生成层的用途不同，取不同名避免无意义的收敛提示
     *    （与 `RULE_TEXT_MAX_LEN` vs `MAX_RULE_LEN` 同一处置理由）。
     */
    private const val MAX_PLAN_NOTE_LEN = 200

    /** `query_plan` 单次最多回多少条计划行（token 预算）。 */
    private const val MAX_PLAN_ROWS = 14

    /** 交给模型的工具 schema（OpenAI function calling 格式）。 */
    val defs: List<ToolDef> = listOf(
        ToolDef(
            function = ToolFunctionDef(
                name = NAME_QUERY_EVENTS,
                description = "查询某日期区间内的健康记录（饮食/运动/睡眠/体重/身体/生病），" +
                    "返回逐条原文与热量。回答“我某天吃了什么/练了什么”必须用它查证，禁止凭对话记忆编造。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "day_from" to mapOf(
                            "type" to "string",
                            "description" to "开始日期，yyyy-MM-dd",
                        ),
                        "day_to" to mapOf(
                            "type" to "string",
                            "description" to "结束日期，yyyy-MM-dd",
                        ),
                        "type" to mapOf(
                            "type" to "string",
                            "description" to "可选，按类型过滤：meal/exercise/sleep/body/illness/other",
                        ),
                    ),
                    "required" to listOf("day_from", "day_to"),
                ),
            ),
        ),
        ToolDef(
            function = ToolFunctionDef(
                name = NAME_QUERY_STATS,
                description = "重新读取今日健康摘要（摄入/消耗/运动次数/睡眠/体重等" +
                    "本地统计数字）。记录刚发生变化、需要最新数字时用。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any?>(),
                ),
            ),
        ),
        ToolDef(
            function = ToolFunctionDef(
                name = NAME_QUERY_PLAN,
                description = "查询某日期区间内已生成的今日计划（备注 / 来源）。" +
                    "回答“我的计划是什么/某天安排了什么”用它查证。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "day_from" to mapOf(
                            "type" to "string",
                            "description" to "开始日期，yyyy-MM-dd",
                        ),
                        "day_to" to mapOf(
                            "type" to "string",
                            "description" to "结束日期，yyyy-MM-dd",
                        ),
                    ),
                    "required" to listOf("day_from", "day_to"),
                ),
            ),
        ),
        ToolDef(
            function = ToolFunctionDef(
                name = NAME_QUERY_GOAL,
                description = "查询当前生效的目标（主目标 / 体重 / 训练 / 睡眠 / 饮水 / 摄入）。" +
                    "回答“我的目标是什么”用它查证，不要凭记忆。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any?>(),
                ),
            ),
        ),
        ToolDef(
            function = ToolFunctionDef(
                name = NAME_QUERY_TRAINING_WEEK,
                description = "查询本周训练计划（每天安排 + 已完成情况 + 每周目标）。" +
                    "回答“这周练什么/练了几次”用它查证。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any?>(),
                ),
            ),
        ),
        ToolDef(
            function = ToolFunctionDef(
                name = NAME_PROPOSE_LOG,
                description = "拟一条记录草稿。用户让你记东西时调用（raw_text 用用户的原话），" +
                    "草稿要经用户确认后才会写入，你无权直接写入记录。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "raw_text" to mapOf(
                            "type" to "string",
                            "description" to "要记录的内容，用用户原话",
                        ),
                    ),
                    "required" to listOf("raw_text"),
                ),
            ),
        ),
        ToolDef(
            function = ToolFunctionDef(
                name = NAME_PROPOSE_PLAN_CHANGE,
                description = "拟修改计划（草稿，需用户确认）：把某个计划条目改成休息、" +
                    "按字段修改某个条目、改或清空当日计划备注，也可把本周某天设为休息日。" +
                    "定位条目用 match_title（可加 match_time 消歧，命中必须唯一）。你无权直接修改计划。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "date" to mapOf(
                            "type" to "string",
                            "description" to "要修改的日期 yyyy-MM-dd（今日计划类用；set_training_rest 忽略）",
                        ),
                        "action" to mapOf(
                            "type" to "string",
                            "description" to "动作：set_rest=把某条目改成休息；patch_item=按字段改某条目；" +
                                "set_note=改当日备注；clear_note=清空备注；" +
                                "set_training_rest=把本周某天设为休息日",
                        ),
                        "match_title" to mapOf(
                            "type" to "string",
                            "description" to "要改的条目：标题片段（子串、不区分大小写）。set_rest / patch_item 用",
                        ),
                        "match_time" to mapOf(
                            "type" to "string",
                            "description" to "可选：条目时间 HH:mm，同名条目消歧用。set_rest / patch_item 用",
                        ),
                        "patch" to mapOf(
                            "type" to "object",
                            "description" to "patch_item 的改动对象，只允许含 type/title/detail/kcal/duration/why" +
                                "（type 只能 meal/exercise/sleep/habit）",
                            "properties" to mapOf(
                                "type" to mapOf("type" to "string"),
                                "title" to mapOf("type" to "string"),
                                "detail" to mapOf("type" to "string"),
                                "kcal" to mapOf("type" to "integer"),
                                "duration" to mapOf("type" to "string"),
                                "why" to mapOf("type" to "string"),
                            ),
                        ),
                        "note" to mapOf(
                            "type" to "string",
                            "description" to "set_note 的新备注文本",
                        ),
                        "weekday" to mapOf(
                            "type" to "integer",
                            "description" to "set_training_rest 的星期几 1..7（1 = 周一）",
                        ),
                    ),
                    "required" to listOf("date", "action"),
                ),
            ),
        ),
        ToolDef(
            function = ToolFunctionDef(
                name = NAME_PROPOSE_GOAL_CHANGE,
                description = "拟修改一个目标值（体重 kg / 每周训练次数 / 训练分钟 / 睡眠小时 / 饮水 ml / " +
                    "每日摄入 kcal）。草稿经用户确认后才生效，你无权直接修改目标。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "metric" to mapOf(
                            "type" to "string",
                            "description" to "目标指标：weight_kg / sessions_per_week / " +
                                "train_minutes_per_week / sleep_h / water_ml / kcal_daily",
                        ),
                        "value" to mapOf(
                            "type" to "number",
                            "description" to "新的目标数值（正数）",
                        ),
                    ),
                    "required" to listOf("metric", "value"),
                ),
            ),
        ),
        ToolDef(
            function = ToolFunctionDef(
                name = NAME_PROPOSE_RECORD_DELETE,
                description = "拟删除一条记录。用日期 + 关键词定位那条记录（关键词取原文里能唯一识别它的片段）；" +
                    "命中 0 条或多条会失败，请把关键词说得更具体。草稿经用户确认后才删除。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "day" to mapOf(
                            "type" to "string",
                            "description" to "记录所在日期，yyyy-MM-dd",
                        ),
                        "keyword" to mapOf(
                            "type" to "string",
                            "description" to "能唯一识别该记录的原文片段",
                        ),
                    ),
                    "required" to listOf("day", "keyword"),
                ),
            ),
        ),
    )

    /**
     * 执行一次工具调用。
     *
     * @return [ToolExecResult]：text 回给模型；proposal 非空 = 拟稿（调用方终止循环）。
     *   未授权 / 参数非法 / 未知名一律返回错误文本，**不抛异常**。
     */
    suspend fun execute(
        context: Context,
        call: ToolCall,
        perms: ToolPermissions,
    ): ToolExecResult {
        val uid = UUID.randomUUID().toString()
        val name = call.function.name

        // 纵深防御第一道：总开关关闭 → 任何工具一律拒绝（不产 draft、不查库）。
        if (!perms.toolsEnabled) {
            return ToolExecResult("AI 工具当前已被关闭，无法执行。", null, uid, false)
        }

        val args: JSONObject = try {
            JSONObject(call.function.arguments.ifBlank { "{}" })
        } catch (_: Exception) {
            return ToolExecResult("工具参数不是合法 JSON，请检查后重试。", null, uid, false)
        }

        return when (name) {
            NAME_QUERY_EVENTS -> ok(uid, queryEvents(context, args))
            NAME_QUERY_STATS -> ok(uid, queryStats(context))
            NAME_QUERY_PLAN -> ok(uid, queryPlan(context, args))
            NAME_QUERY_GOAL -> ok(uid, queryGoal(context))
            NAME_QUERY_TRAINING_WEEK -> ok(uid, queryTrainingWeek(context))

            NAME_PROPOSE_LOG -> if (!perms.writeRecord) {
                denied(uid)
            } else {
                proposeLog(args, uid)
            }

            NAME_PROPOSE_PLAN_CHANGE -> if (!perms.writePlan) {
                denied(uid)
            } else {
                proposePlanChange(context, args, uid)
            }

            NAME_PROPOSE_GOAL_CHANGE -> if (!perms.writeGoal) {
                denied(uid)
            } else {
                proposeGoalChange(args, uid)
            }

            NAME_PROPOSE_RECORD_DELETE -> if (!perms.writeRecord) {
                denied(uid)
            } else {
                proposeRecordDelete(context, args, uid)
            }

            else -> ToolExecResult("未知工具：$name", null, uid, false)
        }
    }

    // ── 结果构造 ────────────────────────────────────────────────────

    /** 只读工具结果。 */
    private fun ok(uid: String, text: String): ToolExecResult =
        ToolExecResult(text, null, uid, false)

    /** 权限缺失（不产 draft）。 */
    private fun denied(uid: String): ToolExecResult =
        ToolExecResult("你当前没有该操作权限，请让用户在设置里开启。", null, uid, false)

    /** 拟稿结果（需用户确认）。 */
    private fun draft(uid: String, text: String, proposal: AgentProposal): ToolExecResult =
        ToolExecResult(text, proposal, uid, true)

    // ── 只读工具 ────────────────────────────────────────────────────

    private suspend fun queryEvents(context: Context, args: JSONObject): String {
        val from = args.optString("day_from").trim()
        val to = args.optString("day_to").trim()
        val typeFilter = args.optString("type").trim()
        if (!DATE_PATTERN.matches(from) || !DATE_PATTERN.matches(to)) {
            return "day_from / day_to 必须是 yyyy-MM-dd 格式。"
        }
        val db = HealixApp.from(context).database
        val all = try {
            db.eventDao().listInRange(from, to)
        } catch (_: Exception) {
            return "查询失败，请稍后再试。"
        }
        val filtered = if (typeFilter.isEmpty()) {
            all
        } else {
            all.filter { it.type == typeFilter }
        }
        if (filtered.isEmpty()) return "该区间没有任何记录。"
        // token 预算（§4.3）：最多回 30 条，单条原文截断到 36 字。
        val shown = filtered.takeLast(30)
        val lines = shown.map { e ->
            val kcal = if (e.kcal > 0) "（${e.kcal} kcal）" else ""
            val raw = if (e.rawText.length > 36) e.rawText.take(36) + "…" else e.rawText
            "• ${e.dayKey} ${typeName(e.type)}：$raw$kcal"
        }
        val truncated = if (filtered.size > shown.size) {
            "\n（仅显示最近 ${shown.size} 条，共 ${filtered.size} 条）"
        } else {
            ""
        }
        return lines.joinToString("\n") + truncated
    }

    private suspend fun queryStats(context: Context): String {
        // 与系统提示同一数据源（TodaySummary）：一处口径，两处消费
        return TodaySummary.build(context).lines.joinToString("\n")
    }

    private suspend fun queryPlan(context: Context, args: JSONObject): String {
        val from = args.optString("day_from").trim()
        val to = args.optString("day_to").trim()
        if (!DATE_PATTERN.matches(from) || !DATE_PATTERN.matches(to)) {
            return "day_from / day_to 必须是 yyyy-MM-dd 格式。"
        }
        val db = HealixApp.from(context).database
        val rows = try {
            db.planDao().listPlansInRange(from, to)
        } catch (_: Exception) {
            return "查询失败，请稍后再试。"
        }
        if (rows.isEmpty()) return "该区间没有生成过今日计划。"
        val shown = rows.takeLast(MAX_PLAN_ROWS)
        val lines = shown.map { p ->
            val src = if (p.source == TrainingPlanner.SOURCE_AI) "AI" else "本地"
            val detail = planNoteOf(p.planJson)
                ?: p.content?.replace('\n', ' ')?.take(60)?.ifBlank { null }
                ?: "无备注"
            "• ${p.date}（$src）：$detail"
        }
        val truncated = if (rows.size > shown.size) {
            "\n（仅显示最近 ${shown.size} 条，共 ${rows.size} 条）"
        } else {
            ""
        }
        return lines.joinToString("\n") + truncated
    }

    private suspend fun queryGoal(context: Context): String {
        val db = HealixApp.from(context).database
        val rows = try {
            db.goalDao().listActive()
        } catch (_: Exception) {
            return "查询失败，请稍后再试。"
        }
        if (rows.isEmpty()) return "还没有设置目标。"
        return rows.joinToString("\n") { g -> "• ${goalMetricName(g.metric)}：${goalValueText(g)}" }
    }

    private suspend fun queryTrainingWeek(context: Context): String {
        val planner = TrainingPlanner(context)
        val plan = runCatching { planner.loadOrGenerate(false) }.getOrNull()
            ?: return "本周还没有生成训练计划。"
        val done = runCatching { planner.completedDows(plan) }.getOrDefault(emptySet())
        val goal = runCatching { planner.sessionsGoal() }.getOrDefault(0)
        val sb = StringBuilder()
        if (plan.focus.isNotBlank()) sb.append("本周重点：").append(plan.focus).append('\n')
        for (day in plan.days) {
            val mark = if (day.dow in done) "✓" else "·"
            val body = if (day.isRest) "休息" else "${day.title}（${day.itemsLine()}）"
            sb.append("$mark ${dowLabel(day.dow)}：$body").append('\n')
        }
        sb.append("已完成 ${done.size}/$goal 次")
        return sb.toString().trim()
    }

    // ── 拟稿工具（只产 draft）────────────────────────────────────────

    private fun proposeLog(args: JSONObject, uid: String): ToolExecResult {
        val raw = args.optString("raw_text").trim()
        if (raw.isEmpty()) return ToolExecResult("raw_text 不能为空。", null, uid, true)
        return draft(
            uid,
            "已拟好记录草稿：「$raw」。等待用户确认，确认后才会写入。",
            LogProposal(rawText = raw, callUid = uid),
        )
    }

    /**
     * 拟改计划（**条目级**，v0.3 B6 返工）：把模型给的 `action` 规格化成
     * [PlanChangeWriter] 的 `op` 载荷，再走 [PlanChangeWriter.describe] 的**拟稿期校验**
     * （只读库、不写库）—— 校验通过才产 draft，失败把错误文本回给模型（循环可继续）。
     *
     * 这样「改不了就静默改成别的」不再可能：条目定位失败（命中 0 条 / 多条）与非法
     * 补丁都在这里被拦下、原样回错误文本；真正落库推迟到用户确认后的
     * [PlanChangeWriter.apply]。
     */
    private suspend fun proposePlanChange(
        context: Context,
        args: JSONObject,
        uid: String,
    ): ToolExecResult {
        val date = args.optString("date").trim()
        if (!DATE_PATTERN.matches(date)) {
            return ToolExecResult("date 必须是 yyyy-MM-dd 格式。", null, uid, true)
        }
        val action = args.optString("action").trim()
        val op = JSONObject()
        when (action) {
            PlanChangeWriter.OP_SET_NOTE -> {
                val note = args.optString("note").trim()
                if (note.isEmpty()) {
                    return ToolExecResult("set_note 需要给出 note。", null, uid, true)
                }
                if (note.length > MAX_PLAN_NOTE_LEN) {
                    return ToolExecResult("note 太长（最多 $MAX_PLAN_NOTE_LEN 字）。", null, uid, true)
                }
                op.put("op", PlanChangeWriter.OP_SET_NOTE)
                op.put("note", note)
            }

            PlanChangeWriter.OP_CLEAR_NOTE -> op.put("op", PlanChangeWriter.OP_CLEAR_NOTE)

            PlanChangeWriter.OP_SET_REST -> {
                op.put("op", PlanChangeWriter.OP_SET_REST)
                op.put("match_title", args.optString("match_title").trim())
                op.put("match_time", args.optString("match_time").trim())
            }

            PlanChangeWriter.OP_PATCH_ITEM -> {
                op.put("op", PlanChangeWriter.OP_PATCH_ITEM)
                op.put("match_title", args.optString("match_title").trim())
                op.put("match_time", args.optString("match_time").trim())
                val patch = args.optJSONObject("patch")
                    ?: return ToolExecResult("patch_item 需要给出 patch 对象。", null, uid, true)
                op.put("patch", patch)
            }

            PlanChangeWriter.OP_SET_TRAINING_REST -> {
                op.put("op", PlanChangeWriter.OP_SET_TRAINING_REST)
                op.put("weekday", args.optInt("weekday", 0))
            }

            else -> return ToolExecResult(
                "action 不合法，可选：set_rest / patch_item / set_note / clear_note / set_training_rest。",
                null, uid, true,
            )
        }
        val db = HealixApp.from(context).database
        val opJson = op.toString()
        return when (val r = PlanChangeWriter.describe(context, db, date, opJson)) {
            is PlanChangeWriter.Result.Ok -> draft(
                uid,
                "已拟好计划修改草稿：${r.summary}。等待用户确认。",
                // op = 机器可应用的规范化载荷（已过拟稿期校验）
                PlanChangeProposal(date = date, op = opJson, callUid = uid, summary = r.summary),
            )

            is PlanChangeWriter.Result.Error -> ToolExecResult(r.message, null, uid, true)
        }
    }

    private fun proposeGoalChange(args: JSONObject, uid: String): ToolExecResult {
        val metric = args.optString("metric").trim()
        val value = args.optDouble("value", Double.NaN)
        if (metric !in WRITABLE_GOAL_METRICS) {
            return ToolExecResult(
                "metric 不合法，可选：${WRITABLE_GOAL_METRICS.joinToString(" / ")}。",
                null, uid, true,
            )
        }
        if (!value.isFinite() || value <= 0.0) {
            return ToolExecResult("value 必须是正数。", null, uid, true)
        }
        val summary = "把「${metricName(metric)}」目标改为 ${trimNum(value)}${metricUnit(metric)}"
        return draft(
            uid,
            "已拟好目标修改草稿：$summary。等待用户确认。",
            GoalChangeProposal(metric = metric, value = value, callUid = uid, summary = summary),
        )
    }

    private suspend fun proposeRecordDelete(
        context: Context,
        args: JSONObject,
        uid: String,
    ): ToolExecResult {
        val day = args.optString("day").trim()
        val keyword = args.optString("keyword").trim()
        if (!DATE_PATTERN.matches(day)) {
            return ToolExecResult("day 必须是 yyyy-MM-dd 格式。", null, uid, true)
        }
        if (keyword.isEmpty()) return ToolExecResult("keyword 不能为空。", null, uid, true)
        val db = HealixApp.from(context).database
        val rows = try {
            db.eventDao().listByDay(day)
        } catch (_: Exception) {
            return ToolExecResult("查询失败，请稍后再试。", null, uid, true)
        }
        val hits = rows.filter { it.rawText.contains(keyword) }
        if (hits.isEmpty()) {
            return ToolExecResult("在 $day 没找到包含「$keyword」的记录。", null, uid, true)
        }
        if (hits.size > 1) {
            return ToolExecResult(
                "在 $day 找到 ${hits.size} 条包含「$keyword」的记录，请把关键词说得更具体。",
                null, uid, true,
            )
        }
        val target = hits.first()
        val summary = "删除 $day 的记录「${target.rawText.take(30)}」"
        return draft(
            uid,
            "已拟好删除草稿：$summary。等待用户确认。",
            RecordDeleteProposal(
                clientEventId = target.clientEventId,
                callUid = uid,
                summary = summary,
            ),
        )
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    private fun planNoteOf(json: String?): String? = runCatching {
        if (json.isNullOrBlank()) return@runCatching null
        val obj = loadsLenient(json) as? JSONObject ?: return@runCatching null
        (obj.opt("note") as? String)?.trim()?.ifEmpty { null }
    }.getOrNull()

    private fun typeName(type: String): String = when (type) {
        "meal" -> "饮食"
        "exercise" -> "运动"
        "sleep" -> "睡眠"
        "body" -> "身体"
        "illness" -> "生病"
        else -> "其他"
    }

    private fun goalMetricName(metric: String): String = metricName(metric)

    /** 目标指标中文名。 */
    private fun metricName(metric: String): String = when (metric) {
        GoalMetrics.PRIMARY -> "主目标"
        GoalMetrics.KCAL_DAILY -> "每日摄入"
        GoalMetrics.WEIGHT_KG -> "体重"
        GoalMetrics.SESSIONS_PER_WEEK -> "每周训练次数"
        GoalMetrics.TRAIN_MINUTES_PER_WEEK -> "每周训练时长"
        GoalMetrics.SLEEP_H -> "睡眠"
        GoalMetrics.WATER_ML -> "饮水"
        else -> metric
    }

    private fun metricUnit(metric: String): String = when (metric) {
        GoalMetrics.WEIGHT_KG -> " kg"
        GoalMetrics.SLEEP_H -> " 小时"
        GoalMetrics.WATER_ML -> " ml"
        GoalMetrics.KCAL_DAILY -> " kcal"
        GoalMetrics.SESSIONS_PER_WEEK -> " 次/周"
        GoalMetrics.TRAIN_MINUTES_PER_WEEK -> " 分钟/周"
        else -> ""
    }

    private fun goalValueText(g: GoalEntity): String = when (g.metric) {
        GoalMetrics.PRIMARY -> when (g.targetValue.toInt()) {
            0 -> "增重"
            1 -> "减重"
            2 -> "保持"
            else -> "自定义"
        }
        GoalMetrics.SLEEP_H -> "${trimNum(g.targetValue)} 小时"
        GoalMetrics.WEIGHT_KG -> "${trimNum(g.targetValue)} kg"
        GoalMetrics.KCAL_DAILY -> "${g.targetValue.toInt()} kcal"
        GoalMetrics.SESSIONS_PER_WEEK -> "${g.targetValue.toInt()} 次/周"
        GoalMetrics.TRAIN_MINUTES_PER_WEEK -> "${g.targetValue.toInt()} 分钟/周"
        GoalMetrics.WATER_ML -> "${g.targetValue.toInt()} ml"
        else -> trimNum(g.targetValue)
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}

/**
 * HealthAgent（S3–S4）：**有界**工具循环。
 *
 * 三重保险（C5 有界自主，缺一不可）：
 * 1. **步数上限** [MAX_STEPS]：每执行一轮工具调用计 1 步，超过即弃局回退单轮；
 * 2. **墙钟上限** [WALL_CLOCK_MS]：从 run() 开始计时，任何一轮开始前检查，超时即回退；
 * 3. **同参即停**：连续两轮工具调用签名完全一致 = 模型卡死，立即回退。
 *
 * 降级链：agent（本类）→ 单轮 ChatEngine → 本地模板。
 * 本类只负责第一级到第二级的判定；单轮与模板由调用方（ChatViewModel / ChatEngine）承担。
 *
 * 埋点：
 * - **每次** provider 往返经 [EventRepository.recordChatCall] 记一条 `llm_calls`
 *   （purpose=ask，配额不变式：一次往返恰好一行、失败也记、禁补记）；
 * - **每次**工具调用经 [recordToolCall] 记一条 `tool_calls`（独立新表，**不进** `llm_calls`）。
 */
internal class HealthAgent(
    private val context: Context,
    private val repo: EventRepository,
) {

    private val db = HealixApp.from(context).database

    suspend fun run(
        config: ProviderConfig,
        sessionDate: String,
        userText: String,
        history: List<ChatMessageEntity>,
        background: String,
        knowledge: String,
        /** 用户自定义输出偏好规则（v0.3 B4）：与单轮同源注入，空 = 不注入。 */
        userRules: String = "",
    ): AgentOutcome {
        val provider = OpenAiCompatProvider(config)
        val deadline = System.currentTimeMillis() + WALL_CLOCK_MS
        // 权限（纵深防御第二道）：读出开关 → 执行层真拦（不只看 UI）。
        val perms = ToolPermissions.load(context)

        val messages = mutableListOf<ChatMessage>()
        // 系统提示与单轮同源（ChatEngine），但按**工具路径模式**注入：
        // `PromptMode.TOOL` 走压缩版「说话方式」+ 省略「今天的已知数字」（去重，数字改由
        // query_stats 按需取），人格/硬边界/隐私规则仍同源（不出现第二套人格）。
        // 用户规则同样作用于工具路径（PV-1）。工具说明段（含动态权限声明）追加在其后。
        messages += ChatMessage(
            role = "system",
            content = ChatEngine.systemPrompt(
                context, sessionDate, background, knowledge,
                mode = PromptMode.TOOL,
                userRules = userRules,
            ) + toolsSection(perms),
        )
        history.takeLast(HISTORY_WINDOW).forEach { m ->
            if (m.role == "user" || m.role == "assistant") {
                messages += ChatMessage(role = m.role, content = m.content)
            }
        }
        messages += ChatMessage(role = "user", content = userText)

        var lastSignature = ""
        var steps = 0
        // 本轮实际执行的工具调用次数（§4.5 工具使用可见性），随 Done 回传给调用方
        var toolsUsed = 0

        while (steps < MAX_STEPS) {
            if (System.currentTimeMillis() >= deadline) return AgentOutcome.Failed

            val startedAt = System.currentTimeMillis()
            val result = provider.chat(
                ChatRequest(
                    messages = messages.toList(),
                    tools = ToolRegistry.defs,
                    // 工具路径用 TOOL_TEMPERATURE(0.4)：比单轮 0.7 更克制，减少多步调用漂移；
                    // 抽取链 0.3 / 训练链 0.4 一字不动（对齐 PRD §6.4 B）。
                    temperature = TOOL_TEMPERATURE,
                    timeoutMs = TIMEOUT_MS,
                    // 循环内重试降为 2 次：墙钟预算优先给"走完多步"而不是"单步死磕"
                    maxRetries = 2,
                ),
            )
            recordRoundTrip(config, result, System.currentTimeMillis() - startedAt)

            when (result) {
                is ChatResult.Err -> return when (result.kind) {
                    ErrKind.RATE_LIMIT -> AgentOutcome.RateLimited(result.message)
                    else -> AgentOutcome.Failed
                }

                is ChatResult.Ok -> {
                    if (result.toolCalls.isEmpty()) {
                        val text = result.content.trim()
                        return if (text.isEmpty()) {
                            AgentOutcome.Failed
                        } else {
                            AgentOutcome.Done(text, toolsUsed)
                        }
                    }

                    messages += ChatMessage(
                        role = "assistant",
                        content = result.content,
                        toolCalls = result.toolCalls,
                    )

                    var proposal: AgentProposal? = null
                    for (call in result.toolCalls) {
                        val callStarted = System.currentTimeMillis()
                        val exec = ToolRegistry.execute(context, call, perms)
                        val callLatency = System.currentTimeMillis() - callStarted
                        toolsUsed++
                        // tool_calls 审计：fire-and-forget（失败不影响主流程），独立于 llm_calls
                        recordToolCall(
                            callUid = exec.callUid,
                            call = call,
                            resultText = exec.text,
                            latencyMs = callLatency,
                            needsConfirm = exec.needsConfirm,
                        )
                        messages += ChatMessage(
                            role = "tool",
                            content = exec.text,
                            toolCallId = call.id,
                        )
                        if (exec.proposal != null) proposal = exec.proposal
                    }

                    // 拟稿是终止性动作：草稿必须经人确认，循环到此为止
                    if (proposal != null) {
                        val note = result.content.trim().ifEmpty {
                            context.getString(com.healix.app.R.string.proposal_note_default)
                        }
                        return AgentOutcome.ProposalPending(note, proposal)
                    }

                    val signature = result.toolCalls
                        .joinToString("|") { "${it.function.name}:${it.function.arguments}" }
                    if (signature == lastSignature) return AgentOutcome.Failed
                    lastSignature = signature
                    steps++
                }
            }
        }
        return AgentOutcome.Failed
    }

    /**
     * 每次 provider 往返落一条 `llm_calls`（`purpose = ask`）。失败也是真实消耗，同样要记。
     *
     * ── 埋点不变式（v0.3 B0 核对固化，**无行为改动**）─────────────────────────
     * **一次 provider 往返恰好一行 `llm_calls`**；**失败也记**；**禁补记**。
     *
     * 本方法是**移位写法**：[run] 里每轮 `provider.chat` 之后**当场**调用它（`Ok` / `Err`
     * 都记），不批处理、不事后补记。
     */
    private suspend fun recordRoundTrip(
        config: ProviderConfig,
        result: ChatResult,
        latencyMs: Long,
    ) {
        when (result) {
            is ChatResult.Ok -> repo.recordChatCall(
                model = config.model,
                attempts = 1,
                latencyMs = latencyMs,
                status = EventRepository.STATUS_OK,
                httpCode = 200,
                inputTokens = result.usage.inputTokens,
                outputTokens = result.usage.outputTokens,
                // agent 走 PromptMode.TOOL（提示已变更）→ 独立版本序列 PROMPT_VER_CHAT_TOOL，
                // 与单轮 / 回退路径的 PROMPT_VER_CHAT 分开归因。
                promptVer = PROMPT_VER_CHAT_TOOL,
            )

            is ChatResult.Err -> repo.recordChatCall(
                model = config.model,
                attempts = result.attempts,
                latencyMs = latencyMs,
                status = when (result.kind) {
                    ErrKind.AUTH -> EventRepository.STATUS_HTTP_ERROR
                    ErrKind.TIMEOUT -> EventRepository.STATUS_TIMEOUT
                    else -> EventRepository.STATUS_RETRY_EXHAUSTED
                },
                httpCode = result.httpCode,
                errorHead = result.message,
                promptVer = PROMPT_VER_CHAT_TOOL,
            )
        }
    }

    /**
     * 每次工具调用落一条 `tool_calls`（v0.3 B5/B6）。
     *
     * **独立于 `llm_calls`**：一次 provider 往返可有 N 次工具调用，粒度不同；
     * 本表**不参与**配额计数（配额不变式只数 provider 往返）。fire-and-forget：
     * 写库失败不影响主流程（[runCatching] 吞异常）。
     *
     * `args` / `result_digest` 截断后存（防长串占页）。
     */
    private suspend fun recordToolCall(
        callUid: String,
        call: ToolCall,
        resultText: String,
        latencyMs: Long,
        needsConfirm: Boolean,
    ) {
        runCatching {
            db.toolCallDao().insert(
                ToolCallEntity(
                    ts = System.currentTimeMillis(),
                    callUid = callUid,
                    name = call.function.name,
                    args = call.function.arguments.take(MAX_ARGS_LEN),
                    resultDigest = resultText.replace('\n', ' ').take(MAX_DIGEST_LEN),
                    latencyMs = latencyMs,
                    needsConfirm = if (needsConfirm) 1 else 0,
                    approved = null,
                ),
            )
        }
    }

    /**
     * 工具说明段 + **动态权限声明**（v0.3 B6，非冻结区）。
     *
     * 权限声明随当前开关变化：只读 / 可拟改某几类草稿 / 全无 —— 让模型知道自己能做什么，
     * 而不是在被拒绝后才明白。真正的拦截仍在 [ToolRegistry.execute] 执行层。
     */
    private fun toolsSection(perms: ToolPermissions): String {
        val grant = when {
            !perms.toolsEnabled ->
                "你当前没有任何工具权限，只能凭已有信息回答。"

            else -> {
                val writeParts = buildList {
                    if (perms.writePlan) add("改今日计划")
                    if (perms.writeRecord) add("拟记 / 删记录")
                    if (perms.writeGoal) add("改目标")
                }
                if (writeParts.isEmpty()) {
                    "你当前只有只读权限（可查询记录 / 计划 / 目标 / 周训练），不能修改任何数据。"
                } else {
                    "你可以拟改${writeParts.joinToString("、")}的草稿（均需用户确认后才会生效，你无权直接写入）。"
                }
            }
        }
        return TOOLS_SECTION + "\n权限：$grant"
    }

    companion object {
        /** 步数上限（C5）：一轮"模型响应 + 工具执行"算一步。 */
        private const val MAX_STEPS = 4

        /** 墙钟上限（毫秒）：整个 run() 的自然时间预算。 */
        private const val WALL_CLOCK_MS = 30_000L

        /** 单次往返超时（毫秒）。 */
        private const val TIMEOUT_MS = 12_000L

        /**
         * 工具路径采样温度（v0.3 B0）：落在 0.3~0.5 区间取中。
         *
         * 比单轮聊天的 0.7 更克制 —— 工具循环要"稳定走完多步 + 参数别乱飘"；
         * **仅工具路径**用；单轮仍 0.7、抽取链 0.3、训练链 0.4 均不动（PRD §6.4 B）。
         */
        private const val TOOL_TEMPERATURE = 0.4

        /** 历史窗口：与单轮一致（9.2 上下文策略）。 */
        private const val HISTORY_WINDOW = 16

        /** `tool_calls.args` 截断长度。 */
        private const val MAX_ARGS_LEN = 500

        /** `tool_calls.result_digest` 截断长度。 */
        private const val MAX_DIGEST_LEN = 200

        /**
         * 工具说明段（追加在系统提示之后）。
         * 硬措辞只有两条：查证义务 + 无写入权；其余交给模型自己判断何时用。
         * 末尾由 [toolsSection] 追加一行动态权限声明。
         */
        private const val TOOLS_SECTION = """

你可以使用工具（不必每轮都用，已有信息足够就直接回答）：
- query_events：查某日期区间的记录原文。回答"我那天吃了什么/练了什么"必须先查证，禁止凭对话记忆编。
- query_stats：重新取今日摘要数字。今日数字**不再**注入背景，需要当日摄入/消耗等数字时调它。
- query_plan：查某日期区间已生成的今日计划（备注 / 来源）。
- query_goal：查当前生效的目标。
- query_training_week：查本周训练安排与完成情况。
- propose_log：用户让你记东西时，用用户原话拟一条草稿。草稿经用户确认后才会写入，你无权直接写入记录。
- propose_plan_change：拟改计划（date + action）：把某条目改成休息 / 按字段改某条目 / 改或清备注 / 把本周某天设为休息日。草稿经用户确认后才生效。
- propose_goal_change：拟改某项目标值（metric + value）。草稿经用户确认后才生效。
- propose_record_delete：拟删一条记录（day + keyword，命中须唯一）。草稿经用户确认后才删除。
回答里引用的数字只能来自记录原文或工具返回。
"""
    }
}
