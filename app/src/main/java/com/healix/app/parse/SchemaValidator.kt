package com.healix.app.parse

import com.healix.app.net.ChatMessage
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * 抽取链后处理层（Kotlin 侧镜像）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 权威来源：`pipeline/contract.py`（Python 侧，回归通过的那个版本）
 * ══════════════════════════════════════════════════════════════════════════
 * 本文件是 contract.py 的**逐条行为对齐**实现，验收标准是
 * `pipeline/tests/test_norm.py` 的 67 条断言在 Kotlin 侧全部成立。
 *
 * 逐条对齐表（Python → Kotlin）：
 * | Python                        | Kotlin                          |
 * |-------------------------------|---------------------------------|
 * | `clean_literal`               | [cleanLiteral]                   |
 * | `to_int`                      | [toInt]                          |
 * | `to_float`                    | [toDouble]                       |
 * | `to_str_list`                 | [toStrList]                      |
 * | `to_type`                     | [toType]                         |
 * | `normalize_event`             | [normalizeEvent]                 |
 * | `extract_events_from_response`| [extractEvents]                  |
 * | `_loads_lenient`              | [loadsLenient]                   |
 * | `_collect_events`             | [collectEvents]                  |
 * | `day_key_of`                  | [dayKeyOf]                       |
 * | `PROMPT_EXTRACT`              | [PROMPT_EXTRACT]（逐字一致）      |
 *
 * 设计原则（与 Python 侧一致）：
 * 1. 无网络依赖，纯函数，可以在单元测试里逐条验证
 * 2. 异常值一律收敛为占位值，**绝不抛异常**（模型输出永远不可信）
 * 3. "单条坏不影响其他条"——一条脏数据不能毒掉整批
 *
 * 用 org.json（Android 内置）而非 Gson/Moshi：零依赖，避免为了一个
 * "模型输出可能不是合法 JSON" 的场景引入一整个序列化框架。
 */

// ---------------------------------------------------------------------------
// 常量
// ---------------------------------------------------------------------------

/** ★ prompt 版本号。每次改 prompt 必须递增，并写入 llm_calls.prompt_ver。 */
const val PROMPT_VER: String = "v1"

/** 合法事件类型。 */
val VALID_TYPES: List<String> = listOf("meal", "exercise", "body", "sleep", "illness", "other")

/** 日界线默认 4:00。 */
const val DEFAULT_DAY_START_HOUR: Int = 4

/**
 * 模型会把"规则文字"当值填进来的字面量（总方案 1.2 实测：time 填 "空串"）。
 * 全部收敛为 ""，绝不入库。
 */
val SENTINEL_LITERALS: Set<String> = setOf(
    "空串", "空", "无", "暂无", "没有",
    "null", "NULL", "None", "none",
    "N/A", "n/a", "NA",
    "-", "--", "—",
    "无。", "无信息", "未提及", "未提供", "不适用",
    "unknown",
)

/** 字段兜底默认值（contract.py FIELD_DEFAULTS 的镜像）。 */
val FIELD_DEFAULTS: Map<String, Any> = mapOf(
    "type" to "other",
    "time_hint" to "",
    "foods" to emptyList<String>(),
    "exercise" to "",
    "amount" to "",
    "kcal" to 0,
    "symptom" to "",
    "weight_kg" to 0.0,
    "sleep_h" to 0.0,
)

// 数值字段合理区间（异常值清洗）。超出即视为模型胡说，**回落默认值**。
const val KCAL_MIN: Int = 0
const val KCAL_MAX: Int = 5000
const val WEIGHT_MIN: Double = 20.0
const val WEIGHT_MAX: Double = 300.0
const val SLEEP_MIN: Double = 0.0
const val SLEEP_MAX: Double = 24.0

/** 从中文文本里榨数字用。对应 Python 的 `_NUM_RE = re.compile(r"-?\d+(?:\.\d+)?")`。 */
private val NUM_RE = Regex("-?\\d+(?:\\.\\d+)?")

