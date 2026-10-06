package com.healix.app.db

/**
 * settings 表的**唯一键名事实来源**。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么必须集中在这里
 * ══════════════════════════════════════════════════════════════════════════
 * 2026-10-03 发现一个**编译期完全不可见**的严重 bug：
 * 设置页（写端）用 `base_url` / `model` / `retry_max` / `daily_quota`，
 * 而事件仓库（读端）用 `provider_base_url` / `provider_model` /
 * `retry_max_retries` / `quota_daily_call_limit`。
 *
 * 两边都是**合法字符串字面量**，kotlinc 不会报任何错，KSP 不会报任何错，
 * 单测也不会覆盖到 —— 唯一的表现是：
 *
 *   1. 设置页填好 base_url + model + API Key，界面回显正常
 *   2. 点「测试连通性」**能过** —— 因为 testConnectivity 走的是
 *      SettingsViewModel 自己的 SettingsValues（与写端同名），发的是真请求
 *   3. 但用户真正「记一笔」时，EventRepository.loadProviderConfig() 读
 *      `provider_base_url` 得到 null → 直接 markFailed("provider_not_configured")
 *      → **一次 HTTP 请求都没有发出去**
 *
 * 「测试能过、实际不能用」是全项目最难排查的一类误导 —— 因此根因不只是
 * 修四个字符串，而是把键名收敛到**一处定义**，让"两边不一致"在结构上不可能发生。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 命名约定（向后兼容用）
 * ══════════════════════════════════════════════════════════════════════════
 * 这里采用**旧设置页的短名**（`base_url` / `model`）作为正式名，因为它已经被
 * 用户的设备写进 SQLite 了 —— 换成长名等于让所有已安装用户的现有配置失效。
 * 读端改过来即可，代价为零。
 *
 * ⚠️ 新增任何 settings 键，都必须在这里加常量，并且**禁止**在任何其它文件里
 * 写裸字符串字面量。`pipeline/check_kotlin.py` 的 `check_settings_keys()`
 * 会在 CI 上强制这条约束。
 */
object SettingsKeys {

    // ── 模型服务 ──────────────────────────────────────────────────────
    /** 接口地址。例：`https://open.bigmodel.cn/api/paas/v4` */
    const val BASE_URL = "base_url"

    /** 模型名。例：`glm-4-flash` */
    const val MODEL = "model"

    /**
     * 服务商**预设 key**（不是展示名）。
     * 取值 ∈ {zhipu, deepseek, openrouter, siliconflow, custom}。
     * 埋点与预设回显用；不参与请求构造（请求只用 baseUrl/model/key）。
     */
    const val PROVIDER = "provider"

    // ── 调用限制（已停用，留档）─────────────────────────────────────
    /**
     * ⚠️ 配额口径改造（2026-10-05）：每日上限改为 QuotaGuard 内置常量
     * （DEFAULT_DAILY_CALL_LIMIT = 20 / DEFAULT_DAILY_CHAT_LIMIT = 15），
     * 不再可配置，这两个键**不再被读取或写入** —— 仅作历史数据留档
     * （老用户 settings 表里可能还有这两行，导出备份兼容旧文件）。
     * 设置页对应两行改为只读展示今日实际调用量。
     */
    const val EXTRACT_QUOTA = "daily_quota"

    /** 同上（已停用，留档）。 */
    const val CHAT_QUOTA = "chat_quota"

    /** 抽取链最大重试次数（不含首次）。 */
    const val RETRY = "retry_max"

    /** 退避初始间隔（秒）。指数退避时为 1.5 × 2^(n-1)。 */
    const val RETRY_DELAY = "retry_base_seconds"

    /**
     * 是否启用指数退避。`"true"` / `"false"`。
     *
     * 当前设置页**没有暴露这个开关**，因此该键通常不存在 → 回落 `true`。
     * 保留声明是为了让 `check_settings_keys()` 的引用检查能过，
     * 也为了将来加开关时不必再动读端。
     */
    const val RETRY_EXP_BACKOFF = "retry_exponential_backoff"

