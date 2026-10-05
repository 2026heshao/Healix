package com.healix.app.rules

import com.healix.app.db.EventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复度注记的**纯计算**部分自测（v8 需求 9 功能 1）。
 *
 * 为什么只测 [RecoveryNote.factOf] 而不测 `of(...)`：后者要 `Context` 取文案，
 * 纯 JVM 单元测试拿不到；而**边界判断全在 factOf 里**（抓不抓得出肌群、哪条历史算数、
 * 谁恢复最低、什么时候给候选清单）。文案只是把事实贴上去，出错也只会是字面问题。
 *
 * ⚠️ `now` 全部用固定时间戳 + 固定偏移构造，**不读系统时钟** —— 否则测试会在
 * 跨零点/夏令时的边界上偶发失败（这类"偶尔红一次"的测试最后都会被人无视）。
 */
class RecoveryNoteTest {

    private companion object {
        /** 固定"现在"：2023-11-14T22:13:20Z。测试里只做相对时间运算，绝对值无关紧要。 */
        const val NOW = 1_700_000_000_000L

        const val HOUR = 3_600_000L

        /** 含 3 个可识别肌群关键词的训练日标题（胸 / 肩 / 三头→手臂）。 */
        const val PUSH_DAY = "推（胸/肩/三头）"

        fun exercise(ts: Long, exercise: String, rawText: String = ""): EventEntity =
            EventEntity(
                clientEventId = "c-$ts-$exercise",
                ts = ts,
                dayKey = "2023-11-14",
                rawText = rawText,
                type = "exercise",
                exercise = exercise,
            )
    }

    // ------------------------------------------------------------------
    // 1. 给不出可核对事实 → null（三条各自独立）
    // ------------------------------------------------------------------

    @Test
    fun factOf_没有历史时返回null() {
        assertNull(RecoveryNote.factOf(PUSH_DAY, "", emptyList(), NOW))
    }

    @Test
    fun factOf_抓不出肌群时返回null() {
        // 标题与动作里一个关键词都没有（休息日 / 没写动作）→ 不猜，返回 null
        assertNull(RecoveryNote.factOf("休息", "", listOf(exercise(NOW - 6 * HOUR, "深蹲")), NOW))
    }

    @Test
    fun factOf_涉及肌群从未记录时返回null() {
        // 历史上只有"腿"，而 PUSH_DAY 涉及胸/肩/手臂 —— 一条都没记录过。
        // 此时若渲染，就会把 recoveryOf 的兜底值 100 当成"恢复得很好"展示出来。
        assertNull(RecoveryNote.factOf(PUSH_DAY, "", listOf(exercise(NOW - 6 * HOUR, "深蹲")), NOW))
    }

    @Test
    fun factOf_软删除的记录不参与() {
        val deleted = exercise(NOW - 6 * HOUR, "杠铃卧推").copy(deletedAt = NOW - 1)
        assertNull(RecoveryNote.factOf(PUSH_DAY, "", listOf(deleted), NOW))
    }

    @Test
    fun factOf_非运动类型不参与() {
        val meal = exercise(NOW - 6 * HOUR, "杠铃卧推").copy(type = "meal")
        assertNull(RecoveryNote.factOf(PUSH_DAY, "", listOf(meal), NOW))
    }

    // ------------------------------------------------------------------
    // 2. 恢复度边界（24h → 50、48h → 100、47h → 97）
    // ------------------------------------------------------------------

    @Test
    fun factOf_六小时前练过_恢复12且给出候选() {
        val fact = RecoveryNote.factOf(
            PUSH_DAY, "", listOf(exercise(NOW - 6 * HOUR, "杠铃卧推")), NOW,
        )
        assertNotNull(fact)
        assertEquals("胸", fact!!.muscle)
        assertEquals(6L, fact.hoursAgo)
        assertEquals(12, fact.recovery)
        // 背 / 腿 / 核心 从未记录 → 恢复度 100 → 全部是候选；
        // 当天计划里的 胸 / 肩 / 手臂 不出现在候选里（不推荐练今天已经排了的肌群）
        assertEquals(listOf("背", "腿", "核心"), fact.ready)
    }

    @Test
    fun factOf_二十四小时前恰好是50_不给候选() {
        // (24 / 48) * 100 = 50.0 → toInt → 50；50 >= LOW_RECOVERY → 视为已恢复
        val fact = RecoveryNote.factOf(PUSH_DAY, "", listOf(exercise(NOW - 24 * HOUR, "卧推")), NOW)
        assertNotNull(fact)
        assertEquals(50, fact!!.recovery)
        assertTrue("已恢复时不应给候选清单", fact.ready.isEmpty())
    }