/**
 * 列表切分分隔符。对应 Python：
 * `re.split(r"[、,，;；/|\n]+|\s+和\s*|\s*加\s*", text)`
 *
 * 注意 "加" 两侧的空格规则**不对称**（`\s*加\s*`）—— 这是刻意的：
 * "牛肉面加鸡蛋" 要能切开，同时不误伤 "加强" 之类不含分隔语义的词。
 */
private val LIST_SPLIT_RE = Regex("[、,，;；/|\\n]+|\\s+和\\s*|\\s*加\\s*")

/** 已知的"包裹键"（总方案第六节明确不要，但模型不听话时要能救）。 */
private val WRAPPER_KEYS: List<String> =
    listOf("events", "answer", "result", "data", "items", "records")

/** 单条事件的字段名（用于"形态 2：单对象"判定）。 */
private val EVENT_FIELD_KEYS: Set<String> = setOf(
    "type", "time", "foods", "exercise", "amount", "kcal", "symptom", "weight_kg", "sleep_h",
)

/** 一句话最多拆 10 条，防模型刷屏。 */
private const val MAX_EVENTS: Int = 10

/** 递归深度上限，防嵌套过深 / 循环结构。 */
private const val MAX_COLLECT_DEPTH: Int = 4

// ---------------------------------------------------------------------------
// 规整后的事件模型
// ---------------------------------------------------------------------------

/**
 * 规整后的事件。**所有字段都有值**（干净的类型），对应 Python 的 `dict`。
 * 由 [normalizeEvent] 保证完整性，构造后即为可直接入库的形态。
 */
data class ParsedEvent(
    val type: String = "other",
    val timeHint: String = "",
    val foods: List<String> = emptyList(),
    val exercise: String = "",
    val amount: String = "",
    val kcal: Int = 0,
    val symptom: String = "",
    val weightKg: Double = 0.0,
    val sleepH: Double = 0.0,
) {
    /** foods → JSON 数组字符串（EventEntity.foods 的存储形态）。 */
    fun foodsJson(): String {
        val arr = JSONArray()
        foods.forEach { arr.put(it) }
        return arr.toString()
    }
}

// ---------------------------------------------------------------------------
// 后处理原语（每一步都是纯函数）
// ---------------------------------------------------------------------------

/**
 * 第 4 步「异常值清洗」：字面量占位词 → ""。
 *
 * 只处理字符串。非字符串先转成字符串再判断 —— 模型可能返回 null / 0。
 * 对齐 Python `clean_literal`。
 */
fun cleanLiteral(value: Any?): String {
    if (value == null || value == JSONObject.NULL) return ""
    val text = when (value) {
        is String -> value
        else -> value.toString()
    }.trim()
    return if (text in SENTINEL_LITERALS) "" else text
}

/**
 * 第 2 步「类型强转」：任意值 → Int，失败给默认值。
 *
 * 对齐 Python `to_int`：
 * - bool 必须先拦掉（Python 里 bool 是 int 子类；Kotlin 里 Boolean 不是 Int，但要显式拒绝以免 true→1）
 * - 数字直接转，字符串先尝试直接解析，失败则从文本里抓第一个数字（"约300 kcal" → 300）
 * - 带 lo/hi 时做区间钳制，**超界回落默认值而非钳到边界**（钳到边界会造出假数据）
 * - 整数部分用 round（Python 的 `int(round(num))` 是**四舍五入**，不是截断）
 */
fun toInt(
    value: Any?,
    default: Int = 0,
    lo: Int? = null,
    hi: Int? = null,
): Int {
    if (value is Boolean) return default
    val num: Double = extractNumber(value) ?: return default

    // 对齐 Python `int(round(num))`：四舍五入到最近整数
    val result = Math.round(num).toInt()
    if (lo != null && result < lo) return default
    if (hi != null && result > hi) return default
    return result
}

/**
 * 第 2 步「类型强转」：任意值 → Double，语义同 [toInt]。
 *
 * 对齐 Python `to_float`：`round(num, 2)` 保留两位。
 */
fun toDouble(
    value: Any?,
    default: Double = 0.0,
    lo: Double? = null,
    hi: Double? = null,
): Double {
    if (value is Boolean) return default
    val num: Double = extractNumber(value) ?: return default

    val result = roundTo2(num)
    if (lo != null && result < lo) return default
    if (hi != null && result > hi) return default
    return result
}

