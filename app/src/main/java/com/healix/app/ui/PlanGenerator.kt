package com.healix.app.ui

import android.content.Context
import com.healix.app.HealixApp
import com.healix.app.db.DailyPlanEntity
import com.healix.app.db.EventEntity
import com.healix.app.db.GoalDefaults
import com.healix.app.db.GoalMetrics
import com.healix.app.db.SettingsKeys
import com.healix.app.db.kcalTargetOf
import com.healix.app.net.ChatMessage
import com.healix.app.net.ChatRequest
import com.healix.app.net.ChatResult
import com.healix.app.net.ErrKind
import com.healix.app.net.NetworkStatus
import com.healix.app.net.OpenAiCompatProvider
import com.healix.app.parse.dayKeyOf
import com.healix.app.parse.dayStartHourOf
import com.healix.app.parse.loadsLenient
import com.healix.app.repo.EventRepository
import com.healix.app.repo.ProfileContext
import com.healix.app.repo.ResourceStore
import com.healix.app.repo.parseFoodsJson
import com.healix.app.rules.FoodPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/**
 * 今日计划 prompt 版本号（独立于抽取链 `PROMPT_VER` 与 `PROMPT_VER_TRAINING`）。
 * 此值只随计划 prompt 迭代递增。
 *
 * v1 → v2（问题 3 方案 C）：同一次调用顺带产出**明天**的 4 条粗颗粒锚点
 * （早/午/晚/训练），items 增 `day` / `slot` 两个字段。
 *
 * v2 → v3（v6 走查）：计划从「以饮食为主」改为**饮食 + 运动双线** ——
 * 今天至少 1 条运动项（例外只有生病 / 疼痛），强度按热量缺口分两档
 * （缺口大 → 低强度短时；缺口小或已达标 → 按本周训练计划原安排），
 * 且运动项的 detail 必须给动作与组次（禁止"运动 30 分钟"这类空描述）。
 *
 * v3 → v4（v6 走查 · 时间冲突）：允许用户把今天的临时安排（「下午 2 点开会」）
 * 当作 `type = other` 的一条记录写下来 —— [buildUserContext] 会把它们作为
 * **已占用时段**喂进来，prompt 硬规则 2 要求时间轴避开。
 *
 * ⚠️ 本地兜底 [buildTimeline] / [exerciseItem] 与本次 prompt 改动**同口径**：
 *    否则断网 / 限流降级时用户看到的时间轴又会退回纯饮食（两条路径行为分叉）。
 *
 * ⚠️ 但**占用时段只有 AI 路径认**：兜底是固定时刻的纯模板，无法从「下午 2 点」这类
 *    中文原文里可靠解析出时刻，故降级路径不避让 —— 这是刻意的已知限制，不是遗漏。
 */
const val PROMPT_VER_PLAN: String = "v4"

/**
 * 今日计划 system prompt（设计规范 §4.3 / 增量设计 §4.4）。
 *
 * ⚠️ 本 prompt **不进** `pipeline/contract.py`：`contract.py` 的契约范围由
 *    `check_kotlin.py` 的 `PROMPT_PARITY` 显式枚举（只含抽取链与训练链），
 *    两者都有 Python 侧镜像实现、参与离线回归；计划链在 Python 侧无镜像，
 *    塞进契约文件只增加"逐字节一致"的手工维护负担而无校验价值。
 *    因此 `PROMPT_PLAN` / `PROMPT_VER_PLAN` **只存在于本文件**。
 */
const val PROMPT_PLAN: String = """你是 Healix 的计划助手。围绕用户的目标与当下真实数据，把**今天（从现在到睡前）**要做的事排成一条时间轴，并顺带给**明天**留下粗颗粒锚点。
今天的计划必须**饮食与运动两条线都有**：吃什么、怎么动，两项都要给出能照着做的具体内容。

硬规则（逐条遵守，冲突时序号小的优先）：
1. 只排「现在之后」的时段：已经过去的时刻不再安排（今天各项的 time 必须晚于"现在是 HH:mm"）。
2. 「今天已记录的其他事项」是用户今天临时记下的、不属于吃喝 / 运动 / 身体指标 / 睡眠的事情。其中**能落在具体时段上**的（如「下午 2 点开会」「三点去接孩子」「四点看医生」）一律视为**已占用时段**：计划里任何条目都不得与之重叠，必要时把相邻条目前后挪开（例如 14:00 开会 → 把 14:00 前后一小时的条目挪到 13:00 之前或 15:30 之后）；**只是事实陈述**的（如「没运动」「今天很累」）不含时段，忽略即可。上下文里没有这一段时，本条忽略。
3. 今天的计划里**至少要有 1 条运动项**（type = exercise）；唯一的例外是第 6、7 条（生病、疼痛/不适）。
4. 结合「热量缺口」安排饮食：缺口大就把正餐+加餐都排上并给具体数量；已达标则不再堆餐，把篇幅让给运动。
5. 运动强度按缺口分两档（与第 4 条联动）：
   - 缺口 > 600 kcal → 排**低强度、短时**的日常活动（餐后快走 / 拉伸 / 慢走，10-20 分钟），不排高强度力量训练；
   - 缺口 ≤ 600 kcal（含已达标）→ 按「本周训练计划里今天那条」的**原安排**执行（动作、组数、次数照抄，不自己换动作）。
6. 若今天有生病记录 → 全天改为恢复安排（清淡饮食 + 补水 + 早睡），**不排任何运动项**。
7. 若今日睡眠不足 6 小时或近 3 日睡眠连续偏低 → 运动降强度（改低强度有氧或拉伸），并优先排早睡。
8. 有疼痛/不适部位 → 运动建议必须避开相关动作，优先恢复性建议（睡眠、补水）。有忌口/过敏 → 饮食建议必须绕开。
9. 运动项最多 2 条（1 条主项 + 至多 1 条散步/拉伸）；今天各项合计最多 6 条，按时间从早到晚排序。
10. 禁止输出 1RM 估算、力量总分、综合评分或任何形式的打分；禁止给出药物剂量。
11. 今天的每一项都要有明确的 time（HH:mm 24 小时制）。
12. 明天只给 4 条**粗颗粒锚点**：早 / 午 / 晚 三餐 + 训练；这 4 条 day 一律写 2，time 一律写空字符串。
13. 明天的训练锚点参考本周训练计划里"明天"那条；若那一句给的是"无"，说明明天休息或本周计划还没生成 → 写恢复/休息，不要硬排力量训练；今天有生病记录或有疼痛/不适部位时，明天的训练锚点同样写恢复。
14. 明天是"先把位置留出来"：内容给常见分量即可，不必精确，也不要编造明天才会有的数据（明天的体重、睡眠、摄入都还没有）。

输出要求：
- 只输出 JSON，不要任何解释文字，不要 markdown 代码围栏。
- items 每项含 day / time / slot / type / title / detail / kcal / duration / why。
- day：今天写 1，明天写 2。
- slot：只对 day=2 有意义，只能取 morning（早）/ noon（午）/ evening（晚）/ train（训练）之一；day=1 的项写空字符串。
- time：day=1 必填 HH:mm；day=2 一律空字符串（锚点不显示具体时刻）。
- type ∈ {meal, exercise, sleep, habit}；sleep/habit 项 kcal 写 0，duration 可写"——"；day=2 的项 kcal 也一律写 0。
- **运动项的 detail 必须写清「怎么做」**：动作名 + 组数×次数（力量），或 时长/距离（有氧）。
  正面例子："深蹲 4×8 / 卧推 4×8"、"快走 30 分钟（约 3 公里）"、"全身拉伸 10 分钟"。
  禁止："运动 30 分钟"、"适当锻炼" 这类没有内容的写法 —— 等于没排。
- title 简短（day=1 如"晚餐""力量训练""快走""睡觉"；day=2 写内容本身，如"燕麦牛奶 + 鸡蛋"）；
  detail 给具体怎么做（数量/动作/时长）；duration 写"约 N 分钟"；why 用一句话说明为什么这样安排。
- note 一句话总结今天的重点，饮食与运动都要提到。

输出格式固定为：
{"items": [{"day": 1, "time": "12:30", "slot": "", "type": "meal", "title": "午餐", "detail": "米饭 200g + 鸡胸 150g + 一份绿叶菜", "kcal": 650, "duration": "约 20 分钟", "why": "还差 1200 kcal，先补一半"}, {"day": 1, "time": "18:30", "slot": "", "type": "meal", "title": "晚餐", "detail": "米饭 200g + 牛肉 150g + 一份绿叶菜", "kcal": 650, "duration": "约 20 分钟", "why": "补上剩下的缺口"}, {"day": 1, "time": "19:30", "slot": "", "type": "exercise", "title": "快走", "detail": "餐后快走 20 分钟（约 2 公里），心率微喘即停", "kcal": 120, "duration": "约 20 分钟", "why": "缺口还大，先做低强度活动，不占用恢复"}, {"day": 1, "time": "23:00", "slot": "", "type": "sleep", "title": "睡觉", "detail": "睡前 30 分钟放下手机", "kcal": 0, "duration": "——", "why": "近 3 天睡眠偏低，优先补觉"}, {"day": 2, "time": "", "slot": "morning", "type": "meal", "title": "燕麦牛奶 + 鸡蛋", "detail": "燕麦 60g + 牛奶 300ml + 鸡蛋 2 个", "kcal": 0, "duration": "约 15 分钟", "why": "先占个位置，明天按当时的缺口再调"}, {"day": 2, "time": "", "slot": "noon", "type": "meal", "title": "米饭 + 鸡胸 + 绿叶菜", "detail": "米饭 200g + 鸡胸 150g + 一份绿叶菜", "kcal": 0, "duration": "约 20 分钟", "why": "先占个位置，明天按当时的缺口再调"}, {"day": 2, "time": "", "slot": "evening", "type": "meal", "title": "面食 + 牛肉", "detail": "面食一份 + 牛肉 120g + 一份蔬菜", "kcal": 0, "duration": "约 20 分钟", "why": "先占个位置，明天按当时的缺口再调"}, {"day": 2, "time": "", "slot": "train", "type": "exercise", "title": "深蹲 4×8 / 卧推 4×8", "detail": "按本周计划明天的安排执行", "kcal": 0, "duration": "约 30 分钟", "why": "明天是训练日，先留出位置"}], "note": "今天餐食补足蛋白，餐后快走 20 分钟；明天三餐与训练已占位"}
"""

