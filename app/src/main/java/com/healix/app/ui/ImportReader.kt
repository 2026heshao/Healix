package com.healix.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.healix.app.HealixApp
import com.healix.app.db.DB_VERSION
import com.healix.app.db.DailyPlanEntity
import com.healix.app.db.DailyReviewEntity
import com.healix.app.db.EventEntity
import com.healix.app.db.GoalEntity
import com.healix.app.db.GoalTypes
import com.healix.app.db.PresetEntity
import com.healix.app.db.ReminderEntity
import com.healix.app.db.SettingEntity
import com.healix.app.db.SettingsKeys
import com.healix.app.db.TrainingPlanEntity
import com.healix.app.parse.dayKeyOf
import com.healix.app.parse.dayStartHourOf
import com.healix.app.repo.EventRepository
import com.healix.app.repo.ORIGIN_AI
import com.healix.app.repo.ORIGIN_USER
import com.healix.app.repo.SOURCE_APP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 导入备份（v8 T07 / PRD 需求 8「本地数据继承」）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 这个类存在的唯一理由
 * ══════════════════════════════════════════════════════════════════════════
 * 本应用 `allowBackup = false` + `dataExtractionRules` 全排除（健康数据不上云，
 * 这是隐私定位的核心），代价是**换机 / 重装默认零恢复**。此前只有 [ExportWriter]
 * 能导出、没有任何读回路径 —— 导出的 JSON 是"死数据"，等于没有迁移能力。
 * 这里补上另一半，让「导出 → 搬运 → 导入」成为事实上的迁移通道。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 三条不能动的原则
 * ══════════════════════════════════════════════════════════════════════════
 * 1. **只合并不覆盖。** 每张表先取本地已有键的全量集合，再逐行判重：
 *    events 用 `client_event_id`（UNIQUE 索引 + `OnConflictStrategy.IGNORE`，
 *    天然幂等）；presets / reminders 用 `name`；goals 用 `metric`；
 *    daily_plans / daily_reviews 用 `date`；training_plans 用 `week_key`；
 *    settings 用 `key`。**已存在的行一律跳过**，绝不覆盖本机数据 ——
 *    导入的定位是"把缺的补回来"，用户的设备才是权威副本。
 *
 * 2. **API Key 永不搬运。** 导出不含 key（key 在 EncryptedSharedPreferences，
 *    不在业务表里），导入也就无从写入。key 缺失时**明确提示**用户去设置页重填 ——
 *    这是刻意的取舍：明文 key 放进一个能被随手转发/丢进网盘的文件，
 *    比多一步手动输入危险得多。
 *
 * 3. **模型输出式的不信任同样适用于用户文件。** 文件可能被手改、被别的版本写出、
 *    被截断。所以：逐行校验、缺字段走兜底、坏行跳过（计入"跳过"而不是让整批失败）、
 *    整体套在一个事务里（中途失败不留半拉数据）。文件头的 `schema_version`
 *    比当前 App 新时**直接拒绝**，而不是把读不懂的字段静默丢掉。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 已知边界（明说，不藏）
 * ══════════════════════════════════════════════════════════════════════════
 * - **按字节上限拒绝**（[MAX_BYTES]）：整份 JSON 走 `org.json` 全量载入内存，
 *   没有流式解析。一份 32MB 的备份 ≈ 9 万条 event ≈ 8 年以上的高强度记录，
 *   超过就提示不支持；真到了那一天要换成流式读取，这是显式的技术债。
 * - **`day_key` 以备份里的值为准**：它按旧设备的日界线算出。用户若在旧机把
 *   日界线改成过 4:00、新机保持默认，历史那几天的分组边界不会重算。
 *   只有备份里缺 `day_key` 时才用本机日界线重算兜底。
 * - **导入 `parse_status = pending` 的历史记录会进入离线队列**，从而在联网后
 *   真的发起解析（消耗配额）。这是对外语义的正确行为（"没解析完的还要解析"），
 *   但意味着导入后第一次联网可能有若干次调用。
 */
internal object ImportReader {

    private const val TAG = "ImportReader"

    /** SAF 请求码。与 [ExportWriter] 的 4011 错开，避免回传路由串台。 */
    private const val REQUEST_CODE = 4012

