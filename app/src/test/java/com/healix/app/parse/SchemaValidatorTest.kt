package com.healix.app.parse

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 后处理层离线自测（Kotlin 侧）。
 *
 * 这是 `pipeline/tests/test_norm.py` 的**逐条镜像** —— 两边的断言集合必须一致。
 * 存在的意义：证明 Kotlin 实现与 Python 权威实现的行为没有漂移。
 * 任何一条失败都说明契约被破坏，必须同步两侧。
 *
 * ⚠️ 依赖 `org.json`。Android 内置该库，但纯 JVM 单元测试（testDebugUnitTest）
 * 找不到它 —— app/build.gradle 的 testImplementation 里加了 `org.json:json`。
 *
 * ⚠️ day_key 相关断言假设设备时区为 Asia/Shanghai（UTC+8）。
 * 测试内显式设置 TimeZone，避免 CI runner（UTC）上结果不同。
 */
class SchemaValidatorTest {

    // ------------------------------------------------------------------
    // 1. 字面量清洗（总方案 1.2 实测错误：time 填 "空串"）
    // ------------------------------------------------------------------

    @Test
    fun cleanLiteral_占位词收敛为空串() {
        assertEquals("", cleanLiteral("空串"))
        assertEquals("", cleanLiteral("null"))
        assertEquals("", cleanLiteral("无"))
        assertEquals("", cleanLiteral(""))
        assertEquals("", cleanLiteral(null))
    }

    @Test
    fun cleanLiteral_保留正常值并去首尾空格() {
        assertEquals("中午", cleanLiteral(" 中午 "))
        assertEquals("三 点", cleanLiteral("三 点"))
    }

    // ------------------------------------------------------------------
    // 2. 类型强转
    // ------------------------------------------------------------------

    @Test
    fun toInt_强转与钳制() {
        assertEquals(300, toInt("300"))
        assertEquals(300, toInt("约300 kcal"))
        assertEquals(301, toInt(300.7))       // Python int(round(300.7)) = 301
        assertEquals(0, toInt("很多"))
        assertEquals(0, toInt(true))          // bool 必须拦截
        assertEquals(0, toInt(99999, default = 0, lo = 0, hi = 5000)) // 超界回落默认值
        assertEquals(0, toInt(-5, default = 0, lo = 0))
        assertEquals(0, toInt(null))
    }

    @Test
    fun toDouble_强转与区间() {
        assertEquals(58.2, toDouble("58.2"), 0.0001)
        assertEquals(58.2, toDouble("体重58.2公斤"), 0.0001)
        assertEquals(0.0, toDouble(500.0, default = 0.0, lo = 20.0, hi = 300.0), 0.0001)
    }

    @Test
    fun toStrList_四种输入形态() {
        assertEquals(listOf("牛肉面", "鸡蛋"), toStrList(listOf("牛肉面", "鸡蛋")))
        assertEquals(listOf("牛肉面", "鸡蛋"), toStrList("牛肉面、鸡蛋"))
        assertEquals(listOf("牛肉面", "鸡蛋"), toStrList("牛肉面加鸡蛋"))
        assertEquals(emptyList<String>(), toStrList("空串"))
        assertEquals(emptyList<String>(), toStrList(null))

        // JSONObject 取键
        val obj = JSONObject().apply { put("牛肉面", "1碗") }
        assertEquals(listOf("牛肉面"), toStrList(obj))

        // 去重保序
        assertEquals(listOf("面", "蛋"), toStrList(listOf("面", "蛋", "面")))
        // 过滤空项
        assertEquals(listOf("面", "蛋"), toStrList(listOf("面", "", "  ", "蛋")))
    }

    @Test
    fun toType_枚举收敛() {
        assertEquals("meal", toType("meal"))
        assertEquals("meal", toType("meal（饮食）"))
        assertEquals("meal", toType("MEAL"))
        assertEquals("other", toType("吃饭"))
        assertEquals("other", toType(""))
    }

    // ------------------------------------------------------------------
    // 3. 单条事件规整
    // ------------------------------------------------------------------

    @Test
    fun normalizeEvent_完整规整() {
        val raw = JSONObject().apply {
            put("type", "meal")
            put("time", "空串")
            put("foods", "牛肉面加鸡蛋")
            put("kcal", "600")
        }
        val ev = normalizeEvent(raw)

        assertEquals("meal", ev.type)
        assertEquals("", ev.timeHint)             // 占位词被清洗
        assertEquals(listOf("牛肉面", "鸡蛋"), ev.foods)
        assertEquals(600, ev.kcal)
    }

    @Test
    fun normalizeEvent_所有字段都有兜底值() {
        val ev = normalizeEvent(JSONObject())
        assertEquals("other", ev.type)
        assertEquals("", ev.timeHint)
        assertEquals(emptyList<String>(), ev.foods)
        assertEquals("", ev.exercise)
        assertEquals("", ev.amount)
        assertEquals(0, ev.kcal)
        assertEquals("", ev.symptom)
        assertEquals(0.0, ev.weightKg, 0.0001)
        assertEquals(0.0, ev.sleepH, 0.0001)
    }

