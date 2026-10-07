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
import com.healix.app.parse.dayStartHourOf
import com.healix.app.parse.loadsLenient
import com.healix.app.repo.EventRepository
import com.healix.app.repo.QuotaGuard
import com.healix.app.ui.ChatEngine
import com.healix.app.ui.HealixDate
import com.healix.app.ui.PROMPT_VER_CHAT_TOOL
import com.healix.app.ui.PlanChangeWriter
import com.healix.app.ui.PlanGenerator
import com.healix.app.ui.ProfileWriter
import com.healix.app.ui.PromptMode
import com.healix.app.ui.ReminderWriter
import com.healix.app.ui.SettingsWriter
import com.healix.app.ui.TodaySummary
import com.healix.app.ui.TrainingPlanner
import com.healix.app.ui.dowLabel
import java.time.Instant
import java.time.ZoneId
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
 *                [PlanChangeWriter]：`op` ∈ {`add_item` / `remove_item` / `set_rest` /
 *                `patch_item` / `set_note` / `clear_note` / `set_training_rest`}
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

/**
 * 拟改**画像 / 资源清单**（`propose_profile_update`，2026-10-07 P1）：
 * 忌口过敏 / 疼痛部位 / 就餐场景 / 手头食物 / 常备药物 / 运动条件 / 作息时刻。
 *
 * 「我以后不吃辣」这类最高频诉求的落点 —— 改完写进 `settings` 的 `profile_*` 键，
 * 下一轮对话与下次生成的计划都会读到。
 *
 * @property op 机器可应用的改动载荷（**规范化 JSON 串**，已过拟稿期校验）。取值域见
 *                [ProfileWriter]：`{"op":"add"|"remove"|"set","field":"…","value":"…"}`
 */
data class ProfileUpdateProposal(
    val op: String,
    override val callUid: String,
    override val summary: String,
) : AgentProposal

/**
 * 拟改**体格与运行设置**（`propose_settings_update`，2026-10-07 P1）：
 * 身高 / 体重 / 年龄 / 活动系数 / 日界线 / 两个隐私开关。
 *
 * ⚠️ 不含 `ai_data_full`（AI 可见资料范围）—— 让 AI 提议扩大自己的可见范围属自授权，
 *    该键只由用户在设置页亲手改。理由见 [SettingsWriter] 类 KDoc。
 *
 * @property op 机器可应用的改动载荷（**规范化 JSON 串**，已过拟稿期校验）。取值域见
 *                [SettingsWriter]：`{"field":"…","value":"…"}`
 */
data class SettingsUpdateProposal(
    val op: String,
    override val callUid: String,
    override val summary: String,
) : AgentProposal

/**
 * 拟改**周期性提醒**（`propose_reminder_change`，2026-10-07 P2）：
 * 新增 / 修改（名字、周期、上次日期）/ 删除 `reminders` 表里的条目。
 *
 * 「提醒我每 3 个月洗牙」这类诉求的落点 —— 改完写进 `reminders`，
 * 设置页与状态页下次刷新即看到。
 *
 * @property op 机器可应用的改动载荷（**规范化 JSON 串**，已过拟稿期校验）。取值域见
 *                [ReminderWriter]：`{"op":"add"|"update"|"delete","name":"…",
 *                "match_name":"…","interval_days":N,"last_done":"yyyy-MM-dd"}`
 */
