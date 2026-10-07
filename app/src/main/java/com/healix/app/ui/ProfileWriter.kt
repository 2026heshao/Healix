package com.healix.app.ui

import android.content.Context
import com.healix.app.R
import com.healix.app.db.AppDatabase
import com.healix.app.db.SettingEntity
import com.healix.app.db.SettingsDao
import com.healix.app.db.SettingsKeys
import com.healix.app.repo.parseFoodsJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * 画像 / 资源清单写路径（2026-10-07 P1：AI 写侧扩展）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么与 [SettingsWriter] 分开
 * ══════════════════════════════════════════════════════════════════════════
 * 两者都落 `settings` 表，但**写入语义与风险面不同**：本类改的是"偏好与条件"
 * （忌口/器材/作息），[SettingsWriter] 改的是"身体数值与运行开关"（身高体重 /
 * 日界线 / 隐私）。分开 → 权限开关可分（用户可能愿意让 AI 记忌口，但不愿让
 * AI 碰日界线），对应 `SettingsKeys.AI_TOOL_WRITE_PROFILE` 与 `..WRITE_SETTINGS`。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 与 [PlanChangeWriter] 同构：describe（拟稿期，不写库）/ apply（确认期，写库）
 * ══════════════════════════════════════════════════════════════════════════
 * 两阶段共用同一份校验（[perform] 的 `commit` 开关 = 单一事实来源），因此
 * "拟稿时能过、确认时落库" 不会出现两套判据。
 *
 * 校验纪律（模型输出永不可信）：
 * - **不抛异常**：一切非法输入返回 [Result.Error]（调用方回一句错误文本，循环可继续）；
 * - **越界不写库**：未知字段 / 不支持的 op / 超长 / 非法时刻 / 场景不在候选集
 *   → [Result.Error]，**不产 draft**；
 * - **幂等可判**：`add` 一个已在列表里的项、`remove` 一个不在的项 → [Result.Error]，
 *   把事实回给模型（它可据此改口），而不是静默产一条什么都不改的草稿；
 * - 单协程内读改写（无并发缝隙）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * `user_background`（「我的情况」整段自述）—— 2026-10-07 起纳入，带撤销
 * ══════════════════════════════════════════════════════════════════════════
 * 它曾是本类唯一的刻意例外：**全文覆盖、不可逆**，而当时只有"记录软删"能撤销，
 * 于是留到有可回退机制再说（见 `docs/v0.3-增量设计-W2-2026-10-06.md` §11.2 挂起项）。
 * 现在 [UndoAction] / [UndoWriter] 把撤销通路泛化了，本字段随之上线：
 * 写入前快照旧值，成功后由 ChatViewModel 置 [ChatViewModel.undo] → UI 弹 5 秒撤销条。
 *
 * 覆盖型写入的**风险面**（与增量型字段的差别）在这里写死：
 * - 摘要里**报出旧值的字数**（"原有 320 字将被替换"）—— 用户点确认前就知道这一下
 *   会盖掉多少东西，而字数不是隐私（回执会落进对话历史、进下一轮上下文，
 *   所以旧值**内容**一个字都不许出现在摘要里）；
 * - 因此旧值只进 [SnapshotRestore] 快照（内存对象，不落库、不进 prompt）。
 *
 * ⚠️ 字段**短名**（如「忌口/过敏」）只用于确认弹窗 / 回执文案，与
 * [com.healix.app.repo.ProfileContext] 注入 prompt 时的完整段标题
 * （「忌口/过敏/不吃（饮食建议必须绕开）」）**刻意不同** —— 后者是给模型看的
 * 硬约束措辞，改它会动 prompt 口径，此处不动。
 */
internal object ProfileWriter {

    // ── field 取值域（`op` 载荷的 `field` 字段）─────────────────────────
    const val FIELD_ALLERGENS = "allergens"
    const val FIELD_PAIN = "pain"
    const val FIELD_SCENE = "scene"
    const val FIELD_FOODS = "foods"
    const val FIELD_MEDS = "meds"
    const val FIELD_SPORT = "sport"
    const val FIELD_SLEEP_BED = "sleep_bed"
    const val FIELD_SLEEP_WAKE = "sleep_wake"