/**
 * 时间轴条目（同时作为 UI 模型）。
 *
 * @property time  今天的条目为 "HH:mm" 24 小时制；旧数据可能为空 → 渲染时隐藏时间列。
 *                 **明天的锚点恒为空串**（锚点不显示具体时刻，见 [slot]）。
 * @property type  meal | exercise | sleep | habit（仅 meal/exercise 可「记一笔」）。
 * @property kcal  估算热量；sleep/habit 为 0。**明天锚点恒为 0** —— 明天的摄入还没发生，
 *                 给数字就是编数据（且锚点不可「记一笔」，见 [TimelineMerger.merge]）。
 * @property duration 空串显示「——」。
 * @property why   一句「为什么是现在」；空串不显示。
 * @property day   [PLAN_DAY_TODAY] / [PLAN_DAY_TOMORROW]（问题 3 方案 C）。
 * @property slot  明天锚点的时段键（[PlanSlot] 之一；今天条目为空串）。
 */
data class TimelineItem(
    val time: String,
    val type: String,
    val title: String,
    val detail: String,
    val kcal: Int,
    val duration: String = "",
    val why: String = "",
    val day: Int = PLAN_DAY_TODAY,
    val slot: String = "",
)

/** 生成/读取结果。`failed = true` 表示 AI 失败（缓存保留或走了本地兜底）。 */
data class PlanResult(
    val items: List<TimelineItem>,
    val note: String,
    /** ai | fallback */
    val source: String,
    val generatedAt: Long,
    val failed: Boolean = false,
    val fromCache: Boolean = false,
)

/**
 * 今日计划生成器（对齐 [TrainingPlanner] 的现成范式）。
 *
 * 职责：读缓存 / 调一次模型（用户点「更新」时）/ 防御性解析 /
 * 本地时间轴兜底 / 落 `daily_plans` / 埋点。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 成本与触发纪律（PRD §8.3）
 * ══════════════════════════════════════════════════════════════════════════
 * **只在计划页点「更新」时调模型**（`update`）；打开计划 Tab 只读缓存
 * （[loadCached]，`force=false`），绝不调 AI —— 与训练页整周缓存同一成本纪律。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 失败必须降级可用（PRD 风险表：免费档 429 密集）
 * ══════════════════════════════════════════════════════════════════════════
 * 未配置 provider / 限流 / 超时 / JSON 解析失败：**有缓存保留缓存（不清空），
 * 无缓存落本地时间轴 [localTimeline]**（`source = fallback`）。
 */
class PlanGenerator(context: Context) {

    private val app: HealixApp = HealixApp.from(context)
    private val appContext: Context = context.applicationContext
    private val db = app.database

