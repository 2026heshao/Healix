package com.healix.app.ui

import android.app.Activity
import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import com.healix.app.HealixApp
import com.healix.app.db.DB_VERSION
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 导出备份（功能补充 2.4；v8 T07 升级为「可还原的迁移包」）。
 *
 * 策略：明文 JSON + 版本头，走 SAF `ACTION_CREATE_DOCUMENT` 让用户选路径，
 * **不申请任何存储权限**。配套读回见 [ImportReader]。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * v1 → v2（2026-10-05，需求 8 数据继承）
 * ══════════════════════════════════════════════════════════════════════════
 * v1 只导出 events / presets / daily_plans / daily_reviews / settings，
 * 而 `ImportReader` 的职责是"把备份还原成一台能用的新机" —— 目标（goals）、
 * 周期性提醒（reminders）、周训练计划（training_plans）都还原不了，
 * 等于备份里少了"用户配置"这一半。所以 v2 补齐这三张表，并在头部写入
 * `schema_version`，供导入侧判断"这个备份是不是比当前 App 还新"。
 *
 * 仍然**刻意不导出**的：
 * - `llm_calls`：调用日志/埋点，是设备本地运行痕迹，不是用户数据；
 * - `chat_messages`：对话是当天上下文，跨设备搬运没有语义（且体积大）；
 * - `body_signals`：由规则从 events 重新推导得出，`acknowledged` 是瞬时 UI 状态；
 * - `knowledge_docs` / `knowledge_chunks`：`uri` 指向本机 SAF 文档，新机上必然失效。
 *   知识库的迁移路径是"在新机重新上传 PDF"，而不是搬一串打不开的路径。
 * - **API Key**：key 存在 EncryptedSharedPreferences，不在业务表里，天然导不出。
 *
 * 向后兼容：v1 的老备份仍可被 [ImportReader] 导入（缺的表就是不导入）。
 */
internal object ExportWriter {

    /** 导出文件的格式版本。v2 起新增 goals / reminders / training_plans + schema_version。 */
    const val FORMAT_VERSION = 2

