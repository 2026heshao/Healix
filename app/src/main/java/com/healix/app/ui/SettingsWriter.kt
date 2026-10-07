package com.healix.app.ui

import android.content.Context
import com.healix.app.R
import com.healix.app.db.AppDatabase
import com.healix.app.db.SettingEntity
import com.healix.app.db.SettingsDao
import com.healix.app.db.SettingsKeys
import com.healix.app.parse.WEIGHT_MAX
import com.healix.app.parse.WEIGHT_MIN
import org.json.JSONObject

/**
 * 体格与运行设置写路径（2026-10-07 P1：AI 写侧扩展）。
 *
 * 与 [ProfileWriter] 同构（describe 拟稿期不写库 / apply 确认期写库，共用 [perform]），
 * 分工与权限开关的划分理由见 [ProfileWriter] 类 KDoc。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 刻意**不含** `ai_data_full`（AI 可见资料范围总开关）
 * ══════════════════════════════════════════════════════════════════════════
 * 该键决定"AI 能看到用户的多少资料"。若允许 AI 自己拟一条"把可见范围调回全部"的
 * 草稿，就等于把**自授权**的口子开在模型手里 —— 用户即便确认，那个确认也是在
 * AI 主动引导下做出的，与"用户自己想看更多"不能等价。故该键**只允许用户在
 * 设置页亲手改**（`SettingsFragment` 的 `rowAiDataSwitch`），本类不提供入口。
 *
 * 这一条与"有界自主"是同一条原则的两个面：写操作的边界由**用户**划，不由 AI 提议。
 *
 * ⚠️ 数值范围刻意与既有口径同源：体重复用 `SchemaValidator.WEIGHT_MIN/MAX`
 * （抽取链清洗模型胡说的同一对常量）；活动系数复用 `PersonalInfoFragment.ACTIVITY_VALUES`
 * （设置页单选取值，**不得两份并存**）。
 */
internal object SettingsWriter {

    // ── field 取值域 ──────────────────────────────────────────────────
    const val FIELD_HEIGHT = "height"
    const val FIELD_WEIGHT = "weight"
    const val FIELD_AGE = "age"
    const val FIELD_ACTIVITY = "activity"
    const val FIELD_DAY_START = "day_start"
    const val FIELD_HIDE_KCAL = "hide_kcal"
    const val FIELD_HIDE_WEIGHT = "hide_weight"

    /** 全部可写字段（供 `HealthAgent` 的 `defs` 与错误提示共用，避免两处列同一份名单）。 */
    val FIELDS = listOf(
        FIELD_HEIGHT, FIELD_WEIGHT, FIELD_AGE, FIELD_ACTIVITY,
        FIELD_DAY_START, FIELD_HIDE_KCAL, FIELD_HIDE_WEIGHT,
    )

    /** 身高区间（cm）。项目里没有既有常量 —— 这是本路径引入的输入闸门。 */
    private const val MIN_HEIGHT_CM = 100
    private const val MAX_HEIGHT_CM = 250

    /** 年龄区间（岁）。 */
    private const val MIN_AGE_Y = 1
    private const val MAX_AGE_Y = 120

    /** 日界线小时的合法区间（闭区间；`dayStartHourOf` 读端另有 clamp 兜底）。 */
    private const val MIN_DAY_START = 0
    private const val MAX_DAY_START = 23

    /** 结果：成功附人类可读摘要（供确认弹窗正文 / 回执），失败附错误文本。 */
    internal sealed interface Result {
        data class Ok(val summary: String) : Result
        data class Error(val message: String) : Result
    }

    /** **拟稿期**校验（不写库）。 */
    suspend fun describe(context: Context, db: AppDatabase, opJson: String): Result =
        perform(context, db, opJson, commit = false)

    /** **确认期**执行（写库）。 */
    suspend fun apply(context: Context, db: AppDatabase, opJson: String): Result =
        perform(context, db, opJson, commit = true)

    // ------------------------------------------------------------------
    // 分派
    // ------------------------------------------------------------------