    /** 备份文件头的固定标识（与 [ExportWriter] 同值）。 */
    private const val FORMAT_NAME = "healix-backup"

    /**
     * 单份备份的体积上限。见类 KDoc「已知边界」。
     *
     * 公开给 UI 读：失败提示要写出具体数值（"超过 32 MB"），
     * 把数字抄进 `strings.xml` 迟早会和这个常量对不上。
     */
    const val MAX_BYTES = 32L * 1024 * 1024

    // 字段长度上限：任何来自文件的字符串都要截断后再落库，
    // 否则一个手改的 10MB `raw_text` 会永久占住 SQLite 页。
    private const val MAX_RAW_LEN = 2000
    private const val MAX_TEXT_LEN = 200
    private const val MAX_NAME_LEN = 60
    private const val MAX_KCAL = 20000
    private const val MAX_INTERVAL_DAYS = 3650
    private const val MAX_WEIGHT_KG = 500.0
    private const val MAX_SLEEP_H = 24.0

    /**
     * 容器键：与 [ExportWriter.buildJson] 写出的结构一一对应，两侧必须同步改。
     *
     * 命名用 `JSON_` 前缀而不是 `KEY_`：`pipeline/check_kotlin.py` 的
     * `check_settings_keys()` 会给「本文件确实在操作 settings 表 + 定义了
     * `KEY_XXX = "字面量"` 常量」的组合做键名分裂启发式检查。这些是**导出格式的
     * JSON 字段名**，与 settings 键毫无关系，换个前缀让这层歧义不存在。
     */
    private const val JSON_EVENTS = "events"
    private const val JSON_PRESETS = "presets"
    private const val JSON_PLANS = "daily_plans"
    private const val JSON_REVIEWS = "daily_reviews"
    private const val JSON_GOALS = "goals"
    private const val JSON_REMINDERS = "reminders"
    private const val JSON_TRAINING = "training_plans"
    private const val JSON_SETTINGS = "settings"

    /**
     * `events.weight_kg` 的导出字段名。
     *
     * ⚠️ 单独提成常量是因为**它与 `SettingsKeys.WEIGHT` 的字面量撞值**（都是
     * `weight_kg`）—— 语义毫不相干（一个是表列名、一个是设置键），但基于裸字符串
     * 的键名检查器只认字面量。用具名常量把"这是列名"写进名字里，避免日后
     * 检查器收紧时误伤。
     */
    private const val FIELD_WEIGHT_KG = "weight_kg"

    /**
     * 事件类型的兜底值。
     * 取值集由 `EventEntity.type` 的 KDoc 定义（meal|exercise|body|sleep|illness|other）。
     * 没提成公共常量是因为全项目没有第二处需要它 —— 有第二处时再收敛。
     */
    private const val EVENT_TYPE_OTHER = "other"

    /**
     * `parse_status` 的合法取值（离线队列语义，**不是错误码**）。
     *
     * 取值本身来自唯一事实来源 `EventRepository` 的 companion 常量，
     * 这里只做「非法的值一律回落 `PARSE_PENDING`」的判定 ——
     * 在别处再抄一份 "pending"/"done"/"failed" 是 2026-10-03 键名分裂事故的同一形态。
     */
    private val PARSE_STATUSES = setOf(
        EventRepository.PARSE_PENDING,
        EventRepository.PARSE_DONE,
        EventRepository.PARSE_FAILED,
    )

    /**
     * `parse_status` 兜底值：无法识别时回到队列，最多多解析一次，不会丢数据。
     *
     * ⚠️ 直引 `EventRepository.PARSE_PENDING` 而不是再写一个 "pending" 字面量。
     */
    private val PARSE_STATUS_FALLBACK = EventRepository.PARSE_PENDING

    /** `goals.status` 的三态（active | achieved | archived）。 */
    private val GOAL_STATUSES = setOf("active", "achieved", "archived")

    /**
     * 导入结果。
     *
     * @param inserted 新写入的行数
     * @param skipped  因**已存在**（或行本身无法修复）而跳过的行数
     * @param failure  非 null = 整批失败；此时 [inserted] / [skipped] 均为 0（事务已回滚）
     * @param needsApiKey 导入后本机仍没有 API Key → UI 需要提示去设置页重填
     */
    data class Report(
        val inserted: Int = 0,
        val skipped: Int = 0,
        val failure: Failure? = null,
        val needsApiKey: Boolean = false,
    )

