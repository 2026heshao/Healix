package com.healix.app.ui

import com.healix.app.db.AppDatabase
import com.healix.app.db.ReminderEntity
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

/**
 * 提醒写路径（2026-10-07 P2：AI 写侧扩展到「结构性数据」）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么是独立文件、而不是塞进 [PlanChangeWriter] / [SettingsWriter]
 * ══════════════════════════════════════════════════════════════════════════
 * 提醒是独立的表（`reminders`），且它的写语义**天生是三态增删改**（不是"改某个键"），
 * 与那两个文件各自的字段 allowlist 模型都不同构。独立成文件后权限开关可分
 * （`SettingsKeys.AI_TOOL_WRITE_REMINDER`）—— 用户可能愿意让 AI 记忌口，
 * 但不愿让 AI 自己排「每 3 个月洗牙」这类日程。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 与 [PlanChangeWriter] 同构：describe（拟稿期，不写库）/ apply（确认期，写库）
 * ══════════════════════════════════════════════════════════════════════════
 * 两阶段共用同一份校验（[perform] 的 `commit` 开关 = 单一事实来源），因此不会出现
 * "拟稿时能过、确认时落库失败" 的两套判据。
 *
 * 校验纪律（模型输出永不可信）：
 * - **不抛异常**：一切非法输入返回 [Result.Error]（调用方回一句错误文本，循环可继续）；
 * - **定位歧义**（命中 0 条 / 多条）→ [Result.Error]，**不写库**；
 * - **同名不重复**：`add` 撞已有名字 / `update` 改成撞名 → [Result.Error]（与
 *   `ImportReader` 导入去重的口径一致：提醒以 `name` 为自然键）；
 * - 单协程内读改写（无并发缝隙）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 到期时刻的口径（**刻意与设置页不同**，见下）
 * ══════════════════════════════════════════════════════════════════════════
 * `SettingsViewModel.saveReminder` 的公式是 `nextDueAt = (lastDoneAt ?: now) + intervalDays`
 * —— 它在编辑面板里总是成立，因为面板会把输入框里的 `lastDoneAt` 一并传回。
 * 但 AI 的 `update` 只带**变了的那几项**，若照搬该公式，「只改个名字」也会把到期日
 * 推到"今天 + 周期"。故此处只在**周期或上次日期真的变了**时重算，否则**保留原到期日**。
 */
internal object ReminderWriter {

    // ── op 取值域（`op` 载荷的 `op` 字段）───────────────────────────────
    // ⚠️ 常量名用 `ACTION_*` 而非 `OP_*`：`ProfileWriter` 已有一组 `OP_ADD` / `OP_REMOVE` /
    //    `OP_SET`，与本文件若同名同值就会触发 `check_kotlin.py` 的
    //    `check_duplicate_constants`（判据正是「同名且同值」），平白多一条收敛提示。
    //    两者语义域不同（画像字段 op vs 提醒 op），**不该收敛**，故按项目既有先例
    //    （见 `HealthAgent.MAX_PLAN_NOTE_LEN` 的 KDoc）另名规避。载荷键仍是 `op`，
    //    与 [PlanChangeWriter] / [ProfileWriter] 保持一致。
    /** 新增一条提醒。 */
    const val ACTION_ADD = "add"

    /** 修改一条已有提醒（按 `match_name` 定位，命中须唯一）。 */
    const val ACTION_UPDATE = "update"

    /** 删除一条提醒（按 `match_name` 定位，命中须唯一）。 */
    const val ACTION_DELETE = "delete"

    /** 提醒名字长度上限。 */
    private const val MAX_NAME_CHARS = 40

    /** 结果：成功附人类可读摘要（供确认弹窗正文 / 回执），失败附错误文本。 */
    internal sealed interface Result {
        data class Ok(val summary: String) : Result
        data class Error(val message: String) : Result
    }

    /**
     * **拟稿期**校验（不写库）：读库定位 + 校验，产出摘要或错误文本。
     * 由 `HealthAgent.proposeReminderChange` 在生成 draft **之前**调用。
     */
    suspend fun describe(db: AppDatabase, opJson: String): Result =
        perform(db, opJson, commit = false)