    companion object {
        /** `plan_json` schema 版本（见增量设计 §3.4）。v3：items 增 `day` / `slot` 两字段。 */
        private const val PLAN_JSON_VERSION = 3

        /** 主目标「增重」编码（与 HealthAggregator / SettingsViewModel 口径一致）。 */
        private const val PLAN_PRIMARY_GAIN = 0

        /** item.type 缺失时的安全兜底（无「记一笔」，最不具副作用）。 */
        private const val PLAN_TYPE_HABIT = "habit"

        /** `type` 常量（本地兜底条目用；与 prompt 约定一致）。 */
        private const val PLAN_TYPE_MEAL = "meal"
        private const val PLAN_TYPE_EXERCISE = "exercise"
        private const val PLAN_TYPE_SLEEP = "sleep"

        /** 明天锚点的统一「为什么」：说清这是占位、当天会按真实数据细化（不冒充精确）。 */
        private const val TOMORROW_WHY = "明天的占位锚点，当天会按你的实际数据细化"

        /**
         * 「今天」条目上限（prompt 约定）。
         *
         * ⚠️ **`internal` 而非 `private`**：`PlanChangeWriter.add_item` 必须按同一上限拦截
         * —— 否则模型加第 7 条时 writer 报「已加上」，而 [itemsOf] 解析期把第 7 条**静默丢弃**
         * （今天 ≤ 本值），用户看到的是「说加了却没加」。上限必须**唯一来源**。
         */
        internal const val MAX_TODAY_ITEMS = 6

        /** 喂给模型的「今天已记录的其他事项」条数上限（防啰嗦 / prompt 被灌爆）。 */
        private const val MAX_OCCUPIED_ITEMS = 5

        /** 单条占用事项的原文截断长度。 */
        private const val MAX_OCCUPIED_LEN = 60

        /**
         * 「明天」锚点上限（早 / 午 / 晚 / 训练，共 4 条）。
         *
         * ⚠️ `internal` 的理由同 [MAX_TODAY_ITEMS]：`PlanChangeWriter.add_item` 同口径拦截，
         * 否则多出的锚点会在 [itemsOf] 解析期被静默丢弃。
         */
        internal const val MAX_TOMORROW_ITEMS = 4

        /**
         * 「缺口大」的分界（kcal）：≥ 此值 → 运动降为低强度短时（见 [exerciseItem]）。
         * 与 [PROMPT_PLAN] 硬规则 5 的「缺口 > 600 kcal」**同口径，改一处必改另一处**。
         */
        private const val LOW_INTENSITY_GAP = 600

        /** 近 3 日**平均**睡眠低于此值（小时）→ 运动降强度（与 prompt 硬规则 7 同口径）。 */
        private const val SLEEP_LOW_H = 6.5

        /**
         * 数组**扫描**上限：模型输出永不可信，防啰嗦 / prompt 被注入时返回成百条拖垮
         * 主线程 inflate（渲染见 PlanReviewFragment.renderTimeline，逐项 inflate）。
         * 今天与明天**各自**另有条数上限（见 [itemsOf]），此处只封总扫描量。
         * ⚠️ 命名为 MAX_PLAN_ITEMS 而非 MAX_EVENTS，避免与 SchemaValidator 的
         *    抽取链同名常量撞车触发 check_duplicate_constants。
         */
        private const val MAX_PLAN_ITEMS = MAX_TODAY_ITEMS + MAX_TOMORROW_ITEMS + 2

        private const val MAX_TITLE_LEN = 80
        private const val MAX_DETAIL_LEN = 120
        private const val MAX_WHY_LEN = 80
        private const val MAX_NOTE_LEN = 200
    }

    // ------------------------------------------------------------------
    // 读：只读缓存（打开计划 Tab 用，force=false，绝不调 AI）
    // ------------------------------------------------------------------

    /** 今日 day_key（yyyy-MM-dd），供调用方拼接缓存键。 */
    suspend fun todayKey(): String = withContext(Dispatchers.IO) {
        val dayStart = dayStartHour()
        dayKeyOf(System.currentTimeMillis(), dayStart)
    }

    /**
     * 读今日计划缓存。命中且解析成功返回 [PlanResult]（`fromCache = true`）；
     * 无缓存或解析失败返回 null（调用方走本地兜底，**绝不触发模型调用**）。
     */
    suspend fun loadCached(todayKey: String): PlanResult? = withContext(Dispatchers.IO) {
        val stored = runCatching { db.planDao().getPlan(todayKey) }.getOrNull()
            ?: return@withContext null
        val parsed = parseTimelineJson(stored.planJson) ?: return@withContext null
        PlanResult(
            items = parsed.items,
            note = parsed.note,
            source = stored.source,
            generatedAt = stored.generatedAt,
            fromCache = true,
        )
    }

    // ------------------------------------------------------------------
    // 本地时间轴兜底（无 AI 可用时的降级；不落库由调用方决定）
    // ------------------------------------------------------------------

    /**
     * 纯本地降级时间轴：把既有规则条目（晚餐 / 加餐 / 睡眠 / 训练 / 恢复）
     * **按时段铺开**成一条时间轴，并按问题 3 方案 C 补一组**明天的粗颗粒锚点**
     * （早 / 午 / 晚 / 训练；`kcal` 全 0 —— 明天的摄入还没发生，不编数字）。
     *
     * ⚠️ **给的是估算值**（这是实话，不是"不编数据"）：受本地规则限制，
     * [buildTimeline] 会给出**估算**的数量与热量（如 400/650/450/400/200 kcal
     * 与"熟米饭 200g + 鸡胸或牛肉 150g"这类常见分量），目的是让用户"照着吃"
     * 有用；但它**不是精确数据**——kcal 是按常见分量拍的估算值。
     * 用户点「记一笔」前，来源行已标注为估算（见 [PlanReviewFragment.renderPlanHeader]
     * 的 `plan_source_estimated`）。食物建议优先取手动清单，物品条件取资源清单。
     *
     * ⚠️ internal：签名暴露 internal 类型 [TodaySummary]，不能是 public
     *    （否则编译报 "public function exposes its internal parameter type"）。
     */
    internal suspend fun localTimeline(summary: TodaySummary): PlanResult = withContext(Dispatchers.IO) {
        val manualFoods = ResourceStore.splitItems(ResourceStore.foods(db))
        val pool = runCatching { FoodPool.build(appContext) }.getOrDefault(emptyList())
        val sport = ResourceStore.sport(db)
        val pain = parseFoodsJson(db.settingsDao().get(SettingsKeys.PROFILE_PAIN).orEmpty())
        val bedTime = db.settingsDao().get(SettingsKeys.PROFILE_SLEEP_BED).orEmpty()
        val now = currentHhmm()
        // 明天的训练锚点要跟本周计划对齐：那天休息 / 没有计划 → 不发训练锚点
        //（免得把「明天休息」覆盖成一条训练行，见 TimelineMerger 的抑制约定）。
        val tomorrowTraining = trainingLineFor(1)

        PlanResult(
            items = buildTimeline(summary, manualFoods, pool, sport, pain, bedTime, now) +
                buildTomorrowAnchors(
                    manualFoods = manualFoods,
                    pool = pool,
                    sport = sport,
                    pain = pain,
                    trainSummary = tomorrowTraining,
                    hasIllness = summary.hasIllness,
                ),
            note = buildNote(summary),
            source = TrainingPlanner.SOURCE_FALLBACK,
            generatedAt = System.currentTimeMillis(),
        )
    }

    // ------------------------------------------------------------------
    // 更新：唯一会调模型的入口（用户点「更新」时，force=true）
    // ------------------------------------------------------------------

