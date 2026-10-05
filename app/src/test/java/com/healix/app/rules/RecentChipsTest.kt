package com.healix.app.rules

import com.healix.app.db.EventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RecentChips] 的纯计算部分（`pick` / `fingerprintOf`）。
 *
 * `label` 需要 `Context.getString`，不进 JVM 单测 —— 它的正确性由"文案全部走
 * strings.xml + 实参个数核对"保证（与其他渲染层同口径）。
 */
class RecentChipsTest {

    /** 只填必填字段，其余走默认；时间戳用递增整数，便于断言"保持输入顺序"。 */
    private fun event(
        id: Long,
        type: String = "meal",
        raw: String = "raw$id",
        foods: String = "[]",
        exercise: String = "",
        amount: String = "",
        symptom: String = "",
        weightKg: Double = 0.0,
        sleepH: Double = 0.0,
    ) = EventEntity(
        clientEventId = "c$id",
        ts = id, // 越大越新
        dayKey = "2026-10-05",
        rawText = raw,
        type = type,
        foods = foods,
        exercise = exercise,
        amount = amount,
        symptom = symptom,
        weightKg = weightKg,
        sleepH = sleepH,
    )

    // ── pick ───────────────────────────────────────────────────────────

    @Test
    fun pick_同内容meal只保留最新一条() {
        // 输入已按 ts DESC：id 大在前
        val list = listOf(
            event(3, raw = "午饭", foods = """["米饭"]"""),
            event(2, raw = "中饭", foods = """["米饭"]"""),
            event(1, raw = "吃了米饭", foods = """["米饭"]"""),
        )
        val picked = RecentChips.pick(list)
        assertEquals(1, picked.size)
        assertEquals(3L, picked[0].ts)
    }

    @Test
    fun pick_不同foods的meal不会被合并() {
        val list = listOf(
            event(2, foods = """["米饭"]"""),
            event(1, foods = """["牛肉面"]"""),
        )
        assertEquals(2, RecentChips.pick(list).size)
    }

    @Test
    fun pick_最多只取count个() {
        val list = (10 downTo 1).map { event(it.toLong(), foods = """["f$it"]""") }
        assertEquals(RecentChips.MAX_CHIPS, RecentChips.pick(list).size)
        assertEquals(3, RecentChips.pick(list, count = 3).size)
        assertEquals(2, RecentChips.pick(list, count = 2).size)
    }

    @Test
    fun pick_去重后不足count时能补到3个不同内容() {
        // 最近 3 条内容一致 —— 只有 SCAN_LIMIT > MAX_CHIPS 才能凑满不同项
        val list = listOf(
            event(5, foods = """["米饭"]"""),
            event(4, foods = """["米饭"]"""),
            event(3, foods = """["米饭"]"""),
            event(2, foods = """["牛肉面"]"""),
            event(1, foods = """["鸡蛋"]"""),
        )
        val picked = RecentChips.pick(list)
        assertEquals(listOf(5L, 2L, 1L), picked.map { it.ts })
    }

    @Test
    fun pick_保持输入顺序即最新在前() {
        val list = listOf(
            event(9, type = "body", weightKg = 65.0),
            event(8, foods = """["米饭"]"""),
            event(7, type = "sleep", sleepH = 7.5),
        )
        assertEquals(listOf(9L, 8L, 7L), RecentChips.pick(list).map { it.ts })
    }

    @Test
    fun pick_count非正返回空() {
        val list = listOf(event(1, foods = """["米饭"]"""))
        assertTrue(RecentChips.pick(list, count = 0).isEmpty())
        assertTrue(RecentChips.pick(list, count = -3).isEmpty())
    }

    @Test
    fun pick_空输入返回空() {
        assertTrue(RecentChips.pick(emptyList()).isEmpty())
    }

    // ── fingerprintOf ─────────────────────────────────────────────────

    @Test
    fun fingerprintOf_不同类型同rawText指纹不同() {
        val a = event(1, type = "meal", raw = "x", foods = """["x"]""")
        val b = event(2, type = "other", raw = "x")
        assertNotEquals(RecentChips.fingerprintOf(a), RecentChips.fingerprintOf(b))
    }

    @Test
    fun fingerprintOf_exercise动作与数量都参与指纹() {
        val a = event(1, type = "exercise", exercise = "跑步", amount = "30 分钟")
        val b = event(2, type = "exercise", exercise = "跑步", amount = "30 分钟")
        val c = event(3, type = "exercise", exercise = "跑步", amount = "40 分钟")
        assertEquals(RecentChips.fingerprintOf(a), RecentChips.fingerprintOf(b))
        assertNotEquals(RecentChips.fingerprintOf(a), RecentChips.fingerprintOf(c))
    }

    @Test
    fun fingerprintOf_body与sleep用数值判定重复() {
        assertEquals(
            RecentChips.fingerprintOf(event(1, type = "body", weightKg = 65.4)),
            RecentChips.fingerprintOf(event(2, type = "body", weightKg = 65.4)),
        )
        assertNotEquals(
            RecentChips.fingerprintOf(event(1, type = "sleep", sleepH = 7.0)),
            RecentChips.fingerprintOf(event(2, type = "sleep", sleepH = 8.0)),
        )
    }

    @Test
    fun fingerprintOf_illness用症状判定重复() {
        assertEquals(
            RecentChips.fingerprintOf(event(1, type = "illness", symptom = "头痛")),
            RecentChips.fingerprintOf(event(2, type = "illness", symptom = " 头痛 ")),
        )
    }
}