    /** 整批失败的原因。UI 侧映射到 `strings.xml`（本类不拼文案）。 */
    enum class Failure {
        /** 不是 Healix 备份（选错了文件 / 内容是别的东西）。 */
        NOT_BACKUP,

        /** 备份来自更新的 App / schema —— 可能有本机读不懂的字段，拒绝而非静默丢。 */
        TOO_NEW,

        /** 超过 [MAX_BYTES]。 */
        TOO_LARGE,

        /** 打开或读取失败（URI 失效 / 权限被撤）。 */
        UNREADABLE,

        /** 写库过程中出错（已回滚）。 */
        INTERNAL,
    }

    /**
     * 导入是"选完文件之后"才发生的，与导出同属低频操作；
     * 用进程级 scope 而不是某个 Fragment 的 lifecycleScope ——
     * 用户在 SAF 选择器里停留期间、或切 Tab 时，导入都不该被取消。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _report = MutableStateFlow<Report?>(null)

    /**
     * 最近一次导入结果（一次性事件）。
     *
     * 用 `StateFlow` 而不是回调：宿主 Fragment 是**常驻 Tab**，在 SAF 期间可能
     * 不在 STARTED 态；StateFlow 会重放最新值，回来后照样能显示结果。
     * 显示完必须调 [consume]，否则下次进页面会重复弹。
     */
    val report: StateFlow<Report?> = _report.asStateFlow()

    /** 取走结果（置空）。UI 显示完就调。 */
    fun consume() {
        _report.value = null
    }

    /**
     * 发起选择备份文件。SAF `ACTION_OPEN_DOCUMENT` —— **不申请任何存储权限**，
     * 且 `type = application/json` 让选择器默认只列 JSON。
     *
     * 与 [ExportWriter] 同样用 `startActivityForResult`，由宿主
     * `MainActivity.onActivityResult` 转发回 [onActivityResult]（见那里的说明）。
     */
    fun launchOpenDocument(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        activity.startActivityForResult(intent, REQUEST_CODE)
    }

    /** 本类的请求码是否归它管（宿主用来决定转发给谁）。 */
    fun handles(requestCode: Int): Boolean = requestCode == REQUEST_CODE

    /**
     * 回传入口。**立即返回**：真正的读文件 + 写库在 [scope] 里跑，
     * 结果经 [report] 通知 UI（`onActivityResult` 在主线程上，不能做 IO）。
     */
    fun onActivityResult(context: Context, requestCode: Int, resultCode: Int, data: Uri?): Boolean {
        if (!handles(requestCode)) return false
        // 用户取消：什么都不做（不是错误，不该弹 toast）。
        if (resultCode != Activity.RESULT_OK || data == null) return true
        val app = context.applicationContext
        scope.launch {
            _report.value = importFrom(app, data)
        }
        return true
    }

