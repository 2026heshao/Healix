package com.healix.app.rules

import com.healix.app.db.EventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 就医材料**汇总口径**自测（v8 需求 9 功能 3）。
 *
 * 只测 [MedicalSummary.summarize]（纯计算）；`render(...)` 要 `Context` 取文案，
 * 纯 JVM 单测拿不到 —— 而"数字对不对"全在 summarize 里，文案贴错只会是字面问题。
 *
 * 这份材料的读者是**医生**：一个把 `weight_kg = 0`（兜底值）算进最低体重的错误，
 * 远比一句文案不通顺严重。故这里的断言全部针对"什么算一条有效记录"。
 */
class MedicalSummaryTest {

    private companion object {
        const val FROM = "2026-07-05"
        const val TO = "2026-10-05"

        fun event(
            dayKey: String,
            type: String,
            ts: Long = 0L,
            weightKg: Double = 0.0,
            sleepH: Double = 0.0,
            kcal: Int = 0,
            symptom: String = "",
            rawText: String = "",
            deletedAt: Long? = null,
        ): EventEntity = EventEntity(
            clientEventId = "c-$dayKey-$type-$ts-$kcal-$weightKg",
            ts = ts,
            dayKey = dayKey,
            rawText = rawText,
            type = type,
            symptom = symptom,
            weightKg = weightKg,
            sleepH = sleepH,
            kcal = kcal,
            deletedAt = deletedAt,
        )
    }

    // ------------------------------------------------------------------
    // 1. 空 / 区间 / 软删
    // ------------------------------------------------------------------

    @Test
    fun summarize_没有记录时全部为空() {
        val doc = MedicalSummary.summarize(emptyList(), FROM, TO)
        assertTrue(doc.isEmpty)
        assertNull(doc.weight)
        assertNull(doc.sleep)
        assertNull(doc.meal)
        assertNull(doc.exercise)
        assertTrue(doc.illness.isEmpty())
        assertEquals(FROM, doc.from)
        assertEquals(TO, doc.to)
    }

    @Test
    fun summarize_只统计时间窗内的记录() {
        val events = listOf(
            event("2026-07-04", "body", weightKg = 60.0), // 早一天 → 出窗
            event("2026-07-05", "body", weightKg = 70.0), // 含左端
            event("2026-10-05", "body", weightKg = 72.0), // 含右端
            event("2026-10-06", "body", weightKg = 99.0), // 晚一天 → 出窗
        )
        val weight = MedicalSummary.summarize(events, FROM, TO).weight
        assertNotNull(weight)
        assertEquals(2, weight!!.count)
        assertEquals(70.0, weight.min, 0.0)
        assertEquals(72.0, weight.max, 0.0)
        assertEquals(71.0, weight.avg, 0.0)
        assertEquals(72.0, weight.latest, 0.0)
        assertEquals("2026-10-05", weight.latestDay)
    }

    @Test
    fun summarize_软删除的记录不计入() {
        val events = listOf(event("2026-08-01", "meal", kcal = 500, deletedAt = 123L))
        val doc = MedicalSummary.summarize(events, FROM, TO)
        assertNull(doc.meal)
        assertTrue(doc.isEmpty)
    }

    // ------------------------------------------------------------------
    // 2. 体重 / 睡眠：0 是兜底值，不是测量
    // ------------------------------------------------------------------

    @Test
    fun summarize_体重为0的记录不算有效测量() {
        // 若把 0 算进去，给医生的材料上会出现「最低体重 0 kg」这种明显错误
        val events = listOf(
            event("2026-08-01", "body", weightKg = 0.0),
            event("2026-08-02", "body", weightKg = 68.0),
        )
        val weight = MedicalSummary.summarize(events, FROM, TO).weight
        assertNotNull(weight)
        assertEquals(1, weight!!.count)
        assertEquals(68.0, weight.min, 0.0)
        assertEquals("2026-08-02", weight.latestDay)
    }

    @Test
    fun summarize_体重全是0时整段不出() {
        val events = listOf(event("2026-08-01", "body", weightKg = 0.0))
        assertNull(MedicalSummary.summarize(events, FROM, TO).weight)
    }