    private suspend fun perform(
        context: Context,
        db: AppDatabase,
        opJson: String,
        commit: Boolean,
    ): Result {
        val op = try {
            JSONObject(opJson)
        } catch (_: Exception) {
            return Result.Error("内部错误：改动指令无法解析。")
        }
        val field = op.optString("field").trim()
        val value = op.optString("value").trim()
        val dao = db.settingsDao()
        return when (field) {
            FIELD_HEIGHT -> {
                val n = value.toIntOrNull()
                    ?: return Result.Error("身高要写成整数厘米数。")
                if (n !in MIN_HEIGHT_CM..MAX_HEIGHT_CM) {
                    return Result.Error("身高要在 $MIN_HEIGHT_CM–$MAX_HEIGHT_CM cm 之间。")
                }
                if (commit) put(dao, SettingsKeys.HEIGHT, n.toString())
                Result.Ok("把身高设为 $n cm")
            }

            FIELD_WEIGHT -> {
                val n = value.toDoubleOrNull()
                    ?: return Result.Error("体重要写成数字公斤数。")
                if (!n.isFinite() || n < WEIGHT_MIN || n > WEIGHT_MAX) {
                    return Result.Error("体重要在 ${fmt(WEIGHT_MIN)}–${fmt(WEIGHT_MAX)} kg 之间。")
                }
                if (commit) put(dao, SettingsKeys.WEIGHT, fmt(n))
                Result.Ok("把体重设为 ${fmt(n)} kg")
            }

            FIELD_AGE -> {
                val n = value.toIntOrNull() ?: return Result.Error("年龄要写成整数。")
                if (n !in MIN_AGE_Y..MAX_AGE_Y) {
                    return Result.Error("年龄要在 $MIN_AGE_Y–$MAX_AGE_Y 岁之间。")
                }
                if (commit) put(dao, SettingsKeys.AGE, n.toString())
                Result.Ok("把年龄设为 $n 岁")
            }

            FIELD_ACTIVITY -> {
                // 与设置页单选同源：不接受候选集外的值（否则设置页回显会空档）
                val idx = PersonalInfoFragment.ACTIVITY_VALUES.indexOf(value)
                if (idx < 0) {
                    return Result.Error(
                        "活动系数只能是：${PersonalInfoFragment.ACTIVITY_VALUES.joinToString(" / ")}。",
                    )
                }
                val label = context.resources
                    .getStringArray(R.array.activity_levels)
                    .getOrNull(idx)
                    .orEmpty()
                if (commit) put(dao, SettingsKeys.ACTIVITY, value)
                Result.Ok("把活动系数设为 $value${if (label.isEmpty()) "" else "（$label）"}")
            }

            FIELD_DAY_START -> {
                val n = value.toIntOrNull() ?: return Result.Error("日界线要写成 0–23 的整数小时。")
                if (n !in MIN_DAY_START..MAX_DAY_START) {
                    return Result.Error("日界线要在 $MIN_DAY_START–$MAX_DAY_START 点之间。")
                }
                if (commit) put(dao, SettingsKeys.DAY_START, n.toString())
                Result.Ok("把日界线设为 $n 点（凌晨 $n 点前的记录算前一天）")
            }

            FIELD_HIDE_KCAL -> boolField(value, commit, dao, SettingsKeys.HIDE_KCAL, label = "热量数字")

            FIELD_HIDE_WEIGHT -> boolField(value, commit, dao, SettingsKeys.HIDE_WEIGHT, label = "体重数字")

            else -> Result.Error("未知的设置字段「$field」，可选：${FIELDS.joinToString(" / ")}。")
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 布尔开关。只认 `true` / `false`（大小写不敏感）—— 刻意不认「开/关/on/yes」
     * 之类近义词：多一种写法就多一处只有模型会触发的分支，判定口径越窄越不会出错。
     *
     * 摘要写成「把热量数字设为隐藏」而不是「把隐藏热量数字」—— 后者少了谓词，
     * 中文读不通（这段文本会出现在确认弹窗正文与回执里，是要给人看的）。
     */
    private suspend fun boolField(
        value: String,
        commit: Boolean,
        dao: SettingsDao,
        key: String,
        label: String,
    ): Result {
        val on = when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> return Result.Error("该开关的值只能是 true 或 false。")
        }
        if (commit) put(dao, key, if (on) "true" else "false")
        return Result.Ok("把$label 设为${if (on) "隐藏" else "显示"}")
    }

    private suspend fun put(dao: SettingsDao, key: String, value: String) {
        dao.put(SettingEntity(key = key, value = value))
    }

    /** 去掉 `68.0` 这种多余的 `.0`（与 `PersonalInfoFragment.trim` 同款口径，此处不引它以保持独立）。 */
    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