    /**
     * 读入并落库。**幂等**：同一份备份导两次，第二次 inserted = 0、skipped = 全部。
     *
     * 不叫 `run`：`run` / `apply` 是 Kotlin 标准库的作用域函数名，
     * 用它们当成员函数名虽然合法（成员优先于扩展），但读代码的人容易看错，
     * 也给静态检查器添噪音。
     *
     * 公开给单测/调试页直接调用（不经过 SAF）。
     */
    suspend fun importFrom(app: Context, uri: Uri): Report {
        // 1. 体积闸门。先问 ContentResolver 要长度，避免把超大文件读进内存再判。
        val declared = runCatching {
            app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        if (declared != null && declared > MAX_BYTES) return Report(failure = Failure.TOO_LARGE)

        // 2. 读文本
        val text = runCatching {
            app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return Report(failure = Failure.UNREADABLE)
        if (text.isBlank()) return Report(failure = Failure.NOT_BACKUP)

        // 3. 校验文件头。解析不出 JSON / 不是本应用的备份 → NOT_BACKUP。
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return Report(failure = Failure.NOT_BACKUP)
        if (root.optString("format") != FORMAT_NAME) return Report(failure = Failure.NOT_BACKUP)

        // 格式版本（本类认识的字段集）或 schema 版本比本机新 → 拒绝。
        // 选择"拒绝"而不是"忽略读不懂的字段"：静默丢字段的迁移包最危险 ——
        // 用户以为全都搬过来了。
        if (root.optInt("version", 0) > ExportWriter.FORMAT_VERSION) {
            return Report(failure = Failure.TOO_NEW)
        }
        if (root.optInt("schema_version", 0) > DB_VERSION) {
            return Report(failure = Failure.TOO_NEW)
        }

        // 4. 落库（单事务：中途失败整体回滚，不留半拉数据）
        return try {
            applyToDb(app, root)
        } catch (t: Throwable) {
            Log.e(TAG, "导入失败（已回滚）：${t.javaClass.simpleName}")
            Report(failure = Failure.INTERNAL)
        }
    }

    // ------------------------------------------------------------------
    // 落库
    // ------------------------------------------------------------------

    private suspend fun applyToDb(app: Context, root: JSONObject): Report {
        val container = HealixApp.from(app)
        val db = container.database
        // 日界线只用于"备份里缺 day_key"的兜底重算（见类 KDoc「已知边界」）
        val dayStart = dayStartHourOf(db.settingsDao().get(SettingsKeys.DAY_START))

        // 各表的"已存在键"集合：各查一次全量再在内存里判重。
        // 逐行 EXISTS 查询是 N 次往返；备份规模下这个差别很实在。
        // 顺带这些 set 也承担"同一份文件内部重复行"的去重。
        val seenPresets = db.presetDao().listAll().mapTo(mutableSetOf()) { it.name }
        val seenPlanDates = db.planDao().listAllPlans().mapTo(mutableSetOf()) { it.date }
        val seenReviewDates = db.planDao().listAllReviews().mapTo(mutableSetOf()) { it.date }
        val seenMetrics = db.goalDao().listAll().mapTo(mutableSetOf()) { it.metric }
        val seenReminders = db.reminderDao().listAll().mapTo(mutableSetOf()) { it.name }
        val seenWeeks = db.trainingPlanDao().listAll().mapTo(mutableSetOf()) { it.weekKey }
        val seenSettingKeys = db.settingsDao().listAll().mapTo(mutableSetOf()) { it.key }

        var inserted = 0
        var skipped = 0

        db.withTransaction {
            // ── events：靠 UNIQUE(client_event_id) + IGNORE 实现幂等 ──
            val events = mutableListOf<EventEntity>()
            each(root.optJSONArray(JSON_EVENTS)) { o ->
                val ev = eventOf(o, dayStart)
                if (ev == null) skipped++ else events += ev
            }
            if (events.isNotEmpty()) {
                // -1 = 命中唯一索引被 IGNORE，即"这条已经在了"
                for (id in db.eventDao().insertIgnoreAll(events)) {
                    if (id == -1L) skipped++ else inserted++
                }
            }

            // ── presets：按 name 去重（表上没有唯一索引，判重只能在这里做）──
            each(root.optJSONArray(JSON_PRESETS)) { o ->
                val name = o.optString("name").take(MAX_NAME_LEN)
                if (name.isBlank() || !seenPresets.add(name)) {
                    skipped++
                } else {
                    db.presetDao().upsert(
                        PresetEntity(
                            name = name,
                            foodsJson = o.optString("foods_json").ifBlank { "[]" },
                            kcal = o.optInt("kcal", 0).coerceIn(0, MAX_KCAL),
                            useCount = o.optInt("use_count", 0).coerceAtLeast(0),
                            lastUsedAt = o.optLong("last_used_at", 0L),
                            createdAt = o.optLong("created_at", 0L),
                        ),
                    )
                    inserted++
                }
            }

            // ── daily_plans / daily_reviews：主键 = date ──
            each(root.optJSONArray(JSON_PLANS)) { o ->
                val date = o.optString("date").take(16)
                if (!isDayKey(date) || !seenPlanDates.add(date)) {
                    skipped++
                } else {
                    db.planDao().upsertPlan(
                        DailyPlanEntity(
                            date = date,
                            targetKcal = o.optInt("target_kcal", 0).coerceIn(0, MAX_KCAL),
                            planJson = o.textOrNull("plan_json"),
                            content = o.textOrNull("content"),
                            generatedAt = o.optLong("generated_at", 0L),
                            source = o.sourceOf(),
                        ),
                    )
                    inserted++
                }
            }

            each(root.optJSONArray(JSON_REVIEWS)) { o ->
                val date = o.optString("date").take(16)
                if (!isDayKey(date) || !seenReviewDates.add(date)) {
                    skipped++
                } else {
                    db.planDao().upsertReview(
                        DailyReviewEntity(
                            date = date,
                            content = o.textOrNull("content"),
                            model = o.textOrNull("model")?.take(48),
                            generatedAt = o.optLong("generated_at", 0L),
                        ),
                    )
                    inserted++
                }
            }

            // ── goals：按 metric 去重（自然键；id 是设备本地自增值，不搬）──
            each(root.optJSONArray(JSON_GOALS)) { o ->
                val metric = o.optString("metric").take(48)
                if (metric.isBlank() || !seenMetrics.add(metric)) {
                    skipped++
                } else {
                    db.goalDao().upsert(
                        GoalEntity(
                            type = o.optString("type").ifBlank { GoalTypes.HABIT }.take(24),
                            metric = metric,
                            targetValue = o.optDouble("target_value", 0.0).finiteOr(0.0),
                            startValue = o.optionalDouble("start_value"),
                            deadline = o.textOrNull("deadline")?.take(16),
                            isPrimary = if (o.optInt("is_primary", 0) != 0) 1 else 0,
                            status = o.optString("status").takeIf { it in GOAL_STATUSES } ?: "active",
                            createdAt = o.optLong("created_at", 0L),
                            updatedAt = o.optLong("updated_at", 0L),
                        ),
                    )
                    inserted++
                }
            }

            // ── reminders：按 name 去重。含 enabled = 0 的行（停用 ≠ 删除）──
            each(root.optJSONArray(JSON_REMINDERS)) { o ->
                val name = o.optString("name").take(MAX_NAME_LEN)
                if (name.isBlank() || !seenReminders.add(name)) {
                    skipped++
                } else {
                    db.reminderDao().upsert(
                        ReminderEntity(
                            name = name,
                            intervalDays = o.optInt("interval_days", 1)
                                .coerceIn(1, MAX_INTERVAL_DAYS),
                            lastDoneAt = o.optionalLong("last_done_at"),
                            nextDueAt = o.optLong("next_due_at", 0L),
                            enabled = if (o.optInt("enabled", 1) != 0) 1 else 0,
                            createdAt = o.optLong("created_at", 0L),
                        ),
                    )
                    inserted++
                }
            }

            // ── training_plans：主键 = week_key ──
            each(root.optJSONArray(JSON_TRAINING)) { o ->
                val week = o.optString("week_key").take(16)
                if (week.isBlank() || !seenWeeks.add(week)) {
                    skipped++
                } else {
                    db.trainingPlanDao().upsert(
                        TrainingPlanEntity(
                            weekKey = week,
                            planJson = o.textOrNull("plan_json"),
                            content = o.textOrNull("content"),
                            generatedAt = o.optLong("generated_at", 0L),
                            source = o.sourceOf(),
                        ),
                    )
                    inserted++
                }
            }

            // ── settings：逐键写入，**已存在的键一律保留本机值** ──
            // 本机值是用户在当前设备上的明确选择；备份里的同名字段可能来自更早的
            // 一次设定。合并语义下，先到本机的胜出。
            val settingRows = mutableListOf<SettingEntity>()
            val settingsObj = root.optJSONObject(JSON_SETTINGS)
            if (settingsObj != null) {
                for (key in settingsObj.keys()) {
                    if (key.isBlank() || !seenSettingKeys.add(key)) {
                        skipped++
                    } else {
                        settingRows += SettingEntity(key = key, value = settingsObj.optString(key))
                        inserted++
                    }
                }
            }
            for (row in settingRows) db.settingsDao().put(row)
        }

        return Report(
            inserted = inserted,
            skipped = skipped,
            needsApiKey = container.secretStore?.hasApiKey() != true,
        )
    }

    // ------------------------------------------------------------------
    // 行解析（模型输出式的不信任：每个字段都要校验 + 兜底 + 截断）
    // ------------------------------------------------------------------

    /**
     * 一行 → [EventEntity]；无法修复返回 null（计入"跳过"）。
     *
     * 什么算"无法修复"：没有 `client_event_id`（去重键，缺了就无法幂等）、
     * 或 `ts` 非正数（排不进任何一天）。这两条之外的字段全部走兜底。
     */
    private fun eventOf(o: JSONObject, dayStart: Int): EventEntity? {
        val clientId = o.optString("client_event_id").take(64)
        if (clientId.isBlank()) return null
        val ts = o.optLong("ts", 0L)
        if (ts <= 0L) return null
        return EventEntity(
            clientEventId = clientId,
            ts = ts,
            // 备份里缺 day_key（理论上不会，导出必带）时用本机日界线重算兜底
            dayKey = o.optString("day_key").takeIf { isDayKey(it) } ?: dayKeyOf(ts, dayStart),
            rawText = o.optString("raw_text").take(MAX_RAW_LEN),
            type = o.optString("type").ifBlank { EVENT_TYPE_OTHER }.take(16),
            timeHint = o.optString("time_hint").take(32),
            foods = o.optString("foods").ifBlank { "[]" },
            exercise = o.optString("exercise").take(MAX_TEXT_LEN),
            amount = o.optString("amount").take(MAX_TEXT_LEN),
            kcal = o.optInt("kcal", 0).coerceIn(0, MAX_KCAL),
            symptom = o.optString("symptom").take(MAX_TEXT_LEN),
            weightKg = o.optDouble(FIELD_WEIGHT_KG, 0.0).finiteOr(0.0).coerceIn(0.0, MAX_WEIGHT_KG),
            sleepH = o.optDouble("sleep_h", 0.0).finiteOr(0.0).coerceIn(0.0, MAX_SLEEP_H),
            source = o.optString("source").ifBlank { SOURCE_APP }.take(24),
            parseStatus = o.optString("parse_status").takeIf { it in PARSE_STATUSES }
                ?: PARSE_STATUS_FALLBACK,
            origin = o.optString("origin").takeIf { it == ORIGIN_USER || it == ORIGIN_AI }
                ?: ORIGIN_USER,
            // 时间戳缺失时退化成 ts：一个"约等于发生时刻"的值，
            // 好过 0（0 会让「最近记录」排序把它甩到最后）
            createdAt = o.optLong("created_at", ts),
            updatedAt = o.optLong("updated_at", ts),
        )
    }

    /** 计划 / 周计划的 source：只认 ai | fallback，其余回落 fallback。 */
    private fun JSONObject.sourceOf(): String =
        optString("source").takeIf {
            it == TrainingPlanner.SOURCE_AI || it == TrainingPlanner.SOURCE_FALLBACK
        } ?: TrainingPlanner.SOURCE_FALLBACK

    /**
     * 取可空字符串。
     *
     * ⚠️ **不能直接用 `optString`**：`JSONObject.NULL.toString()` 返回字面量 `"null"`，
     * 于是"没有内容"会被写成一个内容为 `null` 的字符串存进库 ——
     * 列表里就会看到一行文案写着 "null"。
     */
    private fun JSONObject.textOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    /** 取可空数值（缺失或 JSON null → null，而不是 0.0）。 */
    private fun JSONObject.optionalDouble(key: String): Double? =
        if (isNull(key)) null else optDouble(key).finiteOr(0.0).takeIf { it != 0.0 }

    /** 取可空时间戳（缺失或 JSON null → null）。 */
    private fun JSONObject.optionalLong(key: String): Long? =
        if (isNull(key)) null else optLong(key).takeIf { it > 0L }

    /** 浮点兜底：`NaN` / `Infinity` 落库会变成怪值，一律回落。 */
    private fun Double.finiteOr(fallback: Double): Double = if (isFinite()) this else fallback

    /** 遍历 JSON 数组（null 数组 = 老版本备份里没有这张表 → 什么都不做）。 */
    private inline fun each(arr: JSONArray?, block: (JSONObject) -> Unit) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            block(o)
        }
    }

    /** `yyyy-MM-dd`。主键是日期字符串的表用它挡住手改文件里的任意串。 */
    private fun isDayKey(s: String): Boolean =
        s.length == 10 && s[4] == '-' && s[7] == '-' &&
            s.withIndex().all { (i, c) -> i == 4 || i == 7 || c.isDigit() }
}