    // ── 个人 ─────────────────────────────────────────────────────────
    const val HEIGHT = "height_cm"
    const val WEIGHT = "weight_kg"
    const val AGE = "age"
    const val ACTIVITY = "activity_factor"
    const val TARGET_KCAL = "target_kcal"

    /** 日界线小时。凌晨在此之前的记录算作前一天（默认 4:00）。 */
    const val DAY_START = "day_start_hour"

    /** 用户背景自由文本（「我的情况」）。空 = 未填写，prompt 走无背景路径。 */
    const val BACKGROUND = "user_background"

    // ── 结构化画像（功能清单 2 F6；全部落 settings 表，不建 profile 表）───
    /**
     * 标签类画像值统一存 **JSON 数组字符串**（如 `["乳糖不耐","不吃香菜"]`），
     * 标量类存原样字符串。空数组 `[]` / 空串 = 未填写，注入 prompt 时整段省略。
     * 解析端一律防御性解析（解析失败按空处理，不抛异常）。
     */
    /** 忌口 / 过敏 / 不吃（JSON 数组）。硬约束段：饮食建议必须绕开。 */
    const val PROFILE_ALLERGENS = "profile_allergens"

    /** 疼痛 / 不适部位（JSON 数组）。硬约束段：运动建议必须避开（F9 硬规则）。 */
    const val PROFILE_PAIN = "profile_pain"

    /** 就餐场景（标量：宿舍 / 食堂 / 外卖 / 自己做饭）。软背景段。 */
    const val PROFILE_SCENE = "profile_scene"

    /** 可用器材（JSON 数组）。已被 PROFILE_SPORT（自由文本）取代 ——
     *  仅作为旧数据迁移兜底保留：sport 为空时读它（ResourceStore.sport）。
     *  禁止在新 UI 上再写这个 key。 */
    const val PROFILE_GEAR = "profile_gear"

    // ── 资源清单（白板式手动声明，AI 自动读取）─────────────────────────
    /**
     * 三类均为**自由文本**（多行，想到什么写什么）—— 与画像的标签形态不同：
     * 手头清单的本质是"随手补"，结构化反而没人填。
     * 空串 = 未填写，注入 prompt 时整段省略。
     */
    /** 手头现成的食物（推荐池，优先级高于 F7 自动常吃池）。 */
    const val PROFILE_FOODS = "profile_foods"

    /** 常备药物（仅作事实参考；AI 行为边界由 ChatEngine 规则 10 承担）。 */
    const val PROFILE_MEDS = "profile_meds"

    /** 运动条件（器材 + 场地 + 可用时段，取代原「可用器材」行）。 */
    const val PROFILE_SPORT = "profile_sport"

    /** 就寝时间（标量 `HH:mm`）。软背景段。 */
    const val PROFILE_SLEEP_BED = "profile_sleep_bed"

    /** 起床时间（标量 `HH:mm`）。软背景段。 */
    const val PROFILE_SLEEP_WAKE = "profile_sleep_wake"

    // ── 隐私（设计规范系统 9.7 ④） ────────────────────────────────────
    /**
     * 隐藏热量数字。`"true"` / `"false"`，默认 `false`（不隐藏）。
     *
     * ⚠️ 三处必须同时生效：首页汇总区 / 状态详情页 / 对话系统提示。
     * 特别是**系统提示也要去掉 kcal** —— 否则 AI 会在回复里把数字说出来，
     * 隐私开关等于白做（PRD 14.3 明确点出这个坑）。
     *
     * 隐藏时**整块不显示**，不显示 `***`、不做「已隐藏」提示（PRD 14.3）。
     */
    const val HIDE_KCAL = "hide_kcal"

    /** 隐藏体重数字。同上，默认 `false`。 */
    const val HIDE_WEIGHT = "hide_weight"

