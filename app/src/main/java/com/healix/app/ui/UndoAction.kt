package com.healix.app.ui

import com.healix.app.db.AppDatabase
import com.healix.app.db.SettingEntity

/**
 * 一次写入的**逆操作**载荷（2026-10-07 P2 第三批：可回退机制）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么需要它：原来 [ChatViewModel.undo] 只能表达「撤销一次记录软删」
 * ══════════════════════════════════════════════════════════════════════════
 * 撤销条（[UndoBar]）本身早已是通用的，但它的数据流被写死成
 * `SharedFlow<RecordDeleteProposal>` —— 于是"能被撤销"这件事只有记录删除享有。
 * 而 P1/P2 新开的写路径里有两条是**覆盖型**：`user_background`（整段自述全文替换）
 * 与整周训练重排（整周 7 天整体替换）。它们此前被刻意挂起，理由就是"没有可回退机制"
 * （见 `docs/v0.3-增量设计-W2-2026-10-06.md` §11.2 的挂起项）。
 *
 * 本文件把撤销载荷泛化成 [UndoAction]，让"覆盖型写入"与"记录软删"共用同一条
 * 5 秒撤销通路 —— 撤销条不再为某一类写入专有。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 判据：什么写入该产撤销快照
 * ══════════════════════════════════════════════════════════════════════════
 * **覆盖型 / 破坏性**写入才产快照（旧值整体被替换，用户没有"在设置页逐项改回"的
 * 低成本路径，且改错了看不出来）。典型：
 * - 整段自述 `user_background`（旧文本被一次性替换）；
 * - 整周训练重排（7 天安排被一次性替换）；
 * - 记录删除（旧行为，本来就是软删 + 撤销）。
 *
 * **增量型**写入不产（`add 一条忌口` / `身高改为 175`）：这类改动在设置页是可逐项
 * 复原的，且撤销条只有 5 秒窗口、一次只挂一条 —— 给它挂撤销反而会把真正需要撤销的
 * 那次（整段覆盖）挤掉。
 */
sealed interface UndoAction {

    /**
     * 撤销条左侧文案（含"5 秒内可撤销"的既有措辞），同时兼作无障碍朗读文本。
     *
     * 由**产生方**填好（writer 有 `Context`、ViewModel 有 `Application`），UI 侧不再
     * 按类型分支取字符串 —— 否则每加一种可撤销写入都要回来改一次 Fragment。
     */
    val label: String
}

/** 记录软删的逆操作：清 `deleted_at` 让那条记录回到原位（[ChatViewModel.revert]）。 */
data class RecordRestore(
    val clientEventId: String,
    override val label: String,
) : UndoAction

/**
 * 「写入前旧值快照」的逆操作 —— 把某一处数据**整体写回**到写入之前的样子。
 *
 * @property kind     逆操作的落点类型，取值见 [UndoWriter.KIND_SETTING] /
 *                    [UndoWriter.KIND_TRAINING_WEEK]
 * @property key      定位键：`setting` = settings 键名；`training_week` = 周键 `yyyy-Www`
 * @property oldValue 旧值。`setting` = 旧设置值（null / 空 = 该键此前不存在 → 撤销即删键）；
 *                    `training_week` = 旧 `plan_json`
 * @property oldContent 旧 `content` 列（仅 `training_week` 用；`setting` 恒 null）
 */
data class SnapshotRestore(
    val kind: String,
    val key: String,
    val oldValue: String?,
    val oldContent: String?,
    override val label: String,
) : UndoAction

/**
 * 把 [SnapshotRestore] 落到库 —— 撤销条点「撤销」时由 [ChatViewModel.revert] 调用。
 *
 * 纪律与各 writer 一致：**不抛异常**（撤销是"本来就没写成"的兜底动作，失败也不该崩），
 * 落点行已被删（如整周计划被清）时静默放弃 —— 撤不回比崩掉好。
 */
internal object UndoWriter {

    /** `settings` 表：把某个键写回旧值（旧值不存在 → 删键，与 `SettingsViewModel.put` 同口径）。 */
    const val KIND_SETTING = "setting"

    /** `training_plans` 表：把本周的 `plan_json` / `content` 两列一起写回。 */
    const val KIND_TRAINING_WEEK = "training_week"

    suspend fun revert(db: AppDatabase, action: SnapshotRestore) {
        when (action.kind) {
            KIND_SETTING -> {
                val dao = db.settingsDao()
                val old = action.oldValue
                if (old.isNullOrBlank()) dao.remove(action.key)
                else dao.put(SettingEntity(key = action.key, value = old))
            }

            KIND_TRAINING_WEEK -> {
                val dao = db.trainingPlanDao()
                // 行没了（用户清过库 / 跨周）→ 撤不回，静默放弃
                val row = dao.getWeek(action.key) ?: return
                dao.upsert(row.copy(planJson = action.oldValue, content = action.oldContent))
            }
        }
    }
}