    @Test
    fun summarize_睡眠同理且最新值取当天最后一条() {
        val events = listOf(
            event("2026-08-01", "sleep", ts = 1, sleepH = 6.5),
            event("2026-08-02", "sleep", ts = 2, sleepH = 8.0),
            event("2026-08-02", "sleep", ts = 3, sleepH = 7.5), // 同一天更晚 → latest 用它
            event("2026-08-03", "sleep", ts = 4, sleepH = 0.0), // 无效
        )
        val sleep = MedicalSummary.summarize(events, FROM, TO).sleep
        assertNotNull(sleep)
        assertEquals(3, sleep!!.count)
        assertEquals(6.5, sleep.min, 0.0)
        assertEquals(8.0, sleep.max, 0.0)
        assertEquals("2026-08-02", sleep.latestDay)
        assertEquals(7.5, sleep.latest, 0.0)
        assertEquals((6.5 + 8.0 + 7.5) / 3, sleep.avg, 1e-9)
    }

    // ------------------------------------------------------------------
    // 3. 症状时间线：升序 + 折叠空白 + 截断 + symptom 空则回落 raw_text
    // ------------------------------------------------------------------

    @Test
    fun summarize_症状按时间升序且整行为空时回落原文() {
        val events = listOf(
            event("2026-09-03", "illness", ts = 2, symptom = "咳嗽"),
            event("2026-09-01", "illness", ts = 1, symptom = "", rawText = "胃痛三天"),
        )
        val illness = MedicalSummary.summarize(events, FROM, TO).illness
        assertEquals(2, illness.size)
        assertEquals("2026-09-01", illness[0].day)
        assertEquals("胃痛三天", illness[0].text)
        assertEquals("2026-09-03", illness[1].day)
        assertEquals("咳嗽", illness[1].text)
    }

    @Test
    fun summarize_症状里的换行被折叠成一行() {
        // 自由文本里的换行会破坏"一行一条"的对齐，必须折叠
        val events = listOf(event("2026-09-01", "illness", symptom = "头痛\n 持续 2   小时 "))
        val illness = MedicalSummary.summarize(events, FROM, TO).illness
        assertEquals("头痛 持续 2 小时", illness[0].text)
    }

    @Test
    fun summarize_过长的症状被截断() {
        val long = "痛".repeat(200)
        val events = listOf(event("2026-09-01", "illness", symptom = long))
        val text = MedicalSummary.summarize(events, FROM, TO).illness[0].text
        assertTrue("应带省略号", text.endsWith("…"))
        assertEquals("保留前 60 个字符 + 省略号", 61, text.length)
    }

    // ------------------------------------------------------------------
    // 4. 饮食 / 运动：条数 + 有记录的天数 + 热量合计
    // ------------------------------------------------------------------

    @Test
    fun summarize_饮食按天去重后算日均分母() {
        val events = listOf(
            event("2026-08-01", "meal", kcal = 600),
            event("2026-08-01", "meal", kcal = 700), // 同一天第二条
            event("2026-08-02", "meal", kcal = 500),
        )
        val meal = MedicalSummary.summarize(events, FROM, TO).meal
        assertNotNull(meal)
        assertEquals(3, meal!!.count)
        assertEquals("有记录的天数是 2，不是 3 条记录", 2, meal.days)
        assertEquals(1800, meal.totalKcal)
    }

    @Test
    fun summarize_运动只统计条数与消耗() {
        val events = listOf(
            event("2026-08-01", "exercise", kcal = 300),
            event("2026-08-02", "exercise", kcal = 250),
        )
        val exercise = MedicalSummary.summarize(events, FROM, TO).exercise
        assertNotNull(exercise)
        assertEquals(2, exercise!!.count)
        assertEquals(550, exercise.totalKcal)
        assertNull(MedicalSummary.summarize(events, FROM, TO).weight)
    }

    // ------------------------------------------------------------------
    // 5. 类型之间的隔离：body 的记录不该被算进 meal
    // ------------------------------------------------------------------

    @Test
    fun summarize_各类型互不串味() {
        val events = listOf(
            event("2026-08-01", "body", weightKg = 70.0),
            event("2026-08-01", "sleep", sleepH = 7.0),
            event("2026-08-01", "meal", kcal = 400),
            event("2026-08-01", "exercise", kcal = 200),
            event("2026-08-01", "illness", symptom = "头痛"),
            event("2026-08-01", "other", kcal = 9999), // 未归类 → 哪一段都不进
        )
        val doc = MedicalSummary.summarize(events, FROM, TO)
        assertNotNull(doc.weight)
        assertNotNull(doc.sleep)
        assertNotNull(doc.meal)
        assertNotNull(doc.exercise)
        assertEquals(1, doc.illness.size)
        assertEquals(400, doc.meal!!.totalKcal)
        assertEquals(200, doc.exercise!!.totalKcal)
        assertEquals(false, doc.isEmpty)
    }
}