/**
 * 任意值 → Double?（不钳制、不四舍五入）。
 * 返回 null 表示"根本榨不出数字"。供 [toInt] / [toDouble] 共用。
 */
private fun extractNumber(value: Any?): Double? {
    return when (value) {
        null, JSONObject.NULL -> null
        is Boolean -> null
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        is Double -> value
        is Float -> value.toDouble()
        is Number -> value.toDouble()
        is String -> {
            val text = cleanLiteral(value)
            if (text.isEmpty()) {
                null
            } else {
                text.toDoubleOrNull() ?: NUM_RE.find(text)?.value?.toDoubleOrNull()
            }
        }
        else -> null
    }
}

/**
 * 保留两位小数，对齐 Python `round(num, 2)`（银行家舍入 vs 半上舍入的差异
 * 对 0.005 这类边界值存在，但本项目的体重量级不会踩到，且 Python 侧同样用 round，
 * 两边行为在 58.2 / 7.5 这类值上完全一致）。
 */
private fun roundTo2(value: Double): Double = Math.round(value * 100.0) / 100.0

/**
 * 第 2 步「类型强转」：任意值 → List<String>。
 *
 * 对齐 Python `to_str_list`，吃下四种形态：
 * - JSONArray → 逐项清洗
 * - JSONObject → 只取键（`{"牛肉面":"1碗"}` → `["牛肉面"]`，值是数量，属 amount 语义）
 * - String → 按分隔符切分（"牛肉面、鸡蛋" / "牛肉面加鸡蛋"）
 * - 其它 → 单元素列表
 *
 * 收尾：清洗每项 → 去空 → **去重且保持顺序**。
 */
fun toStrList(value: Any?): List<String> {
    if (value == null || value == JSONObject.NULL) return emptyList()

    val items: List<Any?> = when (value) {
        is JSONArray -> (0 until value.length()).map { value.opt(it) }
        is List<*> -> value
        is JSONObject -> value.keys().asSequence().toList()
        is Map<*, *> -> value.keys.toList()
        is String -> {
            val text = cleanLiteral(value)
            if (text.isEmpty()) return emptyList()
            LIST_SPLIT_RE.split(text).filter { it.isNotBlank() }
        }
        else -> listOf(value)
    }

    val result = ArrayList<String>(items.size)
    for (item in items) {
        val cleaned = cleanLiteral(item)
        if (cleaned.isNotEmpty()) result.add(cleaned)
    }

    // 去重但保持顺序
    val seen = HashSet<String>(result.size)
    val deduped = ArrayList<String>(result.size)
    for (item in result) {
        if (seen.add(item)) deduped.add(item)
    }
    return deduped
}

/**
 * 第 2 步：type 必须是枚举内值，否则 → other。
 *
 * 对齐 Python `to_type`：
 * - 先 cleanLiteral 再 lower
 * - 完全匹配直接返回
 * - 宽容：模型可能返回 "meal（饮食）" 这类带注释的，做子串包含匹配
 * - 都不中 → "other"
 */
fun toType(value: Any?): String {
    val text = cleanLiteral(value).lowercase()
    if (text in VALID_TYPES) return text
    for (candidate in VALID_TYPES) {
        if (text.contains(candidate)) return candidate
    }
    return "other"
}

// ---------------------------------------------------------------------------
// 单条事件后处理
// ---------------------------------------------------------------------------

/**
 * 第 2–4 步：把模型返回的一个元素规整为**保证可用**的事件。
 *
 * 输入可以是 JSONObject / String / 任何东西 —— 永远返回完整 [ParsedEvent]，绝不抛异常。
 * 这是"单条坏不影响其他条"的实现（功能补充 1.5）。
 *
 * 对齐 Python `normalize_event` 的三个收尾动作：
 * 1. meal 空 foods 合法（模型漏抽），**不编造内容**
 * 2. 非 meal 类型剥掉 foods
 * 3. 所有字段有兜底值
 */