    /**
     * 重新排今日时间轴（唯一会调模型的入口）。
     *
     * 降级链（未配置/不可用 与 AI 失败**同一收口** [failWithFallbackOrCache]）：
     * 有缓存 → 保留上一版（不清空）；无缓存 → 落本地兜底。
     * AI Ok 但解析为空 → 亦视失败走该收口。
     *
     * 埋点不变式（**一次 provider 往返恰好一行 `llm_calls`**）：
     * - 解析成功 → `STATUS_OK`；HTTP 通但 JSON 没用（解析空）→ `STATUS_SCHEMA_INVALID`；
     * - 其它失败 → 按 [ErrKind] 记 `TIMEOUT` / `HTTP_ERROR` / `RETRY_EXHAUSTED`。
     * ⚠️ **绝不用"补记一条"实现** —— 那会让一次往返产生 2 行，`QuotaGuard.canExtract()`
     *    按行数计数 → 白多消耗一次配额。正确改法是把 OK 记录**移位**到解析成功之后。
     *
     * ⚠️ internal：签名暴露 internal 类型 [TodaySummary]（同 [localTimeline]）。
     */
    internal suspend fun update(todayKey: String, summary: TodaySummary): PlanResult = withContext(Dispatchers.IO) {
        val config = runCatching { app.eventRepository.loadProviderConfig() }.getOrNull()
        if (config == null || !config.isUsable() || !NetworkStatus.isOnline(app)) {
            // 未配置 / 不可用 / 断网：不调网。与 AI 失败走同一收口 ——
            // 有缓存保留上一版，无缓存才落本地兜底（不得用兜底覆盖已有缓存）。
            // 断网同样不调网：NETWORK 属于可重试 ErrKind，离线会白等整条退避链
            //（最坏 ~75-80 秒），把按钮卡在「更新中」。与对话链同一口径。
            return@withContext failWithFallbackOrCache(todayKey, summary)
        }

        val provider = OpenAiCompatProvider(config)
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(role = "system", content = PROMPT_PLAN),
                ChatMessage(role = "user", content = buildUserContext()),
            ),
            temperature = 0.5,
            timeoutMs = 20_000L,
            // 免费档 429 密集：重试 2 次即可，不把用户卡在长退避链上
            maxRetries = 2,
            retryBaseSeconds = 1.5,
            exponentialBackoff = true,
        )

        val started = System.currentTimeMillis()
        val result = provider.chat(request)
        val latencyMs = System.currentTimeMillis() - started

        when (result) {
            is ChatResult.Ok -> {
                // ⚠️ 埋点**移位**到解析判定之后（见函数 KDoc 的不变式）：
                //    一次 provider 往返恰好一行 llm_calls，绝不"先记 OK 再补记"。
                val parsed = parseTimelineJson(result.content)
                if (parsed != null) {
                    app.eventRepository.recordPlanCall(
                        model = config.model,
                        attempts = 1,
                        latencyMs = latencyMs,
                        status = EventRepository.STATUS_OK,
                        httpCode = 200,
                        inputTokens = result.usage.inputTokens,
                        outputTokens = result.usage.outputTokens,
                        promptVer = PROMPT_VER_PLAN,
                    )
                    val plan = PlanResult(
                        items = parsed.items,
                        note = parsed.note,
                        source = TrainingPlanner.SOURCE_AI,
                        generatedAt = System.currentTimeMillis(),
                    )
                    persist(todayKey, plan, summary.target)
                    return@withContext plan
                }
                // HTTP 通了但 JSON 没用（解析为空 / items 空）→ 改记 schema_invalid，
                // 再走兜底/缓存。否则埋点会显示"成功"，模型开始吐坏 JSON 这类回归
                // 在数据上看不出来。
                app.eventRepository.recordPlanCall(
                    model = config.model,
                    attempts = 1,
                    latencyMs = latencyMs,
                    status = EventRepository.STATUS_SCHEMA_INVALID,
                    httpCode = 200,
                    inputTokens = result.usage.inputTokens,
                    outputTokens = result.usage.outputTokens,
                    errorHead = result.content.take(200),
                    promptVer = PROMPT_VER_PLAN,
                )
                failWithFallbackOrCache(todayKey, summary)
            }

            is ChatResult.Err -> {
                app.eventRepository.recordPlanCall(
                    model = config.model,
                    attempts = if (result.attempts > 0) result.attempts else request.maxRetries + 1,
                    latencyMs = latencyMs,
                    status = when (result.kind) {
                        ErrKind.TIMEOUT -> EventRepository.STATUS_TIMEOUT
                        ErrKind.AUTH -> EventRepository.STATUS_HTTP_ERROR
                        else -> EventRepository.STATUS_RETRY_EXHAUSTED
                    },
                    httpCode = result.httpCode,
                    errorHead = result.message,
                    promptVer = PROMPT_VER_PLAN,
                )
                failWithFallbackOrCache(todayKey, summary)
            }
        }
    }

    /**
     * AI 失败后的收口：有缓存 → 保留上一版（`failed = true`，不清空）；无缓存 →
     * 落本地兜底（`source = fallback`，`failed = true`）。
     */
    private suspend fun failWithFallbackOrCache(todayKey: String, summary: TodaySummary): PlanResult {
        val cached = loadCached(todayKey)
        if (cached != null) {
            return cached.copy(failed = true, fromCache = true)
        }
        val local = localTimeline(summary)
        val persisted = local.copy(failed = true, generatedAt = System.currentTimeMillis())
        persist(todayKey, persisted, summary.target)
        return persisted
    }

    // ------------------------------------------------------------------
    // user 上下文（本地事实，不做推测）
    // ------------------------------------------------------------------

    /** 聚合喂给模型的本地事实：目标 + 结构化目标 + 画像 + 今日摘要 + 今日/明天训练 + 近 3 日摘要。 */
    private suspend fun buildUserContext(): String {
        val goalStatement = db.settingsDao().get(SettingsKeys.GOAL_STATEMENT).orEmpty().trim()
        val primaryIdx = db.goalDao().getByMetric(GoalMetrics.PRIMARY)
            ?.targetValue?.toInt() ?: PLAN_PRIMARY_GAIN
        val targetKcal = kcalTargetOf(db)
        val sessions = goalInt(GoalMetrics.SESSIONS_PER_WEEK, GoalDefaults.TRAIN_SESSIONS_PER_WEEK)
        val minutes = goalInt(GoalMetrics.TRAIN_MINUTES_PER_WEEK, GoalDefaults.TRAIN_MINUTES_PER_WEEK)
        val sleepH = db.goalDao().getByMetric(GoalMetrics.SLEEP_H)
            ?.targetValue?.takeIf { it > 0.0 } ?: GoalDefaults.SLEEP_H
        val waterMl = goalInt(GoalMetrics.WATER_ML, GoalDefaults.WATER_ML)

        val summary = TodaySummary.build(appContext)
        val profile = ProfileContext.build(db)
        val todayTraining = trainingLineFor(0)
        val tomorrowTraining = trainingLineFor(1)
        val recent = recentSummary()
        val occupied = todayOccupiedItems()

        return buildString {
            // 目标（用户自述）行（2026-10-05）：画像类自由文本，受 AI_DATA_FULL
            // 总开关门控 —— 关闭时整行省略（不打印"未填写"）。其余行是排程
            // 操作数（裁定 F），保留 GoalDefaults 兜底，不受本开关影响。
            if (ProfileContext.aiDataFull(db)) {
                appendLine("目标（用户自述）：" + goalStatement.ifEmpty { "未填写" })
            }
            appendLine("主目标：" + goalName(primaryIdx))
            appendLine("每日目标摄入：$targetKcal kcal")
            appendLine("每周训练：$sessions 次 / $minutes 分钟")
            appendLine("睡眠目标：${trim(sleepH)} 小时   饮水目标：$waterMl ml")
            if (profile.isNotBlank()) appendLine(profile)
            summary.lines.forEach { appendLine(it) }
            // 今天已记录的其他事项（v6 走查）：作为**已占用时段**喂给模型，
            // 让它排出的时间轴避开用户自己的临时安排（如「下午 2 点开会」）。
            // 空则整段省略 —— 不打印「无」，免得模型把空列表当成一种状态去讨论。
            // ⚠️ 行标题必须与 prompt 硬规则 2 的措辞对得上（模型靠它找到这一段）。
            if (occupied.isNotEmpty()) {
                appendLine("今天已记录的其他事项（含时段的视为已占用，计划需避开）：")
                occupied.forEach { appendLine("  $it") }
            }
            appendLine("本周训练计划里\"今天\"那条：${todayTraining ?: "无"}")
            appendLine("本周训练计划里\"明天\"那条：${tomorrowTraining ?: "无"}")
            appendLine("最近记录摘要：$recent")
            appendLine("请按硬规则排出今天从现在到睡前的时间轴（饮食 + 运动两条线都要有），并附上明天的 4 条锚点。")
        }.trim()
    }

    /**
     * 本周训练计划里「今天 + [offsetDays]」那天的安排（`标题 · 动作`）。
     *
     * @return 该天的安排；**本周计划尚未生成、或那天是休息日 → null**
     *         （调用方据此区分「无」与「休息」，见 prompt 硬规则 13）。
     */
    private suspend fun trainingLineFor(offsetDays: Long): String? {
        val plan = runCatching { TrainingPlanner(appContext).loadOrGenerate(force = false) }.getOrNull()
            ?: return null
        val dow = LocalDate.now().plusDays(offsetDays).dayOfWeek.value
        val day = plan.days.firstOrNull { it.dow == dow && !it.isRest } ?: return null
        val line = listOf(day.title, day.itemsLine())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        return line.ifBlank { null }
    }

    /**
     * 今天已记录的「其他事项」（`events.type == other` 的原文）。
     *
     * 用途（v6 走查 · 时间冲突）：把用户今天的临时安排（「下午 2 点开会」）作为
     * **已占用时段**喂给 [PROMPT_PLAN]，让重排出的时间轴自动避开。
     *
     * 三个刻意的边界：
     * 1. **只取 `rawText`，不用 `ts`** —— `ts` 是「用户什么时候记的」，不是「事情什么时候发生」。
     *    下午 1 点记「下午 2 点开会」，`ts` = 13:00 而事情在 14:00，时刻信息只在原文里。
     * 2. **只取 `other`**：吃喝 / 运动 / 体重 / 睡眠 / 生病都不是「时段占用」。
     * 3. **不做语义剪裁**：否定类事实（「没运动」「今天很累」）同样落在 `other`，
     *    由 prompt 硬规则 2 要求模型自行区分「能落在时段上的」与「纯事实陈述」。
     *
     * 这是**唯一**把记录内容送进计划 prompt 的口子（其余上下文只给数字），
     * 故改动它等于改计划生成的输入面，需与其他链路一起复核。
     *
     * @return 最多 [MAX_OCCUPIED_ITEMS] 条、每条截断到 [MAX_OCCUPIED_LEN] 的原文；无则空列表。
     */
    private suspend fun todayOccupiedItems(): List<String> {
        val dayStart = dayStartHour()
        val key = dayKeyOf(System.currentTimeMillis(), dayStart)
        val rows = runCatching { db.eventDao().listByDay(key) }.getOrDefault(emptyList())
        return rows.asSequence()
            .filter { it.type == EventEntity.TYPE_OTHER }
            .map { it.rawText.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_OCCUPIED_ITEMS)
            .map { it.take(MAX_OCCUPIED_LEN) }
            .toList()
    }

    /** 近 3 日（不含今天）记录摘要；无记录写「无」。 */
    private suspend fun recentSummary(): String {
        val dayStart = dayStartHour()
        val today = LocalDate.parse(dayKeyOf(System.currentTimeMillis(), dayStart))
        val parts = mutableListOf<String>()
        for (offset in 1..3) {
            val key = today.minusDays(offset.toLong()).toString()
            val rows = runCatching { db.eventDao().listByDay(key) }.getOrDefault(emptyList())
            if (rows.isEmpty()) continue
            val kcal = rows.filter { it.type == PLAN_TYPE_MEAL }.sumOf { it.kcal }
            val sleep = rows.firstOrNull { it.type == PLAN_TYPE_SLEEP && it.sleepH > 0 }?.sleepH ?: 0.0
            parts += "$key ${rows.size} 条 / 摄入 $kcal kcal / 睡眠 ${trim(sleep)} 小时"
        }
        return if (parts.isEmpty()) "无" else parts.joinToString("；")
    }

    // ------------------------------------------------------------------
    // 本地时间轴构造（迁移自 PlanReviewViewModel 的规则条目）
    // ------------------------------------------------------------------

    /**
     * 规则条目 → 时间轴（按时段铺开）。三种形态与原 `buildLocalPlan` 一致：
     * 生病 → 单条清淡餐；已达标 → 收尾条目（训练 / 恢复）；有缺口 → 晚餐(+加餐)(+睡眠)。
     */
    private fun buildTimeline(
        s: TodaySummary,
        manualFoods: List<String>,
        pool: List<String>,
        sport: String,
        pain: List<String>,
        bedTime: String,
        now: String,
    ): List<TimelineItem> {
        if (s.hasIllness) {
            return listOf(
                TimelineItem(
                    time = slotOrNow("18:00", now),
                    type = PLAN_TYPE_MEAL,
                    title = "晚餐：清淡易消化",
                    detail = "小米粥 + 蒸蛋，避免油腻与生冷",
                    kcal = 400,
                    duration = "约 15 分钟",
                    why = "今天有生病记录，先恢复再训练",
                ),
            )
        }

        if (s.gap <= 0) {
            // 已达标：只留一条收尾（运动 / 恢复），结构与 v1 相同。
            return listOf(exerciseItem(s, sport, pain, slotOrNow("20:00", now)))
        }

        val items = mutableListOf<TimelineItem>()

        if (s.gap >= LOW_INTENSITY_GAP) {
            items += TimelineItem(
                time = slotOrNow("18:30", now),
                type = PLAN_TYPE_MEAL,
                title = "晚餐：主食 + 蛋白质",
                detail = mealDetail(manualFoods, pool, "熟米饭 200g + 鸡胸或牛肉 150g + 一份绿叶菜"),
                kcal = 650,
                duration = "约 20 分钟",
                why = "还差 ${s.gap} kcal，这一顿补上一大半",
            )
        } else {
            items += TimelineItem(
                time = slotOrNow("18:30", now),
                type = PLAN_TYPE_MEAL,
                title = "晚餐：正常一份主食",
                detail = mealDetail(manualFoods, pool, "面食或米饭一份 + 一个鸡蛋"),
                kcal = 450,
                duration = "约 15 分钟",
                why = "缺口不大，一顿补齐",
            )
        }

        // 餐后运动 / 恢复（v6 走查新增）：让「怎么运动」这条线在**本地兜底**里也存在 ——
        // 改前只有「热量已达标」那一条分支会给训练，缺口 > 0 时兜底计划全天纯饮食，
        // 与 prompt 硬规则 3 要求的双线不一致（断网 / 限流降级时最明显）。
        items += exerciseItem(s, sport, pain, slotOrNow("19:30", now))

        // 只用**摄入类**条目抵扣缺口：运动项的 kcal 是消耗不是摄入 ——
        // 改前 items 里只有晚餐（meal），全量求和恰好正确；多一条 exercise 后
        // 若仍全量求和，缺口会被凭空多扣 120-200 kcal。
        val remain = s.gap - items.filter { it.type == PLAN_TYPE_MEAL }.sumOf { it.kcal }
        if (remain > 200) {
            items += TimelineItem(
                time = slotOrNow("21:00", now),
                type = PLAN_TYPE_MEAL,
                title = "加餐：睡前补充",
                detail = mealDetail(manualFoods, pool, "蛋白粉 1 勺 + 香蕉 1 根"),
                kcal = 400,
                duration = "约 5 分钟",
                why = "离睡眠还有几个小时，小份加餐好消化",
            )
        }

        // 睡眠行动条：近 3 日平均睡眠不足 6.5 小时才提（有数据才建议，不猜）
        if (s.sleepLast3.size >= 2 && s.sleepLast3.average() < SLEEP_LOW_H) {
            items += TimelineItem(
                time = sleepSlot(bedTime, now),
                type = PLAN_TYPE_SLEEP,
                title = "睡：${bedTime.ifBlank { "定点" }} 前放下手机",
                detail = "今晚按目标就寝时间执行",
                kcal = 0,
                duration = "——",
                why = "近 3 天平均只睡 ${trim(s.sleepLast3.average())} 小时",
            )
        }

        return items
    }

    /**
     * 运动 / 恢复条目 —— **今日计划里「怎么运动」这条线的唯一产出点**。
     *
     * prompt 路径由 `PROMPT_PLAN` 硬规则 3 / 5 约束，本地兜底走这里，两者**同口径**：
     * 改前此处叫 `tailItem`，只在「热量已达标」那条分支被调用 → 缺口 > 0 的日子
     * 兜底计划全天纯饮食。名字与调用面一起改，正是为了杜绝再退回单线。
     *
     * 强度三档（自上而下判定）：
     * 1. 疼痛/不适（F9）→ 不排训练，改「恢复」（type = sleep，不冒充运动记录）；
     * 2. 缺口 ≥ [LOW_INTENSITY_GAP]，或近 3 日平均睡眠 < [SLEEP_LOW_H] → 低强度短时（餐后快走）；
     * 3. 否则 → 按资源清单「运动条件」给力量训练，清单为空则自重。
     *
     * kcal 是**估算值**（口径见 [localTimeline] 的 KDoc），不是精确消耗。
     */
    private fun exerciseItem(
        s: TodaySummary,
        sport: String,
        pain: List<String>,
        time: String,
    ): TimelineItem {
        if (pain.isNotEmpty()) {
            return TimelineItem(
                time = time,
                type = PLAN_TYPE_SLEEP,
                title = "恢复：补水 + 早睡",
                detail = "疼痛/不适部位（${pain.joinToString("、")}）相关动作今天全部避开",
                kcal = 0,
                duration = "——",
                why = "恢复优先于训练（疼痛避让）",
            )
        }
        val lowIntensity = s.gap >= LOW_INTENSITY_GAP ||
            (s.sleepLast3.size >= 2 && s.sleepLast3.average() < SLEEP_LOW_H)
        return if (lowIntensity) {
            TimelineItem(
                time = time,
                type = PLAN_TYPE_EXERCISE,
                title = "餐后快走",
                detail = "饭后半小时快走 20 分钟（约 2 公里），心率微喘即停",
                kcal = 120,
                duration = "约 20 分钟",
                why = if (s.gap >= LOW_INTENSITY_GAP) {
                    "热量缺口还大，先做低强度活动"
                } else {
                    "近 3 天睡眠偏低，用低强度代替力量"
                },
            )
        } else {
            TimelineItem(
                time = time,
                type = PLAN_TYPE_EXERCISE,
                title = "练：力量训练 30 分钟",
                detail = if (sport.isBlank()) {
                    "深蹲 4×8 + 俯卧撑 4×10，自重就够"
                } else {
                    "按你的条件练：${sport.lineSequence().joinToString("；")}"
                },
                kcal = 200,
                duration = "约 30 分钟",
                why = if (s.gap <= 0) "今天热量已达标，正好安排训练" else "缺口不大，按训练计划练正合适",
            )
        }
    }

    /**
     * 「明天」的粗颗粒锚点（问题 3 方案 C）：早 / 午 / 晚 三餐 +（非休息日才有的）训练。
     *
     * 纯模板铺开、零网络。三点刻意的约束：
     * 1. `time` 一律空串 —— 锚点**不显示具体时刻**（明天的时刻还没意义）；
     * 2. `kcal` 一律 0 —— 明天的摄入还没发生，给数字就是编数据；
     * 3. 第 4 条（训练位）按 `生病 / 疼痛 → 恢复`、`本周计划明天那条 → 跟它`、
     *    `资源清单有装备 → 按条件练`、**都没有就不发** 的顺序取一条；
     *    完全不发时，明天那格仍保留训练日行本身（休息日显示「休息」），
     *    不会被一条凭空造出来的训练锚点覆盖（见 [TimelineMerger.merge] 的抑制约定）。
     *
     * @param trainSummary 本周训练计划里「明天」那条（`标题 · 动作`）；null = 明天休息 / 无计划
     * @param hasIllness 今天有生病记录 → 明天也不排力量，训练位改「恢复」
     */
    private fun buildTomorrowAnchors(
        manualFoods: List<String>,
        pool: List<String>,
        sport: String,
        pain: List<String>,
        trainSummary: String?,
        hasIllness: Boolean,
    ): List<TimelineItem> {
        val out = mutableListOf<TimelineItem>()

        out += TimelineItem(
            time = "",
            type = PLAN_TYPE_MEAL,
            title = anchorTitle(manualFoods, pool, "燕麦牛奶 + 鸡蛋"),
            detail = "燕麦 60g + 牛奶 300ml + 鸡蛋 2 个",
            kcal = 0,
            duration = "约 15 分钟",
            why = TOMORROW_WHY,
            day = PLAN_DAY_TOMORROW,
            slot = PlanSlot.MORNING,
        )
        out += TimelineItem(
            time = "",
            type = PLAN_TYPE_MEAL,
            title = anchorTitle(manualFoods, pool, "主食 + 蛋白质 + 蔬菜"),
            detail = "米饭 200g + 鸡胸 150g + 一份绿叶菜",
            kcal = 0,
            duration = "约 20 分钟",
            why = TOMORROW_WHY,
            day = PLAN_DAY_TOMORROW,
            slot = PlanSlot.NOON,
        )
        out += TimelineItem(
            time = "",
            type = PLAN_TYPE_MEAL,
            title = anchorTitle(manualFoods, pool, "面食 + 牛肉 + 蔬菜"),
            detail = "面食一份 + 牛肉 120g + 一份蔬菜",
            kcal = 0,
            duration = "约 20 分钟",
            why = TOMORROW_WHY,
            day = PLAN_DAY_TOMORROW,
            slot = PlanSlot.EVENING,
        )

        // 第 4 条（训练位）四选一，优先级与 [exerciseItem] 同口径：生病 / 疼痛 → 恢复优先。
        when {
            hasIllness || pain.isNotEmpty() -> out += TimelineItem(
                time = "",
                type = PLAN_TYPE_SLEEP,
                title = "恢复：补水 + 早睡",
                detail = if (pain.isEmpty()) {
                    "今天记录了不适，明天先恢复，不排力量训练"
                } else {
                    "疼痛/不适部位（${pain.joinToString("、")}）相关动作先避开"
                },
                kcal = 0,
                duration = "——",
                why = TOMORROW_WHY,
                day = PLAN_DAY_TOMORROW,
                slot = PlanSlot.TRAIN,
            )
            trainSummary != null -> out += TimelineItem(
                time = "",
                type = PLAN_TYPE_EXERCISE,
                title = trainSummary.substringBefore(" · ").ifBlank { "按本周计划练" },
                detail = "按本周计划明天的安排执行",
                kcal = 0,
                duration = "约 30 分钟",
                why = TOMORROW_WHY,
                day = PLAN_DAY_TOMORROW,
                slot = PlanSlot.TRAIN,
            )
            sport.isNotBlank() -> out += TimelineItem(
                time = "",
                type = PLAN_TYPE_EXERCISE,
                title = "按你的条件练",
                detail = "按你的条件练：${sport.lineSequence().joinToString("；")}",
                kcal = 0,
                duration = "约 30 分钟",
                why = TOMORROW_WHY,
                day = PLAN_DAY_TOMORROW,
                slot = PlanSlot.TRAIN,
            )
        }

        return out
    }

    /** 锚点标题：手动清单优先于常吃池（都为空时用既有的常见搭配），最多取 2 项。 */
    private fun anchorTitle(manual: List<String>, pool: List<String>, fallback: String): String = when {
        manual.isNotEmpty() -> manual.take(2).joinToString(" + ")
        pool.isNotEmpty() -> pool.take(2).joinToString(" + ")
        else -> fallback
    }

    /**
     * 食物建议明细：手动清单（资源清单，"我现在就有"）优先于常吃池
     *（F7「他吃过 = 他买得到」）；都为空时回落既有建议，不编食物。
     */
    private fun mealDetail(manual: List<String>, pool: List<String>, fallback: String): String =
        when {
            manual.isNotEmpty() -> "用你手头的：${manual.take(3).joinToString("、")}（按平常的量）"
            pool.isNotEmpty() -> "优先常吃：${pool.take(3).joinToString("、")}（按平常的量）"
            else -> fallback
        }

    /** 兜底计划的 note（迁移自原 `buildNote`）。 */
    private fun buildNote(s: TodaySummary): String = when {
        s.hasIllness -> "今天记录了不适，计划已改为清淡饮食，暂不安排高强度运动。"
        s.recordCount == 0 -> "今天还没有记录，下面按默认目标给出建议。"
        s.gap <= 0 -> "今天已达标，可以安排一次力量训练。"
        s.gap >= 1500 -> "缺口较大，晚餐和加餐分两次补上，运动先做低强度活动。"
        else -> "按当前缺口给出了具体数量和热量，餐后安排一次运动。"
    }

    // ------------------------------------------------------------------
    // 解析 / 序列化 / 落库
    // ------------------------------------------------------------------

    private data class TimelineJson(val items: List<TimelineItem>, val note: String)

    /**
     * 防御性解析 `plan_json`（模型/旧数据永远不可信）：
     * 1. 根是 JSONObject 且有 items 数组 → 新格式；
     * 2. 根是 JSONArray → 逐元素当 item（更老形态）；
     * 3. 单条 item 字段缺失/非法一律收敛为安全值；`why` ↔ `whyNow` 双字段名；
     * 4. items 为空 → 返回 null（交调用方兜底）。
     */
    private fun parseTimelineJson(json: String?): TimelineJson? {
        if (json.isNullOrBlank()) return null
        return when (val root = loadsLenient(json)) {
            is JSONObject -> {
                val arr = root.optJSONArray("items") ?: return null
                val items = itemsOf(arr)
                if (items.isEmpty()) return null
                // ⚠️ 用 optText 而非 optString：optString 对 JSON null 会返回字面字符串
                //    "null"（本文件的 optText 专门挡了 JSONObject.NULL），模型给
                //    "note": null 时计划底部会显示字面 "null"。同时按 MAX_NOTE_LEN 截断。
                //    —— 这一处顺带修掉一个既有 P3（note 未挡 JSON null）。
                TimelineJson(items, root.optText("note").take(MAX_NOTE_LEN))
            }
            is JSONArray -> {
                val items = itemsOf(root)
                if (items.isEmpty()) return null
                TimelineJson(items, "")
            }
            else -> null
        }
    }

    /**
     * items 数组 → 条目列表（防御性，模型输出永不可信）。
     *
     * 与 v1 的差别（问题 3 方案 C）：按 `day` **分日计数**，今天 ≤ [MAX_TODAY_ITEMS]、
     * 明天 ≤ [MAX_TOMORROW_ITEMS] —— 单看总数会让"模型把明天写成 10 条"把今天挤空。
     * 明天锚点另有三条硬归一：`time` 强制空串、`kcal` 强制 0、`slot` 认不出则整条丢弃。
     */
    private fun itemsOf(arr: JSONArray): List<TimelineItem> {
        val out = mutableListOf<TimelineItem>()
        var todayCount = 0
        var tomorrowCount = 0
        // 条数上限：parseTimelineJson → renderTimeline 在主线程逐项 inflate，
        // 模型啰嗦或 prompt 被注入时返回成百条 = 卡顿 / ANR / OOM。
        for (i in 0 until minOf(arr.length(), MAX_PLAN_ITEMS)) {
            val o = arr.optJSONObject(i) ?: continue
            // day 只认「次日」这一个额外值：模型给 0 / 3 / 乱码一律收敛回今天。
            val tomorrow = o.optInt("day", PLAN_DAY_TODAY) >= PLAN_DAY_TOMORROW
            val slot = if (tomorrow) resolveSlot(o.optText("slot"), o.optText("time")) else ""
            // 时段认不出来 → 丢弃该锚点：宁可少一条，也不把说不清时段的行塞进「明天」。
            if (tomorrow && slot == null) continue
            if (tomorrow) {
                if (tomorrowCount >= MAX_TOMORROW_ITEMS) continue
            } else if (todayCount >= MAX_TODAY_ITEMS) {
                continue
            }

            // 先截断再判空：长度上限对 title/detail 生效后再决定是否跳过该项。
            val title = o.optText("title").take(MAX_TITLE_LEN)
            val detail = o.optText("detail").take(MAX_DETAIL_LEN)
            if (title.isEmpty() && detail.isEmpty()) continue

            if (tomorrow) tomorrowCount++ else todayCount++
            out += TimelineItem(
                // 锚点不显示具体时刻：即使模型给了 time 也一律清空（渲染口径唯一）。
                time = if (tomorrow) "" else o.optText("time").take(5),
                type = o.optText("type").ifEmpty { PLAN_TYPE_HABIT }.take(16),
                title = title,
                detail = detail,
                // 明天的摄入还没发生 → 不采信模型给的数字（锚点也不可「记一笔」）。
                kcal = if (tomorrow) 0 else o.optInt("kcal", 0),
                duration = o.optText("duration").take(16),
                why = o.optText("why").ifEmpty { o.optText("whyNow") }.take(MAX_WHY_LEN),
                day = if (tomorrow) PLAN_DAY_TOMORROW else PLAN_DAY_TODAY,
                slot = slot.orEmpty(),
            )
        }
        return out
    }

    /**
     * `plan_json` → 给 AI 工具（`query_plan`）读的**条目明细**多行文本。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么摘要必须在**本文件**产出
     * ══════════════════════════════════════════════════════════════════════════
     * `plan_json` 的 schema 只在本文件定义（[parseTimelineJson] / [itemsOf]）。若在
     * `HealthAgent.queryPlan` 里另写一份解析，"条目字段叫什么、明天锚点怎么归一"
     * 就出现两处口径 —— 改一处必漏另一处，且编译期完全不可见（本仓最贵的一类 bug）。
     * 故这里直接**吃 [parseTimelineJson] 的输出**，不重新解析原始 JSON。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 修掉的硬伤（2026-10-07，AI 能力补齐 P0）
     * ══════════════════════════════════════════════════════════════════════════
     * `query_plan` 旧版只回一句 `note`：模型被问「我 12 点该吃什么」时拿不到任何条目，
     * 只能凭对话记忆编 —— 与「回答里引用的数字只能来自记录原文或工具返回」直接冲突。
     * 更刺眼的是 `PlanChangeWriter.locate` 的 KDoc 早就写着"`title` 是用户/模型眼里的
     * 条目身份（**`query_plan` 返回的就是它**）"，而旧版从不返回它。本次把这条隐含契约补齐。
     *
     * 输出口径（token 预算）：
     * - 今天每条一行：`时间 [类型] 标题：具体做法，时长，热量（为什么）`；
     * - 明天锚点**不逐条摊开**，只回一行条数摘要 —— 锚点本就是占位，摊开既冗余，
     *   又容易被模型误当成"已确定的安排"；
     * - 备注另起一行。
     *
     * @return 多行文本（**每行前置两空格**，供调用方拼在 `• date（来源）：` 之后）；
     *         `plan_json` 解析不出条目（旧数据 / 纯兜底文本）时返回 null →
     *         调用方回落 `note` 一句话。**不抛异常**。
     */
    fun itemsDigest(planJson: String?): String? {
        val parsed = runCatching { parseTimelineJson(planJson) }.getOrNull() ?: return null
        val today = parsed.items.filter { it.day != PLAN_DAY_TOMORROW }
        val tomorrow = parsed.items.size - today.size
        if (today.isEmpty() && tomorrow == 0) return null
        val lines = mutableListOf<String>()
        for (item in today) {
            val time = item.time.ifBlank { "--:--" }
            val dur = if (item.duration.isBlank()) "" else "，${item.duration}"
            val kcal = if (item.kcal > 0) "，${item.kcal} kcal" else ""
            val why = if (item.why.isBlank()) "" else "（${item.why}）"
            lines += "  $time [${planTypeLabel(item.type)}] ${item.title}：" +
                "${item.detail}$dur$kcal$why"
        }
        if (tomorrow > 0) {
            lines += "  （另有明天 $tomorrow 条占位锚点；锚点内容当天才按真实数据细化）"
        }
        if (parsed.note.isNotBlank()) lines += "  备注：${parsed.note}"
        return lines.joinToString("\n")
    }

    /**
     * 计划条目类型的短标签（`query_plan` 摘要用；取值域见 [PROMPT_PLAN] 的 type 约定）。
     *
     * 刻意用单字「吃 / 动 / 睡」而不是「饮食 / 运动 / 睡眠」：摘要可能一次回 7 天计划、
     * 每天 6 条，逐字节省下来的都是上下文预算；单字仍可零歧义区分三档。
     */
    private fun planTypeLabel(type: String): String = when (type) {
        PLAN_TYPE_MEAL -> "吃"
        PLAN_TYPE_EXERCISE -> "动"
        PLAN_TYPE_SLEEP -> "睡"
        else -> "其他"
    }

    /**
     * 归一化「明天」锚点的时段键（模型输出永不可信）。
     *
     * 容忍三种形态，按优先级：
     * 1. 规范 ASCII 键（`morning` / `noon` / `evening` / `train`）；
     * 2. 中文时段名（早 / 午 / 中 / 晚 / 练 / 运动）；
     * 3. 只写了 `time` 时刻 → 按小时折算（< 10 早 / < 15 午 / 其余 晚）。
     *
     * @return [PlanSlot] 之一；**null = 认不出** → 调用方丢弃该条（见 [itemsOf]）。
     */
    private fun resolveSlot(rawSlot: String, rawTime: String): String? {
        val s = rawSlot.lowercase(Locale.US)
        if (PlanSlot.MORNING in s || "早" in rawSlot) return PlanSlot.MORNING
        if (PlanSlot.NOON in s || "午" in rawSlot || "中" in rawSlot) return PlanSlot.NOON
        if (PlanSlot.EVENING in s || "晚" in rawSlot) return PlanSlot.EVENING
        if (PlanSlot.TRAIN in s || "练" in rawSlot || "运动" in rawSlot) return PlanSlot.TRAIN
        val hour = rawTime.take(2).toIntOrNull() ?: return null
        return when {
            hour < 10 -> PlanSlot.MORNING
            hour < 15 -> PlanSlot.NOON
            else -> PlanSlot.EVENING
        }
    }

    /** 安全取文本：缺失 / JSON null 一律返回空串（避免 "null" 字符串混入）。 */
    private fun JSONObject.optText(name: String): String {
        val v = opt(name) ?: return ""
        if (v === JSONObject.NULL) return ""
        return v.toString().trim()
    }

    /** [PlanResult] → `plan_json`（与 prompt 约定 schema 一致，便于重新加载）。 */
    private fun serialize(result: PlanResult): String {
        val root = JSONObject()
        root.put("version", PLAN_JSON_VERSION)
        root.put("generated_at", result.generatedAt)
        root.put("source", result.source)
        val arr = JSONArray()
        for (item in result.items) {
            val o = JSONObject()
            o.put("day", item.day)
            o.put("time", item.time)
            o.put("slot", item.slot)
            o.put("type", item.type)
            o.put("title", item.title)
            o.put("detail", item.detail)
            o.put("kcal", item.kcal)
            o.put("duration", item.duration)
            o.put("why", item.why)
            arr.put(o)
        }
        root.put("items", arr)
        root.put("note", result.note)
        return root.toString()
    }

    /** [PlanResult] → 纯文本（落 `content`，供导出/回退展示）。 */
    private fun renderText(result: PlanResult): String = result.items.joinToString("\n") { item ->
        listOf(item.time, item.title).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** 落 `daily_plans`（复用现成 PlanDao，零 schema 改动）。 */
    private suspend fun persist(date: String, result: PlanResult, targetKcal: Int) {
        runCatching {
            db.planDao().upsertPlan(
                DailyPlanEntity(
                    date = date,
                    targetKcal = targetKcal,
                    planJson = serialize(result),
                    content = renderText(result),
                    generatedAt = result.generatedAt,
                    source = result.source,
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private suspend fun goalInt(metric: String, fallback: Int): Int =
        db.goalDao().getByMetric(metric)?.targetValue?.toInt()?.takeIf { it > 0 } ?: fallback

    /** 日界线小时。**唯一夹取入口** = [dayStartHourOf]（禁止在别处再写 coerceIn）。 */
    private suspend fun dayStartHour(): Int =
        dayStartHourOf(db.settingsDao().get(SettingsKeys.DAY_START))

    /** 主目标名（增重 / 减重 / 保持）。prompt 上下文，措辞与 TodaySummary 一致。 */
    private fun goalName(idx: Int): String = when (idx) {
        1 -> "减重"
        2 -> "保持"
        3 -> "自定义（见下方用户自述）"
        else -> "增重"
    }

    /** 当前本地时间 `HH:mm`（24 小时制两位补零，与 TodaySummary.lines[0] 同口径）。 */
    private fun currentHhmm(): String {
        val t = java.time.LocalTime.now()
        return String.format(Locale.US, "%02d:%02d", t.hour, t.minute)
    }

    /** 若固定时段已过去则用"现在"，保证本地时间轴不排"过去"的整点。 */
    private fun slotOrNow(slot: String, now: String): String = if (slot <= now) now else slot

    private fun sleepSlot(bedTime: String, now: String): String =
        slotOrNow(bedTime.trim().ifBlank { "23:00" }, now)

    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.US, "%.1f", v)
}