    @Test
    fun factOf_四十七小时前是97() {
        val fact = RecoveryNote.factOf(PUSH_DAY, "", listOf(exercise(NOW - 47 * HOUR, "卧推")), NOW)
        assertNotNull(fact)
        assertEquals(47L, fact!!.hoursAgo)
        assertEquals(97, fact.recovery) // 97.91 → 向零截断
    }

    @Test
    fun factOf_四十八小时前恢复满() {
        val fact = RecoveryNote.factOf(PUSH_DAY, "", listOf(exercise(NOW - 48 * HOUR, "卧推")), NOW)
        assertNotNull(fact)
        assertEquals(48L, fact!!.hoursAgo)
        assertEquals(100, fact.recovery)
        // 更久以前（72 小时）同样是「已恢复」，不会超过 100
        val older = RecoveryNote.factOf(PUSH_DAY, "", listOf(exercise(NOW - 72 * HOUR, "卧推")), NOW)
        assertNotNull(older)
        assertEquals(100, older!!.recovery)
        assertEquals(72L, older.hoursAgo)
    }

    // ------------------------------------------------------------------
    // 3. 取「恢复度最低」的那个肌群（不是随便一个、也不是平均值）
    // ------------------------------------------------------------------

    @Test
    fun factOf_取恢复度最低的肌群() {
        // 胸 1 小时前练过（2%），肩 40 小时前练过（83%）→ 必须报「胸」
        val history = listOf(
            exercise(NOW - 1 * HOUR, "杠铃卧推"),
            exercise(NOW - 40 * HOUR, "哑铃肩推"),
        )
        val fact = RecoveryNote.factOf(PUSH_DAY, "", history, NOW)
        assertNotNull(fact)
        assertEquals("胸", fact!!.muscle)
        assertEquals(2, fact.recovery)
        assertEquals(1L, fact.hoursAgo)
    }

    @Test
    fun factOf_动作串也能抓出肌群() {
        // 标题中性、肌群只在动作里（detail 走同一个关键词表）
        val fact = RecoveryNote.factOf("训练日", "杠铃卧推 4×8", listOf(exercise(NOW - 6 * HOUR, "卧推")), NOW)
        assertNotNull(fact)
        assertEquals("胸", fact!!.muscle)
    }

    // ------------------------------------------------------------------
    // 4. 全部肌群都被今天覆盖 → 没有候选可给，但仍要有事实
    // ------------------------------------------------------------------

    @Test
    fun factOf_六个肌群全被今天覆盖时不给候选() {
        val allMuscles = "胸 背 腿 肩 手臂 核心"
        val history = listOf(exercise(NOW - 1 * HOUR, "卧推 引体 深蹲 推举 弯举 卷腹"))
        val fact = RecoveryNote.factOf(allMuscles, "", history, NOW)
        assertNotNull(fact)
        assertTrue("候选必须排除当天已排的肌群 → 此时应空", fact!!.ready.isEmpty())
        assertEquals(2, fact.recovery) // (1 / 48) * 100 = 2.08 → 2
    }

    // ------------------------------------------------------------------
    // 5. 时钟回拨 / 极端值：不出现负数（展示层会显示"上次 -3 小时前"）
    // ------------------------------------------------------------------

    @Test
    fun factOf_时间戳在未来时不出现负数() {
        val fact = RecoveryNote.factOf(PUSH_DAY, "", listOf(exercise(NOW + 5 * HOUR, "卧推")), NOW)
        assertNotNull(fact)
        assertEquals(0L, fact!!.hoursAgo)
        assertEquals(0, fact.recovery) // 负数被 coerceIn(0, 100) 夹到 0
    }

    // ------------------------------------------------------------------
    // 6. 底层 MuscleRecovery（本轮新增 lastTsOf，旧的 recoveryOf 改为复用）
    // ------------------------------------------------------------------

    @Test
    fun muscleRecovery_未练过返回null与100() {
        assertNull(MuscleRecovery.lastTsOf("胸", emptyList()))
        assertEquals(100, MuscleRecovery.recoveryOf("胸", emptyList(), NOW))
    }

    @Test
    fun muscleRecovery_lastTsOf取最近一次() {
        val history = listOf(exercise(NOW - 40 * HOUR, "卧推"), exercise(NOW - 2 * HOUR, "卧推"))
        assertEquals(NOW - 2 * HOUR, MuscleRecovery.lastTsOf("胸", history))
        // 与 recoveryOf 同源：2 小时 → 4%
        assertEquals(4, MuscleRecovery.recoveryOf("胸", history, NOW))
    }

    @Test
    fun muscleRecovery_抓不出关键词就不猜() {
        assertEquals(emptyList<String>(), MuscleRecovery.musclesOf("今天有点累", "拉伸"))
        assertEquals(6, MuscleRecovery.MUSCLES.size)
    }
}