    /** 「我的情况」整段自述（`user_background`）：**全文覆盖**，唯一带撤销的画像字段。 */
    const val FIELD_BACKGROUND = "background"

    /**
     * 全部可写字段（**供 `HealthAgent` 的 `defs` / 错误提示共用**，避免两处列同一份名单）。
     * 顺序 = 工具描述里的展示顺序。
     */
    val FIELDS = listOf(
        FIELD_ALLERGENS, FIELD_PAIN, FIELD_SCENE,
        FIELD_FOODS, FIELD_MEDS, FIELD_SPORT,
        FIELD_SLEEP_BED, FIELD_SLEEP_WAKE,
        FIELD_BACKGROUND,
    )

    // ── op 取值域 ─────────────────────────────────────────────────────
    /** 数组类字段：追加一项。 */
    const val OP_ADD = "add"

    /** 数组类字段：移除一项。 */
    const val OP_REMOVE = "remove"

    /** 标量 / 文本 / 时刻类字段：整体覆盖。 */
    const val OP_SET = "set"

    /** 数组单项长度上限。 */
    private const val MAX_ARRAY_ITEM_CHARS = 20

    /** 数组条数上限（防手滑灌爆 prompt）。 */
    private const val MAX_ARRAY_ITEMS = 20

    /** 自由文本类字段（手头食物 / 常备药物 / 运动条件）长度上限。 */
    private const val MAX_FREE_TEXT_CHARS = 500

    /** 摘要里回显新值的截断长度（只用于确认弹窗/回执，**不回显旧值内容**）。 */
    private const val SUMMARY_TAKE_CHARS = 20

    /** `HH:mm` / `H:mm`（UI 的输入口径允许单位数小时，见 `PersonalInfoFragment.TIME_RE`）。 */
    private val CLOCK_RE = Regex("""^(\d{1,2}):(\d{2})$""")

    /**
     * 结果：成功附人类可读摘要（供确认弹窗正文 / 回执），失败附错误文本。
     *
     * [Ok.undo] 非空 = 本次写入属**覆盖型**，调用方应在落库成功后置撤销条
     * （见 [UndoAction] 的判据）。增量型字段恒为 null。
     */
    internal sealed interface Result {
        data class Ok(val summary: String, val undo: UndoAction? = null) : Result
        data class Error(val message: String) : Result
    }

    /**
     * **拟稿期**校验（不写库）：产出摘要或错误文本。
     * 由 `HealthAgent.proposeProfileUpdate` 在生成 draft **之前**调用。
     */
    suspend fun describe(context: Context, db: AppDatabase, opJson: String): Result =
        perform(context, db, opJson, commit = false)