    /** AI 可见资料范围总开关。"true"/"false"；键不存在 = "true"（默认开）。
     *  false = 三条 AI 链路只用记录数据（今日数字/历史记录/工具），画像/体格/
     *  目标组/次目标/计划段全部不注入。hide_kcal/hide_weight 不受影响。
     *  判定口径全仓唯一：`!= "false"`（读点 ProfileContext.aiDataFull）。 */
    const val AI_DATA_FULL = "ai_data_full"

    // ── AI 工具权限（v0.3 B5/B6，D4：全部默认开）────────────────────────
    /**
     * AI 工具总开关。`"true"` / `"false"`；**键不存在 = "true"（默认开）**。
     *
     * 关 = `ChatViewModel` 在调 `HealthAgent` **之前**判定，直接走 [ui.ChatEngine.reply]
     * 单轮（跳过 agent）—— 因此既不会调用任何工具，也不会写 `tool_calls` 行。
     * 与"降级链第二级"同一出口。判定口径：`!= "false"`。
     */
    const val AI_TOOLS_ENABLED = "ai_tools_enabled"

    /**
     * 写工具权限：**拟改今日计划**（`propose_plan_change`）。默认开（键不存在 = 开）。
     *
     * 仅控制 Agent **执行层**是否能产出该 draft（纵深防御的第二道，见
     * `HealthAgent.ToolPermissions`）；即使开着，用户仍需在 UI 二次确认后才落库。
     * 判定口径：`!= "false"`。
     */
    const val AI_TOOL_WRITE_PLAN = "ai_tool_write_plan"

    /**
     * 写工具权限：**拟记 / 删记录**（`propose_record_delete`）。默认开。
     * 判定口径：`!= "false"`。
     */
    const val AI_TOOL_WRITE_RECORD = "ai_tool_write_record"

    /**
     * 写工具权限：**拟改目标**（`propose_goal_change`）。默认开。
     * 判定口径：`!= "false"`。
     */
    const val AI_TOOL_WRITE_GOAL = "ai_tool_write_goal"

    // ── 目标组的一次性提示（设计规范系统 9.7 / 921 行） ────────────────
    /**
     * 目标组「依据提示」是否已展示过。`"true"` / `"false"`，默认 `false`（未展示）。
     *
     * 规范原文：「每项值下方 8dp 处可选显示 13sp `text_3` 的依据提示
     * （如 `来自膳食指南推荐量`），**仅在用户首次打开该组时显示一次**」。
     * "仅一次"必须跨启动记住，所以需要一个持久化标记 —— 用 `settings` 表存，
     * 不新建表（一条布尔值不值得加表 + Migration）。
     *
     * ⚠️ 这只是"提示已读"的状态，不是用户配置。写入时机 = 首次渲染目标组之后，
     * 保证用户至少真的看到过一眼。
     */
    const val GOAL_SOURCE_SEEN = "goal_source_seen"

    // ── 自由文本目标（「我的目标」/ 主目标自定义文本）──────────────────
    /**
     * 用户自由文本目标（如「想练出马甲线」「年底前跑半马」）。
     *
     * ⚠️ 为什么必须落 settings 表：`goals.target_value` 是 `REAL`（无文本列），
     *    自由文本目标无处可放。不得改名/改值域；
     *    空串 = 未填写。
     *
     * v10 起**双重身份**（唯一自由文本目标键，不再新增第二个）：
     * 1. 主目标自定义态（`goals.metric=primary, target_value=3`）的文本载体 ——
     *    设置页主目标行 / 首页主目标行 / GoalSetupSheet 均同源读写；
     * 2. AI 计划 prompt 的「目标（用户自述）」（PlanGenerator）与
     *    system prompt 的 primaryGoalName（TodaySummary）同源取值。
     */
    const val GOAL_STATEMENT = "goal_statement"