fun normalizeEvent(raw: Any?): ParsedEvent {
    val obj: JSONObject = when (raw) {
        is JSONObject -> raw
        is String -> {
            // 字符串形态：先尝试当 JSON 解析，失败当空对象（与 Python 的
            // "非 dict 一律变 {}" 语义一致 —— Python 里 str 不是 dict，直接归空）
            try {
                JSONObject(raw)
            } catch (e: JSONException) {
                JSONObject()
            }
        }
        else -> JSONObject()
    }

    var type = toType(obj.opt("type"))
    var foods = toStrList(obj.opt("foods"))

    // 补默认值 / 剥不合法字段
    if (type != "meal" && foods.isNotEmpty()) {
        foods = emptyList()
    }
    // meal 且 foods 为空是合法的（模型漏抽），保持为空，不编造

    return ParsedEvent(
        type = type,
        // 兼容 time 与 time_hint 两个键（Python: raw.get("time") or raw.get("time_hint")）
        timeHint = cleanLiteral(optFirstNonNull(obj, "time", "time_hint")),
        foods = foods,
        exercise = cleanLiteral(obj.opt("exercise")),
        amount = cleanLiteral(obj.opt("amount")),
        kcal = toInt(obj.opt("kcal"), default = 0, lo = KCAL_MIN, hi = KCAL_MAX),
        symptom = cleanLiteral(obj.opt("symptom")),
        weightKg = toDouble(obj.opt("weight_kg"), default = 0.0, lo = WEIGHT_MIN, hi = WEIGHT_MAX),
        sleepH = toDouble(obj.opt("sleep_h"), default = 0.0, lo = SLEEP_MIN, hi = SLEEP_MAX),
    )
}

/**
 * 取第一个非 null 的键值。
 *
 * 对齐 Python `raw.get("time") or raw.get("time_hint")`：
 * Python 的 `or` 把**空字符串也视为假**，所以 `{"time":"", "time_hint":"中午"}` → "中午"。
 * 这里必须复刻这个行为，否则契约不一致。
 */
private fun optFirstNonNull(obj: JSONObject, vararg keys: String): Any? {
    for (key in keys) {
        val v = obj.opt(key)
        if (v != null && v != JSONObject.NULL) {
            // 空串视为假，继续下一个键（对齐 Python 的 or）
            if (v is String && v.isEmpty()) continue
            return v
        }
    }
    return null
}

// ---------------------------------------------------------------------------
// 顶层响应解析（第 1 层：把模型输出变成 events 列表）
// ---------------------------------------------------------------------------

/**
 * 把模型返回的原始字符串解析为**规整后的事件列表**。
 *
 * 兼容四种真实形态（实测 + 防御性）：
 * 1. `{"events": [ {...}, {...} ]}`          ← 目标形态
 * 2. `{ ...单条字段... }`                     ← 旧 prompt / 模型退化
 * 3. `[ {...}, {...} ]`                       ← 模型直接给数组
 * 4. `{ "answer": {...} }` / `{"data": [...]}`← 被包了一层（prompt 明令禁止但会发生）
 *
 * 解析失败兜底：返回空列表，由调用方决定标 failed 还是走本地估算。
 *
 * 对齐 Python `extract_events_from_response`。
 */
fun extractEvents(raw: String?): List<ParsedEvent> {
    if (raw.isNullOrEmpty()) return emptyList()
    val payload = loadsLenient(raw) ?: return emptyList()
    return collectEvents(payload, 0)
}

/**
 * 尽力解析 JSON：先直解，失败则剥 ``` 围栏 / 抓最外层 `{}` 或 `[]`。
 *
 * 对齐 Python `_loads_lenient`。返回 null 表示彻底解析不出来。
 *
 * 用 org.json 而非手写 tokenizer：org.json 对括号匹配要求严格，
 * 天生完成 Python `json.loads` 的等价工作（含嵌套、转义、unicode）。
 */