    /**
     * **确认期**执行（写库）：与 [describe] 同一套校验，校验通过后落地。
     * 由 `ChatViewModel.confirmReminderChange` 在用户点确认后调用。
     *
     * ⚠️ 重新读库定位（拟稿到确认之间用户可能在设置页手改过）—— 不沿用拟稿期快照。
     */
    suspend fun apply(db: AppDatabase, opJson: String): Result =
        perform(db, opJson, commit = true)

    // ------------------------------------------------------------------
    // 分派
    // ------------------------------------------------------------------

    private suspend fun perform(db: AppDatabase, opJson: String, commit: Boolean): Result {
        val op = runCatching { JSONObject(opJson) }.getOrNull()
            ?: return Result.Error("内部错误：改动指令无法解析。")
        return when (op.optString("op").trim()) {
            ACTION_ADD -> addOp(db, op, commit)
            ACTION_UPDATE -> updateOp(db, op, commit)
            ACTION_DELETE -> deleteOp(db, op, commit)
            else -> Result.Error("未知的提醒修改类型（只支持 $ACTION_ADD / $ACTION_UPDATE / $ACTION_DELETE）。")
        }
    }

    // ------------------------------------------------------------------
    // 三种 op
    // ------------------------------------------------------------------

    private suspend fun addOp(db: AppDatabase, op: JSONObject, commit: Boolean): Result {
        val name = op.optString("name").trim()
        val nameErr = validateName(name)
        if (nameErr != null) return Result.Error(nameErr)
        val interval = op.optInt("interval_days", 0)
        val intervalErr = validateInterval(interval)
        if (intervalErr != null) return Result.Error(intervalErr)

        val all = readAll(db)
        if (all.any { it.name.equals(name, ignoreCase = true) }) {
            return Result.Error("已经有一条叫「$name」的提醒了，改成修改它或换个名字。")
        }
        val (lastDoneAt, dayErr) = parseDay(op.optString("last_done"))
        if (dayErr != null) return Result.Error(dayErr)
        val base = lastDoneAt ?: System.currentTimeMillis()
        if (commit) {
            db.reminderDao().upsert(
                ReminderEntity(
                    name = name,
                    intervalDays = interval,
                    lastDoneAt = lastDoneAt,
                    nextDueAt = base + interval.toLong() * HealixDate.DAY_MS,
                    enabled = 1,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        return Result.Ok("新增提醒「$name」，每 $interval 天一次")
    }

    private suspend fun updateOp(db: AppDatabase, op: JSONObject, commit: Boolean): Result {
        val row = locate(db, op.optString("match_name").trim())
            ?: return Result.Error(locateError(db, op.optString("match_name").trim()))
        val oldName = row.name

        val hasName = op.has("name")
        val hasInterval = op.has("interval_days")
        val hasLastDone = op.has("last_done")
        if (!hasName && !hasInterval && !hasLastDone) {
            return Result.Error("update 至少要给出 name / interval_days / last_done 之一。")
        }

        var newName = row.name
        if (hasName) {
            newName = op.optString("name").trim()
            val err = validateName(newName)
            if (err != null) return Result.Error(err)
            // 撞名检查：排除自己
            val all = readAll(db)
            if (all.any { it.id != row.id && it.name.equals(newName, ignoreCase = true) }) {
                return Result.Error("已经有一条叫「$newName」的提醒了，换个名字。")
            }
        }

        var newInterval = row.intervalDays
        if (hasInterval) {
            newInterval = op.optInt("interval_days", 0)
            val err = validateInterval(newInterval)
            if (err != null) return Result.Error(err)
        }

        var newLastDone = row.lastDoneAt
        if (hasLastDone) {
            val (parsed, dayErr) = parseDay(op.optString("last_done"))
            if (dayErr != null) return Result.Error(dayErr)
            newLastDone = parsed
        }

        // 只有「周期 / 上次日期真的变了」才重算到期日（见类 KDoc 的口径说明）
        val nextDueAt = if (hasInterval || hasLastDone) {
            (newLastDone ?: System.currentTimeMillis()) + newInterval.toLong() * HealixDate.DAY_MS
        } else {
            row.nextDueAt
        }

        if (commit) {
            db.reminderDao().upsert(
                row.copy(name = newName, intervalDays = newInterval, lastDoneAt = newLastDone, nextDueAt = nextDueAt),
            )
        }
        val renamed = if (newName != oldName) "，改名为「$newName」" else ""
        val cycled = if (hasInterval) "，周期改为每 $newInterval 天" else ""
        val done = if (hasLastDone) "，上次日期更新" else ""
        return Result.Ok("更新提醒「$oldName」$renamed$cycled$done")
    }

    private suspend fun deleteOp(db: AppDatabase, op: JSONObject, commit: Boolean): Result {
        val row = locate(db, op.optString("match_name").trim())
            ?: return Result.Error(locateError(db, op.optString("match_name").trim()))
        if (commit) db.reminderDao().delete(row.id)
        return Result.Ok("删除提醒「${row.name}」")
    }

    // ------------------------------------------------------------------
    // 定位 / 校验（纯函数，describe 与 apply 共用 = 单一事实来源）
    // ------------------------------------------------------------------

    /**
     * 按名字定位**唯一**一条提醒。
     *
     * 判据 = **名字子串、不区分大小写**（用户/模型眼里的提醒身份就是名字；
     * `query_reminders` 返回的也是它）——与 `PlanChangeWriter.locate` 的 `title` 子串同构。
     * `name` 是自然键（`ImportReader` 导入去重也用 name）。
     *
     * @return 恰一条命中返回该行；0 条或多条返回 null（调用方用 [locateError] 取原因）。
     */
    private suspend fun locate(db: AppDatabase, matchName: String): ReminderEntity? {
        if (matchName.isEmpty()) return null
        return hits(db, matchName).singleOrNull()
    }

    private suspend fun hits(db: AppDatabase, matchName: String): List<ReminderEntity> =
        readAll(db).filter { it.name.contains(matchName, ignoreCase = true) }

    private suspend fun locateError(db: AppDatabase, matchName: String): String {
        if (matchName.isEmpty()) return "需要给出 match_name 来指定要操作的提醒。"
        val n = hits(db, matchName).size
        return when (n) {
            0 -> "没找到名字含「$matchName」的提醒。"
            else -> "名字含「$matchName」的提醒有 $n 条，请把 match_name 写得更具体。"
        }
    }

    /**
     * 读全部提醒（**含 `enabled = 0`**）。
     *
     * 刻意用 `listAll()` 而非 `listEnabled()`：停用只是"暂时不想被提醒"，不是删除
     * —— 用户完全可能说「把那个洗牙的提醒重新开起来 / 改一下」，用 `listEnabled()`
     * 会让这类名字永远定位不到（"没找到"），而它明明就在设置页里。
     */
    private suspend fun readAll(db: AppDatabase): List<ReminderEntity> =
        runCatching { db.reminderDao().listAll() }.getOrDefault(emptyList())

    private fun validateName(name: String): String? = when {
        name.isEmpty() -> "name 不能为空。"
        name.length > MAX_NAME_CHARS -> "提醒名字最长 $MAX_NAME_CHARS 字。"
        else -> null
    }

    private fun validateInterval(days: Int): String? = when {
        days <= 0 -> "interval_days 必须是不小于 1 的整数（天）。"
        days > ReminderEntity.MAX_INTERVAL_DAYS ->
            "interval_days 最多 ${ReminderEntity.MAX_INTERVAL_DAYS} 天。"
        else -> null
    }

    /**
     * 解析 `yyyy-MM-dd` → 当日零点时间戳（系统时区）。
     *
     * 空串 = 未填 → `(null, null)`（与 `SettingsViewModel.saveReminder` 的
     * `lastDoneAt: Long?` 同语义）。格式非法 → `(null, 错误文本)`，由调用方原样回给模型。
     */
    private fun parseDay(raw: String): Pair<Long?, String?> {
        val s = raw.trim()
        if (s.isEmpty()) return null to null
        return try {
            LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() to null
        } catch (_: Exception) {
            null to "last_done 必须是 yyyy-MM-dd 格式（或留空）。"
        }
    }
}