    @Test
    fun normalizeEvent_非对象输入不崩() {
        assertEquals("other", normalizeEvent("这是个字符串").type)
        assertEquals(0, normalizeEvent(null).kcal)
    }

    @Test
    fun normalizeEvent_非meal类型剥掉foods() {
        val raw = JSONObject().apply {
            put("type", "exercise")
            put("exercise", "跑步")
            put("foods", org.json.JSONArray().put("面"))
        }
        assertEquals(emptyList<String>(), normalizeEvent(raw).foods)
    }

    // ------------------------------------------------------------------
    // 4. 顶层响应解析（四种形态）
    // ------------------------------------------------------------------

    @Test
    fun extractEvents_形态1_目标形态() {
        val r = extractEvents("""{"events":[{"type":"meal","foods":["面"],"kcal":600}]}""")
        assertEquals(1, r.size)
        assertEquals(listOf("面"), r[0].foods)
    }

    @Test
    fun extractEvents_形态2_单对象() {
        val r = extractEvents("""{"type":"body","weight_kg":58.2}""")
        assertEquals(1, r.size)
        assertEquals(58.2, r[0].weightKg, 0.0001)
    }

    @Test
    fun extractEvents_形态3_数组() {
        val r = extractEvents("""[{"type":"meal"},{"type":"exercise"}]""")
        assertEquals(2, r.size)
    }

    @Test
    fun extractEvents_形态4_包裹() {
        val r = extractEvents("""{"answer":{"type":"sleep","sleep_h":7.5}}""")
        assertEquals(1, r.size)
        assertEquals(7.5, r[0].sleepH, 0.0001)
    }

    @Test
    fun extractEvents_剥markdown围栏() {
        val r = extractEvents("```json\n{\"events\":[{\"type\":\"meal\"}]}\n```")
        assertEquals(1, r.size)
    }

    @Test
    fun extractEvents_抓最外层对象() {
        val r = extractEvents("""好的，这是结果：{"events":[{"type":"other"}]} 完毕""")
        assertEquals(1, r.size)
        assertEquals("other", r[0].type)
    }

    @Test
    fun extractEvents_脏输入不崩() {
        assertEquals(emptyList<ParsedEvent>(), extractEvents(""))
        assertEquals(emptyList<ParsedEvent>(), extractEvents("我今天很开心"))
        assertEquals(emptyList<ParsedEvent>(), extractEvents(null))
        assertEquals(emptyList<ParsedEvent>(), extractEvents("""{"events":[{"type":"""))
    }

    @Test
    fun extractEvents_单条坏不影响其他条() {
        val r = extractEvents(
            """{"events":[{"type":"meal","kcal":"600"},"垃圾",{"type":"body","weight_kg":"58.2"}]}"""
        )
        assertEquals(3, r.size)
        assertEquals("other", r[1].type)          // 坏元素被兜底
        assertEquals(600, r[0].kcal)              // 好元素不受影响
        assertEquals(58.2, r[2].weightKg, 0.0001) // 字符串数字被强转
    }

    // ------------------------------------------------------------------
    // 5. day_key 日界线（功能补充 1.4）
    // ------------------------------------------------------------------

    @Test
    fun dayKeyOf_日界线四条边界() {
        // 显式指定时区，避免 CI runner 是 UTC 时断言失败
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))

            assertEquals("2026-10-02", dayKeyOf(ts(2026, 10, 3, 1, 0)))   // 凌晨夜宵 → 前一天
            assertEquals("2026-10-02", dayKeyOf(ts(2026, 10, 3, 3, 59)))
            assertEquals("2026-10-03", dayKeyOf(ts(2026, 10, 3, 4, 0)))    // 恰好 4:00 → 当天
            assertEquals("2026-10-03", dayKeyOf(ts(2026, 10, 3, 6, 0)))    // 早餐 → 当天
            assertEquals("2026-10-03", dayKeyOf(ts(2026, 10, 3, 12, 0)))
            assertEquals("2026-10-03", dayKeyOf(ts(2026, 10, 3, 23, 0)))
            // 自定义日界线 8:00
            assertEquals("2026-10-02", dayKeyOf(ts(2026, 10, 3, 6, 0), dayStartHour = 8))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    // ------------------------------------------------------------------
    // 6. Prompt 自检（禁令必须真实存在于字符串里）
    // ------------------------------------------------------------------

    @Test
    fun prompt_自检项() {
        assertTrue("prompt 里出现了'空串'二字，会诱导模型字面理解",
            "空串" !in PROMPT_EXTRACT)
        assertTrue("缺少多事件输出格式说明", "\"events\"" in PROMPT_EXTRACT)
        assertTrue("缺少分量默认参考段", "一碗牛肉面" in PROMPT_EXTRACT)
        assertTrue("缺少多事件拆分规则", "拆成多条" in PROMPT_EXTRACT)
        assertTrue("prompt 版本号不能为空", PROMPT_VER.isNotEmpty())
    }

    // ------------------------------------------------------------------

    private fun ts(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long {
        val cal = Calendar.getInstance()
        cal.set(year, month - 1, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