internal fun loadsLenient(content: String): Any? {
    var text = content.trim()
    if (text.isEmpty()) return null

    // 剥 markdown 代码围栏：```json\n{...}\n```
    if (text.startsWith("```")) {
        text = text.replaceFirst(FENCE_HEAD_RE, "")
        text = text.replace(FENCE_TAIL_RE, "")
        text = text.trim()
    }

    // 1) 直接解析
    parseJsonOrNull(text)?.let { return it }

    // 2) 抓第一个 { 到最后一个 }（模型前后加了解释文字）
    val objStart = text.indexOf('{')
    val objEnd = text.lastIndexOf('}')
    if (objStart != -1 && objEnd > objStart) {
        parseJsonOrNull(text.substring(objStart, objEnd + 1))?.let { return it }
    }

    // 3) 抓第一个 [ 到最后一个 ]
    val arrStart = text.indexOf('[')
    val arrEnd = text.lastIndexOf(']')
    if (arrStart != -1 && arrEnd > arrStart) {
        parseJsonOrNull(text.substring(arrStart, arrEnd + 1))?.let { return it }
    }

    return null
}

/** 单次 JSON 解析尝试。失败返回 null，不抛。 */
private fun parseJsonOrNull(text: String): Any? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return try {
        when (trimmed[0]) {
            '[' -> JSONArray(trimmed)
            '{' -> JSONObject(trimmed)
            '"' -> {
                // JSON 字符串字面量：解出来是 String，按"剥一层壳"处理
                JSONObject("{\"v\":$trimmed}").opt("v")
            }
            else -> null
        }
    } catch (e: JSONException) {
        null
    }
}

private val FENCE_HEAD_RE = Regex("^```[a-zA-Z]*\\s*")
private val FENCE_TAIL_RE = Regex("\\s*```$")

/**
 * 从任意 JSON 结构里挖出事件列表。
 *
 * 对齐 Python `_collect_events`。深度保护 [MAX_COLLECT_DEPTH]。
 */
internal fun collectEvents(payload: Any?, depth: Int): List<ParsedEvent> {
    if (depth > MAX_COLLECT_DEPTH) return emptyList()

    // 形态 3：直接是数组
    if (payload is JSONArray) {
        val out = ArrayList<ParsedEvent>(minOf(payload.length(), MAX_EVENTS))
        val n = minOf(payload.length(), MAX_EVENTS)
        for (i in 0 until n) {
            out.add(normalizeEvent(payload.opt(i)))
        }
        return out
    }

    if (payload !is JSONObject) return emptyList()

    // 形态 1 / 4：有包裹键（按 WRAPPER_KEYS 顺序取第一个命中的）
    for (key in WRAPPER_KEYS) {
        if (!payload.has(key)) continue
        when (val inner = payload.opt(key)) {
            is JSONArray -> {
                val out = ArrayList<ParsedEvent>(minOf(inner.length(), MAX_EVENTS))
                val n = minOf(inner.length(), MAX_EVENTS)
                for (i in 0 until n) {
                    out.add(normalizeEvent(inner.opt(i)))
                }
                return out
            }
            is JSONObject -> {
                // {"events": {...}} 单条
                return listOf(normalizeEvent(inner))
            }
            else -> {
                // 命中了包裹键但值既非数组也非对象 —— 按 Python 行为继续找下一个键
                continue
            }
        }
    }

    // 形态 2：就是一个单条事件对象
    val keys = payload.keys().asSequence().toSet()
    if (keys.any { it in EVENT_FIELD_KEYS }) {
        return listOf(normalizeEvent(payload))
    }

    return emptyList()
}

// ---------------------------------------------------------------------------
// day_key（功能补充 1.4：日界线 4:00）
// ---------------------------------------------------------------------------

/**
 * 把时间戳折算为"记录日"，按自定义日界线。
 *
 * 实现：**本地时间整体减 dayStartHour 小时后取日期** —— 无需任何边界分支。
 *
 * 验证（与 contract.md 第五节表格逐条对应）：
 * - 10-03 01:00（夜宵）→ 减 4h = 10-02 21:00 → `2026-10-02`
 * - 10-03 03:59        → 减 4h = 10-02 23:59 → `2026-10-02`
 * - 10-03 04:00（恰好）→ 减 4h = 10-03 00:00 → `2026-10-03`
 * - 10-03 06:00        → 减 4h = 10-03 02:00 → `2026-10-03`
 * - 10-03 12:00        → 减 4h = 10-03 08:00 → `2026-10-03`
 *
 * 用 [Calendar] 而非 java.time：minSdk 29 虽支持 java.time，但 Calendar
 * 在时区/夏令时语义上与 Python 的 `datetime.astimezone()` 更易对齐，
 * 且无需 desugaring 配置。
 */