    /**
     * **确认期**执行（写库）：与 [describe] 同一套校验，校验通过后落地。
     * 由 `ChatViewModel.confirmProfileUpdate` 在用户点确认后调用。
     *
     * ⚠️ 重新读库（拟稿到确认之间用户可能在设置页手改过）—— 数组类的读改写在这里
     * 重新取当前值，不沿用拟稿期快照。
     */
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
        val action = op.optString("op").trim()
        val value = op.optString("value").trim()
        return when (field) {
            FIELD_ALLERGENS -> arrayField(
                db, action, value, commit,
                key = SettingsKeys.PROFILE_ALLERGENS, label = "忌口/过敏",
            )

            FIELD_PAIN -> arrayField(
                db, action, value, commit,
                key = SettingsKeys.PROFILE_PAIN, label = "疼痛/不适部位",
            )

            FIELD_SCENE -> sceneField(context, db, action, value, commit)

            FIELD_FOODS -> textField(
                db, action, value, commit,
                key = SettingsKeys.PROFILE_FOODS, label = "手头食物",
            )

            FIELD_MEDS -> textField(
                db, action, value, commit,
                key = SettingsKeys.PROFILE_MEDS, label = "常备药物",
            )

            FIELD_SPORT -> textField(
                db, action, value, commit,
                key = SettingsKeys.PROFILE_SPORT, label = "运动条件",
            )

            FIELD_SLEEP_BED -> clockField(
                db, action, value, commit,
                key = SettingsKeys.PROFILE_SLEEP_BED, label = "就寝时间",
            )

            FIELD_SLEEP_WAKE -> clockField(
                db, action, value, commit,
                key = SettingsKeys.PROFILE_SLEEP_WAKE, label = "起床时间",
            )

            FIELD_BACKGROUND -> backgroundField(context, db, action, value, commit)

            else -> Result.Error("未知的画像字段「$field」，可选：${FIELDS.joinToString(" / ")}。")
        }
    }

    // ------------------------------------------------------------------
    // 三类字段的处理
    // ------------------------------------------------------------------

    /** 数组类（忌口 / 疼痛）：`add` / `remove` 单条。读改写，不整份覆盖。 */
    private suspend fun arrayField(
        db: AppDatabase,
        action: String,
        value: String,
        commit: Boolean,
        key: String,
        label: String,
    ): Result {
        if (action != OP_ADD && action != OP_REMOVE) {
            return Result.Error("$label 只支持 add（加入）/ remove（移除）。")
        }
        if (value.isEmpty()) return Result.Error("value 不能为空。")
        if (value.length > MAX_ARRAY_ITEM_CHARS) {
            return Result.Error("单项最长 $MAX_ARRAY_ITEM_CHARS 字。")
        }
        val dao = db.settingsDao()
        val current = parseFoodsJson(dao.get(key).orEmpty())
        val hit = current.any { it.equals(value, ignoreCase = true) }
        val next = when (action) {
            OP_ADD -> {
                if (hit) return Result.Error("$label 里已经有「$value」了，无需重复添加。")
                current + value
            }

            else -> {
                // ⚠️ 刻意**不回吐**现有列表：`ai_data_full = false` 时画像不注入 prompt，
                // 若错误文本里带上全量列表，模型就能靠"故意 remove 一个不存在的项"读到
                // 本该被门控挡住的画像 —— 一行错误提示足以绕过总开关。只回"没有这一条"。
                if (!hit) return Result.Error("$label 里没有「$value」。")
                current.filterNot { it.equals(value, ignoreCase = true) }
            }
        }
        if (next.size > MAX_ARRAY_ITEMS) {
            return Result.Error("$label 最多 $MAX_ARRAY_ITEMS 条，先删几条再加。")
        }
        if (commit) putOrRemove(dao, key, JSONArray(next).toString())
        return Result.Ok(
            if (action == OP_ADD) "把「$value」加入$label" else "把「$value」从$label 里移除",
        )
    }

    /**
     * 就餐场景：取值必须是 `R.array.profile_scenes` 里的候选（**与设置页同源**，
     * 不硬编码第二份），或空串（= 未固定，对应 UI 的「未固定」档）。
     */
    private suspend fun sceneField(
        context: Context,
        db: AppDatabase,
        action: String,
        value: String,
        commit: Boolean,
    ): Result {
        if (action != OP_SET) return Result.Error("就餐场景只支持 set。")
        val options = context.resources.getStringArray(R.array.profile_scenes).toList()
        if (value.isNotEmpty() && value !in options) {
            return Result.Error("就餐场景只能是：${options.joinToString(" / ")}（或留空表示未固定）。")
        }
        if (commit) putOrRemove(db.settingsDao(), SettingsKeys.PROFILE_SCENE, value)
        return Result.Ok("把就餐场景设为「${value.ifEmpty { "未固定" }}」")
    }

    /** 自由文本类（手头食物 / 常备药物 / 运动条件）：整体覆盖。 */
    private suspend fun textField(
        db: AppDatabase,
        action: String,
        value: String,
        commit: Boolean,
        key: String,
        label: String,
    ): Result {
        if (action != OP_SET) return Result.Error("$label 只支持 set。")
        if (value.length > MAX_FREE_TEXT_CHARS) {
            return Result.Error("$label 最长 $MAX_FREE_TEXT_CHARS 字。")
        }
        if (commit) putOrRemove(db.settingsDao(), key, value)
        return Result.Ok("把$label 设为「${value.ifEmpty { "（空）" }}」")
    }

    /**
     * 「我的情况」整段自述（`user_background`）：**全文覆盖**，产撤销快照。
     *
     * - 上限复用设置页的 `InputFilter` 长度（[PersonalInfoFragment.BACKGROUND_MAX]）
     *   —— 这是本字段的**唯一来源**：两边各写一个数，AI 侧就能写进一段设置页显示不下
     *   的文本（用户点进设置页看到被截断的内容，却不知道是 AI 写的还是自己写的）。
     * - 空值 = 删键（与 [putOrRemove] / `SettingsViewModel.put` 同口径）：用户可以让
     *   AI "把我的情况清空"，此时撤销快照里带的是旧文本。
     * - 摘要里**只报新值前 [SUMMARY_TAKE_CHARS] 字与旧值字数**，不回吐旧值内容 ——
     *   摘要会经回执消息落进对话历史，旧自述不该借这条路绕过 `ai_data_full` 门控
     *   （与 [arrayField] 的 `remove` 不回吐列表是同一条纪律）。
     */
    private suspend fun backgroundField(
        context: Context,
        db: AppDatabase,
        action: String,
        value: String,
        commit: Boolean,
    ): Result {
        if (action != OP_SET) return Result.Error("「我的情况」只支持 set（整段覆盖）。")
        if (value.length > PersonalInfoFragment.BACKGROUND_MAX) {
            return Result.Error("「我的情况」最长 ${PersonalInfoFragment.BACKGROUND_MAX} 字。")
        }
        val dao = db.settingsDao()
        val old = dao.get(SettingsKeys.BACKGROUND)
        val oldLen = old.orEmpty().trim().length
        if (commit) putOrRemove(dao, SettingsKeys.BACKGROUND, value)
        val shown = if (value.length > SUMMARY_TAKE_CHARS) {
            value.take(SUMMARY_TAKE_CHARS) + "…"
        } else {
            value
        }
        val head = if (value.isEmpty()) "清空「我的情况」自述" else "把「我的情况」自述改为：$shown"
        val replaceNote = if (oldLen > 0) "（原有 $oldLen 字将被替换）" else "（原本没有自述）"
        return Result.Ok(
            summary = head + replaceNote,
            undo = if (commit) {
                SnapshotRestore(
                    kind = UndoWriter.KIND_SETTING,
                    key = SettingsKeys.BACKGROUND,
                    oldValue = old,
                    oldContent = null,
                    label = context.getString(R.string.undo_background_updated),
                )
            } else {
                null
            },
        )
    }

    /** 时刻类（就寝 / 起床）：`HH:mm`（小时允许 1..2 位，与设置页输入口径一致）。 */
    private suspend fun clockField(
        db: AppDatabase,
        action: String,
        value: String,
        commit: Boolean,
        key: String,
        label: String,
    ): Result {
        if (action != OP_SET) return Result.Error("$label 只支持 set。")
        val m = CLOCK_RE.matchEntire(value)
            ?: return Result.Error("$label 要写成 HH:mm（如 00:30 / 7:30）。")
        val hh = m.groupValues[1].toIntOrNull() ?: -1
        val mm = m.groupValues[2].toIntOrNull() ?: -1
        if (hh !in 0..23 || mm !in 0..59) {
            return Result.Error("$label 不是合法时刻（小时 0-23、分钟 0-59）。")
        }
        val normalized = "%02d:%02d".format(hh, mm)
        if (commit) putOrRemove(db.settingsDao(), key, normalized)
        return Result.Ok("把$label 设为 $normalized")
    }

    /**
     * 写键 / 删键。空值 = 删键（**与 `SettingsViewModel.put` 同口径**）——
     * 所有消费方都按"键不存在 = 未填写"判定，写空串会让"未填写"多一种表示。
     */
    private suspend fun putOrRemove(
        dao: SettingsDao,
        key: String,
        value: String,
    ) {
        if (value.isBlank()) dao.remove(key) else dao.put(SettingEntity(key = key, value = value))
    }
}
