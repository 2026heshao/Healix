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

    // ── 调用限制 ──────────────────────────────────────────────────────
    /**
     * 每日**抽取**调用上限（记一笔 / 出计划 / 复盘）。
     * ⚠️ 与 [CHAT_QUOTA] 是**两个独立计数** —— 一次对话要多次模型往返，
     *    和抽取共用配额会让用户聊两句就吃掉当天记录额度（见 QuotaGuard 头注释）。
     */
    const val EXTRACT_QUOTA = "daily_quota"

    /** 每日**对话**调用上限。 */
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
}