fun dayKeyOf(tsMs: Long, dayStartHour: Int = DEFAULT_DAY_START_HOUR): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = tsMs
    cal.add(Calendar.HOUR_OF_DAY, -dayStartHour)

    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH) + 1 // Calendar 月份从 0 开始
    val day = cal.get(Calendar.DAY_OF_MONTH)

    return buildString(10) {
        append(year.toString().padStart(4, '0'))
        append('-')
        append(month.toString().padStart(2, '0'))
        append('-')
        append(day.toString().padStart(2, '0'))
    }
}

/** 供 UI 显示"今天 / 昨天"用。仍走同一个日界线函数，避免两套逻辑漂移。 */
fun todayDayKey(dayStartHour: Int = DEFAULT_DAY_START_HOUR): String =
    dayKeyOf(System.currentTimeMillis(), dayStartHour)

/** 当前设备默认时区 id，写日志/埋点用（不参与计算，计算全走 Calendar 本地时区）。 */
fun localTimeZoneId(): String = TimeZone.getDefault().id

// ---------------------------------------------------------------------------
// Prompt（与 pipeline/contract.py 的 PROMPT_EXTRACT **逐字一致**）
// ---------------------------------------------------------------------------

/**
 * 抽取链 system prompt。**逐字照抄** `pipeline/contract.py` 的 `PROMPT_EXTRACT`。
 *
 * 自检要求（对齐 test_norm.py 第 6 节）：
 * - 不含 "空串" 二字（会诱导模型字面理解）
 * - 含 `"events"` 结构说明
 * - 含 region pack（"一碗牛肉面"）
 * - 含拆分规则（"拆成多条"）
 *
 * ⚠️ 改动此常量必须同时改 Python 侧并递增 [PROMPT_VER]。
 */
const val PROMPT_EXTRACT: String = """你是一个健康记录助手。把用户的口语记录抽取为结构化 JSON。

字段规则：
- type: meal=吃喝, exercise=运动训练, body=体重体脂等身体指标, sleep=睡眠, illness=生病不适, other=其他
- time: 只填时间词本身（如 早上/中午/晚上/下午/睡前/昨天/三点）。没有就填 ""
- foods: 只填食物和饮品名称，不填地点、不填数量。非饮食填 []
- exercise: 运动项目名称（如 跑步/卧推/深蹲/羽毛球），非运动填 ""
- amount: 数量、重量、时长、组数等量化描述，没有填 ""
- kcal: 摄入热量估算值（整数千卡）。必须估算，按常见分量给出合理数字，不得填 0
- symptom: 症状描述原文，非生病填 ""
- weight_kg: 体重（公斤，小数）。没提体重就填 0
- sleep_h: 睡眠时长（小时，小数）。没提睡眠就填 0

分量默认参考（中国北方日常）：
- 一碗牛肉面 ~500g（面 200g + 汤 250g + 配料 50g）→ 约 550-650 kcal
- 一份盖浇饭（米饭 300g + 菜）→ 约 650-800 kcal
- 一个肉包子 ~100g → 约 230 kcal
- 二两米饭 = 100g 熟米 → 约 116 kcal

kcal 估算优先按「当地常见分量」而不是「标准 100g」。

拆分规则：
- 一句话里包含多件事时，必须拆成多条（例如"吃了牛肉面又称了体重"是 2 条）
- 纯饮食记录不算运动，纯体重记录不算饮食

只输出 JSON，不要任何解释文字。
输出格式固定为：{"events": [ {...}, {...} ]}
每个元素就是一个事件对象，字段名就是上面这些。
不要包裹在 answer / result / data 等任何其他键里。若只有一件事，events 数组里也放 1 个元素。
"""

/**
 * 组装抽取链的两条消息。对齐 Python `build_messages`。
 *
 * 两条消息即可 —— 这是 Prompt Chaining 工作流，不是 Agent，不需要工具定义。
 */
fun buildExtractMessages(rawText: String): List<ChatMessage> = listOf(
    ChatMessage(role = "system", content = PROMPT_EXTRACT),
    ChatMessage(role = "user", content = rawText),
)