    /** 组装导出 JSON。不带换行缩进（体积优先），带版本头（便于日后迁移）。 */
    fun buildJson(context: Context): String = runBlockingSafe {
        val db = HealixApp.from(context).database
        val root = JSONObject()

        root.put("format", "healix-backup")
        root.put("version", FORMAT_VERSION)
        // 产生这份备份时的 DB schema 版本（DB_VERSION，唯一事实来源）。
        // 导入侧据此拒绝"来自更新版本"的文件，而不是静默丢字段。
        root.put("schema_version", DB_VERSION)
        root.put("exported_at", System.currentTimeMillis())
        root.put("exported_date", LocalDate.now().toString())

        // events
        val events = JSONArray()
        val allEvents = db.eventDao().listAll()
        for (e in allEvents) {
            events.put(
                JSONObject().apply {
                    put("client_event_id", e.clientEventId)
                    put("ts", e.ts)
                    put("day_key", e.dayKey)
                    put("raw_text", e.rawText)
                    put("type", e.type)
                    put("time_hint", e.timeHint)
                    put("foods", e.foods)
                    put("exercise", e.exercise)
                    put("amount", e.amount)
                    put("kcal", e.kcal)
                    put("symptom", e.symptom)
                    put("weight_kg", e.weightKg)
                    put("sleep_h", e.sleepH)
                    put("source", e.source)
                    put("parse_status", e.parseStatus)
                    put("origin", e.origin)
                    put("created_at", e.createdAt)
                    put("updated_at", e.updatedAt)
                    // 软删时间戳：必须随备份走，否则"全新设备首次导入"时已删记录
                    // 会以未删除状态复活（同机重导不受影响 —— UNIQUE 键直接 IGNORE）。
                    // 可空 → JSON null；导入侧 optLong 默认 0 再 takeIf { it > 0 } 还原。
                    put("deleted_at", e.deletedAt ?: JSONObject.NULL)
                },
            )
        }
        root.put("events", events)

        // presets（不导 id：主键是设备本地的自增值，搬过去只会和已有行撞号；
        //          导入侧按 name 去重 —— 名称才是用户眼里的身份）
        val presets = JSONArray()
        for (p in db.presetDao().listAll()) {
            presets.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("foods_json", p.foodsJson)
                    put("kcal", p.kcal)
                    put("use_count", p.useCount)
                    put("last_used_at", p.lastUsedAt)
                    put("created_at", p.createdAt)
                },
            )
        }
        root.put("presets", presets)

        // daily_plans（G4：AI 今日计划；plan_json / content 可空 → 用 JSONObject.NULL 兜）
        val plans = JSONArray()
        for (p in db.planDao().listAllPlans()) {
            plans.put(
                JSONObject().apply {
                    put("date", p.date)
                    put("target_kcal", p.targetKcal)
                    put("plan_json", p.planJson ?: JSONObject.NULL)
                    put("content", p.content ?: JSONObject.NULL)
                    put("generated_at", p.generatedAt)
                    put("source", p.source)
                },
            )
        }
        root.put("daily_plans", plans)

        // daily_reviews（G4：每日复盘；content / model 可空 → 用 JSONObject.NULL 兜）
        val reviews = JSONArray()
        for (r in db.planDao().listAllReviews()) {
            reviews.put(
                JSONObject().apply {
                    put("date", r.date)
                    put("content", r.content ?: JSONObject.NULL)
                    put("model", r.model ?: JSONObject.NULL)
                    put("generated_at", r.generatedAt)
                },
            )
        }
        root.put("daily_reviews", reviews)

        // goals（v2 新增；不导 id —— 导入侧按 metric 去重，metric 才是自然键）
        val goals = JSONArray()
        for (g in db.goalDao().listAll()) {
            goals.put(
                JSONObject().apply {
                    put("type", g.type)
                    put("metric", g.metric)
                    put("target_value", g.targetValue)
                    put("start_value", g.startValue ?: JSONObject.NULL)
                    put("deadline", g.deadline ?: JSONObject.NULL)
                    put("is_primary", g.isPrimary)
                    // 含 archived 行：归档是"移出目标栏"不是删除，重新添加即恢复
                    put("status", g.status)
                    put("created_at", g.createdAt)
                    put("updated_at", g.updatedAt)
                },
            )
        }
        root.put("goals", goals)

        // reminders（v2 新增；按 name 去重。含 enabled = 0 的行，同 goals 的理由）
        val reminders = JSONArray()
        for (r in db.reminderDao().listAll()) {
            reminders.put(
                JSONObject().apply {
                    put("name", r.name)
                    put("interval_days", r.intervalDays)
                    put("last_done_at", r.lastDoneAt ?: JSONObject.NULL)
                    put("next_due_at", r.nextDueAt)
                    put("enabled", r.enabled)
                    put("created_at", r.createdAt)
                },
            )
        }
        root.put("reminders", reminders)

        // training_plans（v2 新增；主键 week_key，导入侧按它去重）
        val weekPlans = JSONArray()
        for (t in db.trainingPlanDao().listAll()) {
            weekPlans.put(
                JSONObject().apply {
                    put("week_key", t.weekKey)
                    put("plan_json", t.planJson ?: JSONObject.NULL)
                    put("content", t.content ?: JSONObject.NULL)
                    put("generated_at", t.generatedAt)
                    put("source", t.source)
                },
            )
        }
        root.put("training_plans", weekPlans)

        // settings（非敏感项；apiKey 不在其中）
        val settings = JSONObject()
        for (s in db.settingsDao().listAll()) {
            settings.put(s.key, s.value)
        }
        root.put("settings", settings)

        root.toString()
    }

    /**
     * 用 SAF 让用户选保存位置。
     *
     * 注：导出入口在「我的」页（v6 11.1 从设置页迁来），宿主是 [MainActivity]；
     * 回传由 `MainActivity.onActivityResult` 转发给 [DocumentWriter]。选择器 + 回传 +
     * 写文件这套管线在 [DocumentWriter] 里（v8 需求 9：「就医材料」要做的是同一件事，
     * 管线抄第二份迟早漏改一处），本方法只把"导出"这一路的 MIME / 文件名填进去。
     */
    fun launchCreateDocument(activity: Activity, json: String) {
        DocumentWriter.launch(
            activity = activity,
            requestCode = DocumentWriter.REQUEST_EXPORT,
            mime = DocumentWriter.MIME_JSON,
            fileName = fileName(),
            payload = json,
        )
    }

    fun fileName(): String = "healix-backup-${LocalDate.now()}.json"

    /** 在 IO 线程同步等待的语法糖。导出是低频操作，阻塞可接受。 */
    private fun <T> runBlockingSafe(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }

    /** 供 ActivityResultLauncher 版本使用的契约（保留扩展位）。 */
    val CONTRACT = ActivityResultContracts.CreateDocument(DocumentWriter.MIME_JSON)
}