    /**
     * 自定义**次目标**（文本型，如「年底体脂降到 18%」）。空 = 未使用。
     *
     * 2026-10-05 起经用户拍板**纳入** AI 读取集合（对话/今日计划/周训练三链同口径，
     * 受 AI_DATA_FULL 总开关门控）。
     *
     * ⚠️ 为什么必须落 settings 表：`goals.target_value` 是 `REAL`（无文本列），
     *    自由文本目标无处可放。不得改名/改值域；
     *    空串 = 未填写。
     *
     * 占位判据 = 键值非空；清除 = 删键（文本型无归档态，设置页左滑 + UndoBar 快照恢复）。
     * 上限 80 字（沿用 GOAL_STATEMENT_MAX 先例），空白输入不落库。
     */
    const val CUSTOM_GOAL_TEXT = "custom_goal_text"

    // ── 目标设置引导（v8 需求 5）─────────────────────────────────────
    /**
     * 目标引导是否已完成。`"true"` = 已完成或已跳过；**键不存在 = 未完成**（弹引导）。
     *
     * 为什么需要一个独立键：v8 问题 2b 之前 `ensureGoalDefaultsIfEmpty()` 会在 goals
     * 空表时**无条件预置** 6 条默认目标 —— 这让"用户设过目标"与"系统灌的目标"
     * 在数据上无法区分。该预置已删除；引导弹窗的判据仍只能是本键，而不是 goals 表是否有行。
     *
     * ⚠️ 写入时机（[com.healix.app.ui.RecordFragment.maybeShowGoalSetup]）：
     *   - **老用户升级首启**：`goals` 已有 active 行 → 视为老用户，补写 `"true"`
     *     （静默跳过，不弹引导打扰）；
     *   - **全新安装**：goals 为空 → 引导弹窗出现，用户完成或跳过时写 `"true"`。
     */
    const val GOAL_SETUP_DONE = "goal_setup_done"

    // ── 热量目标收编进 goals 表（v8 问题 2a）──────────────────────────
    /**
     * 老数据迁移标记：settings 键 [TARGET_KCAL] → `goals` 的 `kcal_daily` 行**已完成**。
     *
     * `"true"` = 迁移跑过（老用户设过热量目标并已搬进目标栏）。
     * 键不存在 = 无需迁移（用户从没设过）或尚未跑。
     * 迁移逻辑见 `SettingsViewModel.migrateLegacyKcalTarget()`，**幂等**（已有 kcal 行则跳过）。
     */
    const val KCAL_TARGET_MIGRATED = "kcal_target_migrated"

    /**
     * 「热量目标现已移至此栏」一次性提示**是否已展示**。
     *
     * `"true"` = 已展示过（不再显示）。只对 [KCAL_TARGET_MIGRATED] 为 `"true"` 的设备有意义
     * —— 迁移过一次才需要告诉用户"入口挪地方了"。
     */
    const val KCAL_MOVE_HINT_SEEN = "kcal_move_hint_seen"

    // ── 计划自动重排节流（v8 需求 7）─────────────────────────────────
    /**
     * 最近一次**自动** AI 重排所用日的 day_key（`yyyy-MM-dd`）。
     *
     * 语义：计划页去掉手动「更新」后，AI 重排改为后台自动触发 —— 但一次
     * provider 往返恰好消耗 1 行 `llm_calls`（配额按行数计），**绝不能每次
     * 进页面都调**。规则 = 同一 day_key 内最多自动触发 1 次；本键记录"上次
     * 自动触发发生在哪一天"，与当前 day_key 不同才允许再触发。
     *
     * ⚠️ day_key 必须经 `parse/SchemaValidator.dayStartHourOf` + `dayKeyOf`
     *    取得（日界线纪律），本键只做存储，不做任何日期计算。
     *    键不存在 = 从未自动重排过（允许触发，还需同时满足其它门槛）。
     */
    const val PLAN_AUTO_RERANK_DAY = "plan_auto_rerank_day"
}