data class ReminderChangeProposal(
    val op: String,
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
     * 已拟稿（记录 / 计划 / 目标 / 删除 / 画像 / 设置 / 提醒），等用户确认。
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
    val writeProfile: Boolean = true,
    val writeSettings: Boolean = true,
    val writeReminder: Boolean = true,
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
                writeProfile = dao.get(SettingsKeys.AI_TOOL_WRITE_PROFILE) != "false",
                writeSettings = dao.get(SettingsKeys.AI_TOOL_WRITE_SETTINGS) != "false",
                writeReminder = dao.get(SettingsKeys.AI_TOOL_WRITE_REMINDER) != "false",
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
 * 工具注册表（S3–S4；v0.3 B5 加 3 只读 + B6 加 3 写；2026-10-07 P0 再补 4 只读 +
 * P1 再补 2 写 + P2 扩 `propose_plan_change` 动作域到 7 个、再补 1 写）。
 *
 * 设计取舍：
 * - **只读为主**：query_events / query_stats / query_plan / query_goal /
 *   query_training_week / query_review / query_warnings / query_reminders /
 *   query_settings 只查不写；写入口（propose_log / propose_plan_change /
 *   propose_goal_change / propose_record_delete / propose_profile_update /
 *   propose_settings_update / propose_reminder_change）**只产草稿**（见 [AgentProposal]）
 *   —— 有界自主的边界在这里划死。
 * - **扩动作域 vs 加新工具**：同一实体上的新增动作（如计划条目的增删）扩 `action`
 *   枚举即可，不新建工具名 —— 多一个工具名就多一处 `NAME_*` / `defs` / `execute` /
 *   `TOOLS_SECTION` 四处同步点，且模型也更难在多工具间选对。
 * - **写工具 = 一条工具 + 一个权限开关 + 一个执行器 + 一处埋点**，四件同轮齐。
 *   新增写工具前先自问：**这条写路径能否被用户撤销 / 覆盖？** 不可逆的（如整段
 *   覆盖自由文本）先不要开，见 [ProfileWriter] 类 KDoc 对 `user_background` 的处理。
 * - **只读工具只补"prompt 里没有的数据"**：画像 / 体格 / 目标 / 今日数字 / 隐私开关
 *   已经由 `ChatEngine.systemPrompt`（含 `ProfileContext.build`）注入，再挂一个
 *   `query_profile` 只会让同一段文本说两遍、白烧 token。判据写在此处，新增只读前先自问。
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

    // ── P0 只读补齐（2026-10-07，用户需求「全面提高 AI 能力」）────────────
    // 这 4 个工具填的是**真实盲区**：这些数据表此前对模型完全不可见，模型被问到
    // 只能答"我不知道"或凭记忆编。判定依据是"该数据是否已经出现在 system prompt 里"
    // —— 已经注入的（画像 / 体格 / 目标 / 今日数字 / 隐私开关）**不再补只读工具**，
    // 补了也只是把同一段文本说第二遍（见 [toolsSection] 的权限声明同理）。
    const val NAME_QUERY_REVIEW = "query_review"
    const val NAME_QUERY_WARNINGS = "query_warnings"
    const val NAME_QUERY_REMINDERS = "query_reminders"
    const val NAME_QUERY_SETTINGS = "query_settings"

    const val NAME_PROPOSE_LOG = "propose_log"
    const val NAME_PROPOSE_PLAN_CHANGE = "propose_plan_change"
    const val NAME_PROPOSE_GOAL_CHANGE = "propose_goal_change"
    const val NAME_PROPOSE_RECORD_DELETE = "propose_record_delete"

    // ── P1 写侧扩展（2026-10-07，同上需求）──────────────────────────────
    // 两把新写入口，都走「草稿 → 用户确认」铁律，各有独立权限开关。
    // 覆盖面在 [ProfileWriter] / [SettingsWriter] 的 FIELDS 里，此处不重复列举。
    const val NAME_PROPOSE_PROFILE_UPDATE = "propose_profile_update"
    const val NAME_PROPOSE_SETTINGS_UPDATE = "propose_settings_update"

    // ── P2 结构能力（2026-10-07，同上需求）──────────────────────────────
    // 提醒是「增删改三态」的表，与字段 allowlist 型 writer 不同构 → 独立工具 + 独立开关。
    // 计划条目的增删则扩 [NAME_PROPOSE_PLAN_CHANGE] 的 action 域（不新建工具名）。
    const val NAME_PROPOSE_REMINDER_CHANGE = "propose_reminder_change"

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

    /** `propose_reminder_change` 的动作域 = [ReminderWriter] 的三个 op（**同源引用，不复制字面量**）。 */
    private val REMINDER_ACTIONS = listOf(
        ReminderWriter.ACTION_ADD,
        ReminderWriter.ACTION_UPDATE,
        ReminderWriter.ACTION_DELETE,
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

    /**
     * `query_plan` 单次最多回多少条计划行（token 预算）。
     *
     * 2026-10-07 从 14 收到 7：`query_plan` 改为回**条目明细**后单行膨胀（每条计划最多
     * 6 条今日条目 + 一行明天摘要 + 备注 ≈ 8 行），14 天能到上百行、把上下文灌满。
     * 7 行正好覆盖「最近一周」这一最常见问法。
     */
    private const val MAX_PLAN_ROWS = 7

    /** `query_review` 单次最多回几条复盘行（同比 `query_plan` 的 token 预算）。 */
    private const val MAX_REVIEW_ROWS = 7

    /** `query_review` 单条复盘正文的截断长度。 */
    private const val MAX_REVIEW_CHARS = 200

    /** `query_warnings` 单次最多回几条身体提示。 */
    private const val MAX_WARNINGS = 20

    /** `query_reminders` 单次最多回几条提醒。 */
    private const val MAX_REMINDERS = 15

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
                description = "查询某日期区间内已生成的今日计划，返回**逐条明细**" +
                    "（时间 / 类型 / 标题 / 具体做法 / 时长 / 热量，另附备注与来源）。" +
                    "回答“我的计划是什么 / 某天安排了什么 / 几点该吃什么”必须用它查证，" +
                    "禁止凭对话记忆编造。",
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
                name = NAME_QUERY_REVIEW,
                description = "查询某日期区间内已生成的**每日复盘**（App 对那天的表现做的分析" +
                    "与建议，含模型名）。回答“我的复盘说了什么 / 上次建议我怎么做”用它查证，禁止凭记忆编。",
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
                name = NAME_QUERY_WARNINGS,
                description = "查询某日期区间内 App 自动给出的**身体提示**（如连续睡眠不足、" +
                    "体重异动、生病频次偏高等，分 提示/注意/警告 三档）。" +
                    "回答“我最近身体有什么异常 / 有没有被提醒过”用它查证，禁止凭记忆编。",
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
                name = NAME_QUERY_REMINDERS,
                description = "查询用户设置的**周期性提醒**（体检 / 洗牙 / 配镜 / 疫苗等）" +
                    "及其下次到期时间。回答“我下次该体检了吗 / 我设了哪些提醒”用它查证。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any?>(),
                ),
            ),
        ),
        ToolDef(
            function = ToolFunctionDef(
                name = NAME_QUERY_SETTINGS,
                description = "查询 App 的**运行设置与额度**：日界线（几点算新的一天）、" +
                    "隐私开关（是否隐藏热量 / 体重）、AI 可见资料范围、今日 AI 调用额度余量。" +
                    "只在用户明确问起这些时调用（如“我还能问几次 / 我的日界线是几点 / " +
                    "我把热量隐藏了吗”），不要主动播报额度。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any?>(),
                ),
            ),
        ),
        ToolDef(
            function = ToolFunctionDef(
                name = NAME_PROPOSE_LOG,
                description = "拟一条记录草稿。仅当用户明确要求把某事写进记录" +
                    "（如「记一下」「帮我记着」）时调用（raw_text 用用户的原话）；" +
                    "用户只是回忆提起不算（如「我记着上次帮我记过…」），" +
                    "消息只是提到「记录」二字但主体是提问或求建议时也禁止调用。" +
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
                description = "拟修改计划（草稿，需用户确认）：新增 / 删除计划条目、" +
                    "把某个条目改成休息、按字段修改某个条目、改或清空当日计划备注，" +
                    "也可把本周某天设为休息日。" +
                    "定位已有条目用 match_title（可加 match_time 消歧，命中必须唯一）。" +
                    "你无权直接修改计划。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "date" to mapOf(
                            "type" to "string",
                            "description" to "要修改的日期 yyyy-MM-dd（今日计划类用；set_training_rest 忽略）",
                        ),
                        "action" to mapOf(
                            "type" to "string",
                            "description" to "动作：add_item=新增一条条目；remove_item=删除一条条目；" +
                                "set_rest=把某条目改成休息；patch_item=按字段改某条目；" +
                                "set_note=改当日备注；clear_note=清空备注；" +
                                "set_training_rest=把本周某天设为休息日",
                        ),
                        "match_title" to mapOf(
                            "type" to "string",
                            "description" to "要改的条目：标题片段（子串、不区分大小写）。set_rest / patch_item 用",
                        ),
                        "match_time" to mapOf(
                            "type" to "string",
                            "description" to "可选：条目时间 HH:mm，同名条目消歧用。set_rest / patch_item / remove_item 用",
                        ),
                        "day" to mapOf(
                            "type" to "string",
                            "description" to "add_item 用：today=今天的细排条目（默认）；tomorrow=明天的时段锚点",
                        ),
                        "time" to mapOf(
                            "type" to "string",
                            "description" to "add_item（day=today）用：条目时刻 HH:mm 24 小时制",
                        ),
                        "slot" to mapOf(
                            "type" to "string",
                            "description" to "add_item（day=tomorrow）用：时段锚点，只能 morning / noon / evening / train",
                        ),
                        "patch" to mapOf(
                            "type" to "object",
                            "description" to "patch_item 的改动对象 / add_item 的新条目字段，" +
                                "只允许含 type/title/detail/kcal/duration/why" +
                                "（type 只能 meal/exercise/sleep/habit；add_item 必给 type 与 title）",
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
        ToolDef(
            function = ToolFunctionDef(
                name = NAME_PROPOSE_PROFILE_UPDATE,
                description = "拟改用户的画像 / 资源清单（草稿，需用户确认）。" +
                    "field 可选：allergens=忌口/过敏/不吃（op 用 add / remove 改单条）；" +
                    "pain=疼痛/不适部位（add / remove）；" +
                    "scene=就餐场景（set，只能是 宿舍 / 食堂 / 外卖 / 自己做饭，或留空表示未固定）；" +
                    "foods=手头现成的食物（set，自由文本）；" +
                    "meds=常备药物（set，自由文本）；" +
                    "sport=运动条件（set，自由文本，器材 + 场地 + 可用时段）；" +
                    "sleep_bed=就寝时间（set，HH:mm）；sleep_wake=起床时间（set，HH:mm）。" +
                    "仅当用户明确要求改这些偏好 / 条件时调用（如「我以后不吃辣」「我只有一副哑铃」" +
                    "「我一般 1 点睡」）；用户只是提到相关事实、并未要求改的，不要调用。" +
                    "草稿经用户确认后才生效，你无权直接修改。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "field" to mapOf(
                            "type" to "string",
                            "description" to "要改的字段：allergens / pain / scene / foods / " +
                                "meds / sport / sleep_bed / sleep_wake",
                        ),
                        "op" to mapOf(
                            "type" to "string",
                            "description" to "动作：add=加入一项；remove=移除一项；" +
                                "set=整体覆盖（scene / foods / meds / sport / sleep_bed / sleep_wake 用 set）",
                        ),
                        "value" to mapOf(
                            "type" to "string",
                            "description" to "值：数组类字段填单个条目（如「不吃辣」）；" +
                                "sleep_* 填 HH:mm（如 00:30）；其余填文本",
                        ),
                    ),
                    "required" to listOf("field", "op", "value"),
                ),
            ),
        ),
        ToolDef(
            function = ToolFunctionDef(
                name = NAME_PROPOSE_SETTINGS_UPDATE,
                description = "拟改体格与运行设置（草稿，需用户确认）。" +
                    "field 可选：height=身高（整数 cm）；weight=体重（kg）；age=年龄（整数岁）；" +
                    "activity=活动系数（只能是 1.2 / 1.375 / 1.55 / 1.725）；" +
                    "day_start=日界线（0-23 的整数小时，凌晨几点之前的记录算前一天）；" +
                    "hide_kcal=是否隐藏热量数字（true / false）；" +
                    "hide_weight=是否隐藏体重数字（true / false）。" +
                    "仅当用户明确要求改这些时调用（如「我 175 了」「我最近 70 公斤」" +
                    "「把日界线改成 3 点」「帮我把热量藏起来」）。" +
                    "草稿经用户确认后才生效，你无权直接修改。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "field" to mapOf(
                            "type" to "string",
                            "description" to "要改的字段：height / weight / age / activity / " +
                                "day_start / hide_kcal / hide_weight",
                        ),
                        "value" to mapOf(
                            "type" to "string",
                            "description" to "新值（数字写数字，开关写 true / false）",
                        ),
                    ),
                    "required" to listOf("field", "value"),
                ),
            ),
        ),
        ToolDef(
            function = ToolFunctionDef(
                name = NAME_PROPOSE_REMINDER_CHANGE,
                description = "拟增 / 改 / 删一条周期性提醒（草稿，需用户确认）：" +
                    "体检 / 洗牙 / 配镜 / 疫苗这类「每隔一段时间做一次」的事。" +
                    "action=add 给 name + interval_days（可选 last_done）；" +
                    "action=update 给 match_name 定位，再给要改的 name / interval_days / last_done；" +
                    "action=delete 给 match_name 定位。" +
                    "定位用 match_name（名字子串，命中必须唯一）。" +
                    "仅当用户明确要求加 / 改 / 删提醒时调用。" +
                    "草稿经用户确认后才生效，你无权直接修改。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "action" to mapOf(
                            "type" to "string",
                            "description" to "动作：add=新增；update=修改；delete=删除",
                        ),
                        "name" to mapOf(
                            "type" to "string",
                            "description" to "add 的新提醒名字 / update 的新名字（改名时才给）",
                        ),
                        "match_name" to mapOf(
                            "type" to "string",
                            "description" to "update / delete 要定位的提醒名字片段（子串、不区分大小写）",
                        ),
                        "interval_days" to mapOf(
                            "type" to "integer",
                            "description" to "周期天数（add 必给；update 改周期时才给）",
                        ),
                        "last_done" to mapOf(
                            "type" to "string",
                            "description" to "可选：上次做这件事的日期 yyyy-MM-dd（给了才按它起算下次到期）",
                        ),
                    ),
                    "required" to listOf("action"),
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

            NAME_QUERY_REVIEW -> ok(uid, queryReview(context, args))
            NAME_QUERY_WARNINGS -> ok(uid, queryWarnings(context, args))
            NAME_QUERY_REMINDERS -> ok(uid, queryReminders(context))
            NAME_QUERY_SETTINGS -> ok(uid, querySettings(context))

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

            NAME_PROPOSE_PROFILE_UPDATE -> if (!perms.writeProfile) {
                denied(uid)
            } else {
                proposeProfileUpdate(context, args, uid)
            }

            NAME_PROPOSE_SETTINGS_UPDATE -> if (!perms.writeSettings) {
                denied(uid)
            } else {
                proposeSettingsUpdate(context, args, uid)
            }

            NAME_PROPOSE_REMINDER_CHANGE -> if (!perms.writeReminder) {
                denied(uid)
            } else {
                proposeReminderChange(context, args, uid)
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

    /**
     * 查今日计划 —— **回条目明细**（2026-10-07 P0 修掉的硬伤）。
     *
     * 旧版只回 `planNoteOf` 的一句备注：模型拿不到任何条目，「我 12 点该吃什么」只能凭
     * 对话记忆编 —— 与「回答里引用的数字只能来自记录原文或工具返回」直接冲突。
     * 明细由 [PlanGenerator.itemsDigest] 产出（`plan_json` 的 schema 只在那里定义，
     * 摘要必须同源，理由见该方法 KDoc）。
     *
     * 三级回落（旧数据 / 纯兜底文本也仍要答得出来）：
     * 条目明细 → `note` 一句话 → `content` 纯文本前 60 字 → "无备注"。
     */
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
        // 摘要产出器：构造失败也不出声，直接走下面的回落链（防御式，与全文件同风格）
        val digest = runCatching { PlanGenerator(context) }.getOrNull()
        val lines = shown.map { p ->
            val src = if (p.source == TrainingPlanner.SOURCE_AI) "AI" else "本地"
            val detail = runCatching { digest?.itemsDigest(p.planJson) }.getOrNull()
                ?: planNoteOf(p.planJson)
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

    /**
     * 查每日复盘（`daily_reviews`）—— 2026-10-07 P0 新增，此前该表对模型**完全不可见**。
     *
     * 复盘是 App 对"那天为什么没达标 / 哪里做得好"的成篇分析，用户回问"你上次建议我
     * 怎样来着"是最常见的追问之一；不给工具就只能答"我看不到"。
     */
    private suspend fun queryReview(context: Context, args: JSONObject): String {
        val from = args.optString("day_from").trim()
        val to = args.optString("day_to").trim()
        if (!DATE_PATTERN.matches(from) || !DATE_PATTERN.matches(to)) {
            return "day_from / day_to 必须是 yyyy-MM-dd 格式。"
        }
        val db = HealixApp.from(context).database
        val rows = try {
            db.planDao().listReviewsInRange(from, to)
        } catch (_: Exception) {
            return "查询失败，请稍后再试。"
        }
        if (rows.isEmpty()) return "该区间还没有生成过复盘。"
        val shown = rows.takeLast(MAX_REVIEW_ROWS)
        val lines = shown.map { r ->
            val text = r.content.orEmpty().replace('\n', ' ').trim()
            val body = if (text.length > MAX_REVIEW_CHARS) {
                text.take(MAX_REVIEW_CHARS) + "…"
            } else {
                text.ifEmpty { "（空）" }
            }
            "• ${r.date}：$body"
        }
        val truncated = if (rows.size > shown.size) {
            "\n（仅显示最近 ${shown.size} 条，共 ${rows.size} 条）"
        } else {
            ""
        }
        return lines.joinToString("\n") + truncated
    }

    /**
     * 查身体提示（`body_signals`）—— 2026-10-07 P0 新增，此前该表对模型**完全不可见**。
     *
     * ⚠️ 工具只**复述**规则算出来的提示，不做任何医学判断 —— 措辞与 App 内一致
     * （"App 给你的提示"），避免模型把这些提示当成诊断结论往外说。
     */
    private suspend fun queryWarnings(context: Context, args: JSONObject): String {
        val from = args.optString("day_from").trim()
        val to = args.optString("day_to").trim()
        if (!DATE_PATTERN.matches(from) || !DATE_PATTERN.matches(to)) {
            return "day_from / day_to 必须是 yyyy-MM-dd 格式。"
        }
        val db = HealixApp.from(context).database
        val rows = try {
            db.bodySignalDao().listInRange(from, to, MAX_WARNINGS)
        } catch (_: Exception) {
            return "查询失败，请稍后再试。"
        }
        if (rows.isEmpty()) return "该区间没有身体提示记录。"
        return rows.joinToString("\n") { s ->
            val level = signalLevelName(s.level)
            val ack = if (s.acknowledged == 1) "，用户已看过" else ""
            val detail = s.detail.orEmpty().replace('\n', ' ').trim()
            val body = if (detail.isEmpty()) s.title else "${s.title}：$detail"
            "• ${s.dayKey} [$level$ack] $body"
        }
    }

    /** 查周期性提醒（`reminders`）—— 2026-10-07 P0 新增，此前该表对模型**完全不可见**。 */
    private suspend fun queryReminders(context: Context): String {
        val db = HealixApp.from(context).database
        val rows = try {
            db.reminderDao().listEnabled()
        } catch (_: Exception) {
            return "查询失败，请稍后再试。"
        }
        if (rows.isEmpty()) return "还没有设置周期性提醒。"
        val now = System.currentTimeMillis()
        return rows.take(MAX_REMINDERS).joinToString("\n") { r ->
            val name = r.name.trim().ifEmpty { "（未命名）" }
            "• $name（每 ${r.intervalDays} 天，下次 ${reminderDueText(r.nextDueAt, now)}）"
        }
    }

    /**
     * 查运行设置与额度（`settings` + [QuotaGuard]）—— 2026-10-07 P0 新增。
     *
     * 刻意**只回非敏感项**：apiKey 根本不落 `settings` 表（`SecretStore` 存
     * EncryptedSharedPreferences），故这里逐键读也不会带出密钥。新增键前先自问
     * "这条会不会随导出/回显泄露"（同 [SettingsKeys] 的导出纪律）。
     */
    private suspend fun querySettings(context: Context): String {
        val db = HealixApp.from(context).database
        val dao = db.settingsDao()
        val lines = mutableListOf<String>()
        val dayStart = runCatching {
            dayStartHourOf(dao.get(EventRepository.KEY_DAY_START_HOUR))
        }.getOrDefault(0)
        lines += "• 日界线：每天 $dayStart:00 之后才算新的一天（此前记的算前一天）"
        lines += "• 隐藏热量数字：${boolText(dao.get(SettingsKeys.HIDE_KCAL) == "true")}"
        lines += "• 隐藏体重数字：${boolText(dao.get(SettingsKeys.HIDE_WEIGHT) == "true")}"
        lines += "• AI 可见资料范围（画像 / 体格 / 目标）：" +
            boolText(dao.get(SettingsKeys.AI_DATA_FULL) != "false")
        lines += "• AI 工具总开关：${boolText(dao.get(SettingsKeys.AI_TOOLS_ENABLED) != "false")}"
        val quota = runCatching { QuotaGuard(context) }.getOrNull()
        if (quota != null) {
            val chatUsed = runCatching { quota.usedChatToday() }.getOrDefault(0)
            val chatLeft = (QuotaGuard.DEFAULT_DAILY_CHAT_LIMIT - chatUsed).coerceAtLeast(0)
            val extractUsed = runCatching { quota.usedExtractToday() }.getOrDefault(0)
            val left = (QuotaGuard.DEFAULT_DAILY_CALL_LIMIT - extractUsed).coerceAtLeast(0)
            lines += "• 今日对话额度：已用 $chatUsed/${QuotaGuard.DEFAULT_DAILY_CHAT_LIMIT}" +
                "，还剩 $chatLeft 次"
            lines += "• 今日计划 / 记录识别额度：已用 $extractUsed/" +
                "${QuotaGuard.DEFAULT_DAILY_CALL_LIMIT}，还剩 $left 次"
        }
        return lines.joinToString("\n")
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

            PlanChangeWriter.OP_ADD_ITEM -> {
                op.put("op", PlanChangeWriter.OP_ADD_ITEM)
                op.put("day", args.optString("day").trim())
                op.put("time", args.optString("time").trim())
                op.put("slot", args.optString("slot").trim())
                val patch = args.optJSONObject("patch")
                    ?: return ToolExecResult("add_item 需要给出 patch（条目字段）。", null, uid, true)
                op.put("patch", patch)
            }

            PlanChangeWriter.OP_REMOVE_ITEM -> {
                op.put("op", PlanChangeWriter.OP_REMOVE_ITEM)
                op.put("match_title", args.optString("match_title").trim())
                op.put("match_time", args.optString("match_time").trim())
            }

            PlanChangeWriter.OP_SET_TRAINING_REST -> {
                op.put("op", PlanChangeWriter.OP_SET_TRAINING_REST)
                op.put("weekday", args.optInt("weekday", 0))
            }

            else -> return ToolExecResult(
                "action 不合法，可选：add_item / remove_item / set_rest / patch_item / " +
                    "set_note / clear_note / set_training_rest。",
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

    /**
     * 拟改画像 / 资源清单（`propose_profile_update`，2026-10-07 P1）。
     *
     * 与 [proposePlanChange] 同款两段式：这里**只读库校验、不写库** —— 把
     * `{field, op, value}` 交给 [ProfileWriter.describe] 归一化 + 产出摘要；校验不过
     * （未知字段 / op 不匹配 / 越界 / 重复 add）把错误文本回给模型，循环可继续、不产 draft。
     * 真正落库推迟到用户确认后的 [ProfileWriter.apply]（届时重新读库）。
     */
    private suspend fun proposeProfileUpdate(
        context: Context,
        args: JSONObject,
        uid: String,
    ): ToolExecResult {
        val opJson = JSONObject()
            .put("field", strArg(args, "field"))
            .put("op", strArg(args, "op"))
            .put("value", strArg(args, "value"))
            .toString()
        val db = HealixApp.from(context).database
        return when (val r = ProfileWriter.describe(context, db, opJson)) {
            is ProfileWriter.Result.Ok -> draft(
                uid,
                "已拟好画像修改草稿：${r.summary}。等待用户确认。",
                ProfileUpdateProposal(op = opJson, callUid = uid, summary = r.summary),
            )

            is ProfileWriter.Result.Error -> ToolExecResult(r.message, null, uid, true)
        }
    }

    /**
     * 拟改体格与运行设置（`propose_settings_update`，2026-10-07 P1）。
     *
     * 同 [proposeProfileUpdate]：拟稿期只校验、不写库；确认期由
     * [SettingsWriter.apply] 落库。
     */
    private suspend fun proposeSettingsUpdate(
        context: Context,
        args: JSONObject,
        uid: String,
    ): ToolExecResult {
        val opJson = JSONObject()
            .put("field", strArg(args, "field"))
            .put("value", strArg(args, "value"))
            .toString()
        val db = HealixApp.from(context).database
        return when (val r = SettingsWriter.describe(context, db, opJson)) {
            is SettingsWriter.Result.Ok -> draft(
                uid,
                "已拟好设置修改草稿：${r.summary}。等待用户确认。",
                SettingsUpdateProposal(op = opJson, callUid = uid, summary = r.summary),
            )

            is SettingsWriter.Result.Error -> ToolExecResult(r.message, null, uid, true)
        }
    }

    /**
     * 拟一条提醒改动草稿（`propose_reminder_change`，2026-10-07 P2）。
     *
     * 与其余拟稿工具同构：**只校验、不写库**（[ReminderWriter.describe]），
     * 定位不到 / 非法周期 / 撞名都在这里拦下、原样回错误文本；真正落库推迟到用户确认后的
     * [ReminderWriter.apply]。
     *
     * `action` 与 [ReminderWriter] 的三个 op **同名同值**（`add` / `update` / `delete`），
     * 此处直接透传、不另设映射表 —— 两层名字一旦分叉，就会出现"模型说 add、writer 认 add_"
     * 这类只有端到端才暴露的错。
     */
    private suspend fun proposeReminderChange(
        context: Context,
        args: JSONObject,
        uid: String,
    ): ToolExecResult {
        val action = strArg(args, "action")
        if (action !in REMINDER_ACTIONS) {
            return ToolExecResult(
                "action 不合法，可选：${REMINDER_ACTIONS.joinToString(" / ")}。",
                null, uid, true,
            )
        }
        val op = JSONObject().put("op", action)
        // 只在**模型真的给了**这个参数时才写入载荷 —— `has` 判定与 `ReminderWriter.update`
        // 的「只有变了才重算到期日」是同一个契约（缺参 ≠ 传空串）。
        if (args.has("name")) op.put("name", strArg(args, "name"))
        if (args.has("match_name")) op.put("match_name", strArg(args, "match_name"))
        if (args.has("interval_days")) op.put("interval_days", args.optInt("interval_days", 0))
        if (args.has("last_done")) op.put("last_done", strArg(args, "last_done"))

        val db = HealixApp.from(context).database
        val opJson = op.toString()
        return when (val r = ReminderWriter.describe(db, opJson)) {
            is ReminderWriter.Result.Ok -> draft(
                uid,
                "已拟好提醒改动草稿：${r.summary}。等待用户确认。",
                ReminderChangeProposal(op = opJson, callUid = uid, summary = r.summary),
            )

            is ReminderWriter.Result.Error -> ToolExecResult(r.message, null, uid, true)
        }
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    /**
     * 取一个字符串参数，**JSON null 守卫**。
     *
     * `JSONObject.optString` 会把 JSON `null` 读成**字面串 `"null"`**（项目里
     * `PlanChangeWriter.sanitizePatch` 已踩过同一个坑）—— 那会让"值为空"被当成
     * 合法文本写库。此处显式把 `null` 归一成空串，由各字段的校验决定接受与否。
     */
    private fun strArg(args: JSONObject, key: String): String {
        val v = args.opt(key) ?: return ""
        if (v == JSONObject.NULL) return ""
        return v.toString().trim()
    }

    /** `body_signals.level` 的中文档位名（取值域 `info` / `notice` / `alert`）。 */
    private fun signalLevelName(level: String): String = when (level) {
        "info" -> "提示"
        "notice" -> "注意"
        "alert" -> "警告"
        else -> "提示"
    }

    /**
     * 提醒到期文案（`query_reminders` 用）。
     *
     * ⚠️ **刻意按时间戳算、不按 day_key** —— 与设置页 / 状态页的既有口径一致
     * （`SettingsFragment` 的临期判定是 `nextDueAt - now <= 7 天`，标签用系统时区日历日）。
     * 提醒的"到期"是**时刻**概念、不是**记录日**概念；套日界线会让"凌晨 2 点创建的提醒"
     * 比 UI 上显示的日期差一天（两条口径打架，且只在凌晨窗口暴露）。
     *
     * 一天的毫秒数取 [HealixDate.DAY_MS]（全仓唯一来源，不得再写 `86_400_000` 字面量）。
     */
    private fun reminderDueText(nextDueAt: Long, now: Long): String {
        val date = runCatching {
            Instant.ofEpochMilli(nextDueAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        }.getOrDefault("")
        val diff = nextDueAt - now
        if (diff <= 0L) return if (date.isEmpty()) "已到期" else "$date（已到期）"
        // 向上取整：还有 1 小时也算 1 天，不显示"还有 0 天"
        val days = (diff + HealixDate.DAY_MS - 1) / HealixDate.DAY_MS
        val tail = if (days <= 1L) "即将到期" else "还有 $days 天"
        return if (date.isEmpty()) tail else "$date（$tail）"
    }

    /** 布尔设置 → 「开」/「关」（回给模型时省字数、不留 `true/false` 的歧义）。 */
    private fun boolText(on: Boolean): String = if (on) "开" else "关"

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
                            proposalNote(context)
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
                    if (perms.writeProfile) add("改画像 / 资源清单")
                    if (perms.writeSettings) add("改体格与设置")
                    if (perms.writeReminder) add("增减提醒")
                }
                if (writeParts.isEmpty()) {
                    "你当前只有只读权限（可查询记录 / 计划 / 目标 / 复盘 / 提醒 / 设置等数据），" +
                        "不能修改任何数据。"
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
         * 工具说明段（追加在系统提示之后；非冻结区）。
         *
         * 2026-10-07 用户实测反馈：一段话里只要含「记录」二字就只触发记录工具、
         * 其余问题不回答 → 原版缺**意图判据**（「提到记录 ≠ 要记东西」「提问/建议/
         * 闲聊不调工具」「混合意图须在正文完整回答其余问题」），且 propose_log 的
         * 「用户让你记东西时调用」被模型泛化成「提到记录就调用」。
         * 现版在工具清单前插入意图判据段。
         *
         * 2026-10-07 P0 补齐（用户需求「全面提高 AI 能力」）：新增
         * query_review / query_warnings / query_reminders / query_settings 四个只读工具，
         * 并把 query_plan 一条改写为"逐条明细"。**意图判据段与收尾硬措辞逐字未动**
         * —— 新增的仍是"查用户自己的数据"，落在既有第 4 条查证义务的射程内，无需改判据。
         *
         * 2026-10-07 P1 扩展：新增 propose_profile_update / propose_settings_update 两条
         * **拟稿**工具。它们落在既有第 1 条「要改数据」的射程内，故意图判据段仍逐字未动，
         * 只在各自条目里写清"仅当用户**明确要求改**时才调，只是提到事实不算"——
         * 这与 propose_log 条目当年的修正同因（当时的教训：提到「记录」二字就乱调）。
         * 2026-10-07 P2 扩展：`propose_plan_change` 的 `action` 从 5 个扩到 7 个
         * （加 `add_item` / `remove_item`），并新增 `propose_reminder_change` 一条拟稿工具。
         * 前者仍是**同一条工具**（只改条目清单那一行与工具 schema）；后者是独立工具
         * （提醒是增删改三态，与字段 allowlist 型 writer 不同构）。意图判据段依旧逐字未动。
         * 末尾由 [toolsSection] 追加一行动态权限声明。
         */
        private const val TOOLS_SECTION = """

工具按**意图**判断，禁止按关键词判断：
- 只有当用户明确表达「要记录 / 要改数据 / 要查自己的记录数据」的意图时才调工具。
- 消息里出现「记录」「计划」「目标」等词，但主体是问**一般性知识**、求建议、闲聊或聊知识时，直接回答，禁止调用任何工具（例：「睡眠不好怎么改善」「有什么增肌训练推荐」都不调工具）。**问用户自己记录数据的问题不在此列——按第 4 条必须先查工具**（例：「我的计划是什么」要查，不算禁调的提问）。
- 混合意图（同一段话既让记东西 / 改数据、又问了别的问题）：照常调用拟稿工具，并在**同一条回复正文**里完整回答其余问题——正文 = 回答全部问题 + 一句拟稿说明，禁止只回「好的帮你记下了」。这是硬要求：拟稿发出后本轮即结束，这段正文是用户唯一能看到的回复。
- 查证义务只针对用户自己的记录数据：问「我那天吃了什么/练了什么/我的计划是什么」这类才必须先查工具；一般性知识、通用建议不需要查证也不需要工具。

可用工具（不必每轮都用，已有信息足够就直接回答）：
- query_events：查某日期区间的记录原文。回答"我那天吃了什么/练了什么"必须先查证，禁止凭对话记忆编。
- query_stats：重新取今日摘要数字。今日数字**不再**注入背景，需要当日摄入/消耗等数字时调它。
- query_plan：查某日期区间已生成的今日计划 —— **逐条明细**（时间 / 类型 / 标题 / 具体做法 / 时长 / 热量，另附备注与来源）。回答"我几点该吃什么 / 计划安排了什么"必须先查证。
- query_goal：查当前生效的目标。
- query_training_week：查本周训练安排与完成情况。
- query_review：查某日期区间的每日复盘内容（App 对那天的分析与建议）。
- query_warnings：查某日期区间 App 给出的身体提示（睡眠 / 体重 / 生病等异常，分 提示 / 注意 / 警告 三档）。只复述 App 的提示，不作医学判断。
- query_reminders：查周期性提醒（体检 / 洗牙 / 配镜 / 疫苗）与下次到期时间。
- query_settings：查运行设置与额度（日界线 / 隐私开关 / AI 可见资料范围 / 今日调用额度余量）。只在用户明确问起时调，不要主动播报额度。
- propose_log：用户让你记东西时，用用户原话拟一条草稿。草稿经用户确认后才会写入，你无权直接写入记录。
- propose_plan_change：拟改计划（date + action）：新增条目（add_item，day=today 给 time、day=tomorrow 给 slot）/ 删除条目（remove_item）/ 把某条目改成休息（set_rest）/ 按字段改某条目（patch_item）/ 改或清备注（set_note、clear_note）/ 把本周某天设为休息日（set_training_rest）。定位已有条目用 match_title（必要时加 match_time，命中须唯一）。草稿经用户确认后才生效。
- propose_goal_change：拟改某项目标值（metric + value）。草稿经用户确认后才生效。
- propose_record_delete：拟删一条记录（day + keyword，命中须唯一）。草稿经用户确认后才删除。
- propose_profile_update：拟改画像 / 资源清单。field 取 allergens（忌口过敏，op=add/remove）/ pain（疼痛部位，op=add/remove）/ scene（就餐场景，op=set）/ foods（手头食物，op=set）/ meds（常备药物，op=set）/ sport（运动条件，op=set）/ sleep_bed、sleep_wake（作息时刻，op=set 且值写 HH:mm）。用户明确要求改这些偏好 / 条件时才调。草稿经用户确认后才生效。
- propose_settings_update：拟改体格与运行设置。field 取 height（cm 整数）/ weight（kg）/ age（整数岁）/ activity（只能 1.2 / 1.375 / 1.55 / 1.725）/ day_start（0-23 整数小时）/ hide_kcal、hide_weight（true / false）。用户明确要求改时才调。草稿经用户确认后才生效。
- propose_reminder_change：拟增 / 改 / 删一条周期性提醒（体检 / 洗牙 / 配镜 / 疫苗）。action=add 给 name + interval_days（可选 last_done）；action=update / delete 先给 match_name 定位（名字子串，命中须唯一），update 再给要改的 name / interval_days / last_done。用户明确要求加 / 改 / 删提醒时才调。草稿经用户确认后才生效。
回答里引用的数字只能来自记录原文或工具返回。
"""

        /**
         * 拟稿确认语轮换池（W1，2026-10-07 用户反馈：连续多条逐字相同显"机械感"）。
         * 池序即轮换序；[com.healix.app.R.string.proposal_note_default] 恒为第一条。
         * 文案保持第一人称、口语、长度近似，与既有人设一致。
         */
        private val PROPOSAL_NOTE_POOL = listOf(
            com.healix.app.R.string.proposal_note_default,
            com.healix.app.R.string.proposal_note_alt_1,
            com.healix.app.R.string.proposal_note_alt_2,
            com.healix.app.R.string.proposal_note_alt_3,
        )

        /** 上一次选中的池下标（进程级）：用于确定性去重，保证连续两条不撞同一文案。 */
        @Volatile
        private var lastNoteIdx: Int = -1

        /**
         * 取一条拟稿确认语（模型未给随附说明时的兜底文案）。
         *
         * **确定性依据（无随机）**：取模种子 = 拟稿产生时刻 `System.currentTimeMillis()`
         * （即这条 assistant 消息的 createdAt 口径）。文案随 [AgentOutcome.ProposalPending]
         * 落库一次，之后重进页面读库渲染，**同一条消息的文案必然稳定**；进程级
         * `lastNoteIdx` 去重只保证"连续两条不撞同一句"，不引入任何随机源。
         */
        fun proposalNote(context: Context): String {
            var idx = (System.currentTimeMillis() % PROPOSAL_NOTE_POOL.size).toInt()
            if (idx == lastNoteIdx) idx = (idx + 1) % PROPOSAL_NOTE_POOL.size
            lastNoteIdx = idx
            return context.getString(PROPOSAL_NOTE_POOL[idx])
        }
    }
}
