package com.healix.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.healix.app.HealixApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.time.LocalDate

/**
 * 导出备份（功能补充 2.4）。
 *
 * 策略：明文 JSON + 版本头，走 SAF `ACTION_CREATE_DOCUMENT` 让用户选路径，
 * **不申请任何存储权限**。
 *
 * 导出内容：events + presets + daily_plans + daily_reviews + 非敏感 settings。
 * ⚠️ **不含 API Key** —— key 存在 EncryptedSharedPreferences，不在业务表里，天然导不出。
 *
 * 加密与 gzip 是可选增强，MVP 不做（明文比没有强 10 倍）。
 */
internal object ExportWriter {

    private const val FORMAT_VERSION = 1

    /** 组装导出 JSON。不带换行缩进（体积优先），带版本头（便于日后迁移）。 */
    fun buildJson(context: Context): String = runBlockingSafe {
        val db = HealixApp.from(context).database
        val root = JSONObject()

        root.put("format", "healix-backup")
        root.put("version", FORMAT_VERSION)
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
                },
            )
        }
        root.put("events", events)

        // presets
        val presets = JSONArray()
        for (p in db.presetDao().listAll()) {
            presets.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("foods_json", p.foodsJson)
                    put("kcal", p.kcal)
                    put("use_count", p.useCount)
                    put("last_used_at", p.lastUsedAt)
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
     * 注：`ActivityResultContracts.CreateDocument` 需要一个已注册的 launcher，
     * 而 launcher 必须在 Activity 创建阶段注册 —— 因此这里由 SettingsActivity
     * 持有 launcher 并调用 [writeTo]。本方法只负责发起。
     */
    fun launchCreateDocument(activity: Activity, json: String) {
        pendingPayload = json
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, fileName())
        }
        activity.startActivityForResult(intent, REQUEST_CODE)
    }

    /** 兼容 startActivityForResult 的回调入口（避免引入 registerForActivityResult 的时序约束）。 */
    fun onActivityResult(context: Context, requestCode: Int, resultCode: Int, data: Uri?): Boolean {
        if (requestCode != REQUEST_CODE) return false
        if (resultCode != Activity.RESULT_OK || data == null) {
            pendingPayload = null
            return true
        }
        val payload = pendingPayload
        pendingPayload = null
        if (payload == null) return true

        return try {
            context.contentResolver.openOutputStream(data)?.use { out: OutputStream ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun fileName(): String = "healix-backup-${LocalDate.now()}.json"

    private const val REQUEST_CODE = 4011

    @Volatile
    private var pendingPayload: String? = null

    /** 在 IO 线程同步等待的语法糖。导出是低频操作，阻塞可接受。 */
    private fun <T> runBlockingSafe(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }

    /** 供 ActivityResultLauncher 版本使用的契约（保留扩展位）。 */
    val CONTRACT = ActivityResultContracts.CreateDocument("application/json")
}
