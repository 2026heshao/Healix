package com.healix.app.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 目标（PRD §3.2）。 */
@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    /** weight | training | sleep | habit */
    @ColumnInfo(name = "type") val type: String,
    /** primary | weight_kg | sessions_per_week | train_minutes_per_week | sleep_h | water_ml */
    @ColumnInfo(name = "metric") val metric: String,
    @ColumnInfo(name = "target_value") val targetValue: Double,
    @ColumnInfo(name = "start_value") val startValue: Double? = null,
    @ColumnInfo(name = "deadline") val deadline: String? = null,
    /** 1 = 主目标（决定首页大数字/训练处方以谁为准），0 = 非主目标 */
    @ColumnInfo(name = "is_primary") val isPrimary: Int = 0,
    /** active | achieved | archived */
    @ColumnInfo(name = "status") val status: String = "active",
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
)

/** 周训练计划（PRD §4.2 A2）。一周一行，整周复用 —— 这是控制 AI 成本的关键。 */
@Entity(tableName = "training_plans")
data class TrainingPlanEntity(
    /** ISO 周键 yyyy-Www */
    @PrimaryKey @ColumnInfo(name = "week_key") val weekKey: String,
    @ColumnInfo(name = "plan_json") val planJson: String? = null,
    @ColumnInfo(name = "content") val content: String? = null,
    @ColumnInfo(name = "generated_at") val generatedAt: Long = 0,
    /** ai | fallback */
    @ColumnInfo(name = "source") val source: String = "fallback",
)

/** 身体信号（PRD §7.2）。UNIQUE(rule_id, day_key) 是这张表存在的核心理由。 */
@Entity(
    tableName = "body_signals",
    indices = [Index(value = ["rule_id", "day_key"], unique = true)],
)
data class BodySignalEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    /** H1..H8 / T1..T3 */
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "day_key") val dayKey: String,
    /** info | notice | alert */
    @ColumnInfo(name = "level") val level: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "detail") val detail: String? = null,
    @ColumnInfo(name = "acknowledged") val acknowledged: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
)

/** 周期性事项（PRD §5.4 B3）：体检 / 洗牙 / 配镜 / 疫苗。 */
@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "interval_days") val intervalDays: Int,
    @ColumnInfo(name = "last_done_at") val lastDoneAt: Long? = null,
    @ColumnInfo(name = "next_due_at") val nextDueAt: Long,
    @ColumnInfo(name = "enabled") val enabled: Int = 1,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
) {
    companion object {
        /**
         * `interval_days` 的**合法上界**（约 10 年）—— 唯一事实来源。
         *
         * 两个使用点：写入侧 `ReminderWriter`（AI 拟稿的周期校验）与导入侧 `ImportReader`
         * （备份里 `interval_days` 的 `coerceIn` 上界）。两处**必须同口径**：否则手改一份备份
         * 就能塞进一个写入侧会拒绝的值，同一列出现两种"合法"。
         *
         * 下界（1 天）无歧义，不单列常量。
         *
         * 加 companion 不改表结构 → 无需升 Room version。
         */
        const val MAX_INTERVAL_DAYS = 3650
    }
}

/** goals.metric 的取值常量（唯一事实来源，其它文件引用这里，不要重定义）。 */
object GoalMetrics {
    const val PRIMARY = "primary"
    /**
     * 每日目标摄入（千卡）。v8 问题 2a 新增：kcal 目标从 settings 键
     * `TARGET_KCAL` 收编进 `goals` 表 —— 与其它目标同表、同增删改路径。
     */
    const val KCAL_DAILY = "kcal_daily"
    const val WEIGHT_KG = "weight_kg"
    const val SESSIONS_PER_WEEK = "sessions_per_week"
    const val TRAIN_MINUTES_PER_WEEK = "train_minutes_per_week"
    const val SLEEP_H = "sleep_h"
    const val WATER_ML = "water_ml"
}

/**
 * `goals.type` 的取值常量（**唯一事实来源**，其它文件引用这里，不要重定义）。
 *
 * 与 [GoalMetrics] 的区别：`metric` 是"目标度量哪一项"（进 DB 的键），
 * `type` 是"这条目标属于哪一类"（用于分组展示 / 语义归类）。
 * ⚠️ 两处曾各写一份 `"goal_mode"` 字面量（`SettingsViewModel` 与 `MainViewModel`），
 * 已收敛到此 —— 与 2026-10-03 键名分裂事故同类，改一处漏一处是最贵的 bug。
 */
object GoalTypes {
    /** 主目标行（`metric = PRIMARY`）的 `type`：`target_value` 编码 0/1/2。 */
    const val GOAL_MODE = "goal_mode"
    const val WEIGHT = "weight"
    const val TRAINING = "training"
    const val SLEEP = "sleep"
    const val HABIT = "habit"
}

/**
 * 设置页「目标」栏的**展示分组**（唯一事实来源，其它文件引用这里，不要重定义）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么需要"分组"这一层
 * ══════════════════════════════════════════════════════════════════════════
 * `goals` 表里"每周训练"是**两行**（`sessions_per_week` + `train_minutes_per_week`），
 * 但界面上是一个「每周训练」条目（`3 次 / 150 分钟`）。左滑删除 / 后续恢复
 * 必须**成组**处理，否则会出现"删了次数、时长还在"的半个目标。
 *
 * 把分组定义收敛到此处（而非散在 SettingsFragment / SettingsViewModel），
 * 与 [GoalMetrics] / [GoalTypes] / [GoalDefaults] 同一纪律：
 * 添加一个新目标维度时只改这里一处。
 */
object GoalSlots {

    /**
     * 一个展示分组。
     *
     * @param key 稳定标识（跨进程仅用于 add-sheet 的 arguments，非持久化键）
     * @param metrics 该组覆盖的 `goals.metric` 集合（≥1）
     */
    data class Slot(val key: String, val metrics: List<String>)

    val PRIMARY = Slot("primary", listOf(GoalMetrics.PRIMARY))
    val KCAL = Slot("kcal", listOf(GoalMetrics.KCAL_DAILY))
    val WEIGHT = Slot("weight", listOf(GoalMetrics.WEIGHT_KG))
    val TRAIN = Slot(
        "train",
        listOf(GoalMetrics.SESSIONS_PER_WEEK, GoalMetrics.TRAIN_MINUTES_PER_WEEK),
    )
    val SLEEP = Slot("sleep", listOf(GoalMetrics.SLEEP_H))
    val WATER = Slot("water", listOf(GoalMetrics.WATER_ML))

    /** 全部槽位，顺序即设置页展示顺序（热量紧邻主目标 —— 它是增重/减重的量化口径）。 */
    val ALL: List<Slot> = listOf(PRIMARY, KCAL, WEIGHT, TRAIN, SLEEP, WATER)

    /**
     * 「添加目标」的**可选槽位**（= [ALL] 去掉主目标，清单3 R3 起再去掉 [KCAL]）。
     *
     * 主目标只能经 [com.healix.app.ui.GoalSetupSheet] 设定 —— 它带 `is_primary` 语义，
     * 不是"再加一条数值目标"，所以不进「添加目标」列表。
     *
     * ⚠️ 清单3 R3：KCAL 的唯一 UI 归属是设置页「热量摄入」独立栏（开关 + 数值行），
     *    因此 kcal 从可选集合移出 —— **数据行原样保留**（`kcal_daily` active 行继续驱动
     *    首页汇总 / 预警 / 计划口径，`kcalTargetOf` 不动），仅 UI 收编。
     *
     * ⚠️ 这是**目标数量上限的结构性来源**：固定可增槽位 4（+主目标 1 + 文本型自定义
     *    目标 1 = 6，次目标 5 = 4 固定 + 1 自定义，UI 可见口径）。固定槽位加满后
     *    本集合中无未启用项；加上自定义已使用 → 「添加目标」入口置灰（无自由新增路径）。
     */
    val ADDABLE: List<Slot> = listOf(WEIGHT, TRAIN, SLEEP, WATER)

    /** 按 key 取槽位（add-sheet 回调 key → Slot）。 */
    fun byKey(key: String): Slot? = ALL.firstOrNull { it.key == key }
}

/**
 * 目标值的**兜底默认**（唯一事实来源，其它文件引用这里，不要重定义）。
 *
 * 与 [GoalMetrics] 的区别：`GoalMetrics` 是 `goal.metric` 的**键**，
 * 这里是"用户没设过目标时用什么数"的**值**。两者必须分开 ——
 * 键是协议（进 DB、不能改），值是产品默认（可随指南更新）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么必须收敛到一处（2026-10-03 血泪）
 * ══════════════════════════════════════════════════════════════════════════
 * 此前同一组数在 `SettingsViewModel`（`DEFAULT_TRAIN_SESSIONS`）和
 * `StatusDetailViewModel`（`DEFAULT_SESSIONS_PER_WEEK`）各写了一份 ——
 * **值相同、名字不同**。除了日后必然会漂移，还直接引发了 CI #21 的编译失败：
 * `ExerciseSection` / `SleepSection` 是**顶层 data class**，它们的默认参数里
 * 非限定引用了 `StatusDetailViewModel.companion` 的常量，而顶层声明的解析域
 * 里没有那个 companion → `Unresolved reference`。
 *
 * 收敛到顶层 `object` 后，任何位置都能用 `GoalDefaults.X` 限定访问，问题不再复现。
 *
 * 取值依据：膳食指南推荐量（运动 150 分钟/周、饮水 1700ml、睡眠 7.5h）。
 * ⚠️ `HealthRules` 的 `H7`（< 150 分钟/周）**刻意不复用**这里的常量 ——
 * 那是临床判断阈值，不随用户改目标而变；耦合会让用户把目标调小后 H7 就不再预警。
 */
object GoalDefaults {
    /** 每周训练次数（次）。 */
    const val TRAIN_SESSIONS_PER_WEEK: Int = 3

    /** 每周训练时长（分钟）。 */
    const val TRAIN_MINUTES_PER_WEEK: Int = 150

    /** 每日睡眠（小时）。 */
    const val SLEEP_H: Double = 7.5

    /** 每日饮水（毫升）。 */
    const val WATER_ML: Int = 1700

    /**
     * 每日目标摄入（千卡）。v8 问题 2a 起，用户的 kcal 目标**存放在 `goals` 表**
     * （`metric = [GoalMetrics.KCAL_DAILY]`），本常量退化为"用户没设过目标时的兜底值"
     * 与老数据迁移的默认值 —— 与 [TRAIN_SESSIONS_PER_WEEK] 等同一角色。
     */
    const val TARGET_KCAL: Int = 2500
}

/**
 * 读「每日目标摄入」的**唯一入口**（v8 问题 2a）。
 *
 * 三级取值（顺序即优先级）：
 * 1. `goals` 表 `metric = kcal_daily` 且 active 的行的 `target_value`；
 * 2. 历史 settings 键 [SettingsKeys.TARGET_KCAL] —— 只覆盖**迁移窗口期**
 *    （老用户升级后、还没进过设置页触发迁移的那段时间），保证读数不回落成默认值；
 * 3. [GoalDefaults.TARGET_KCAL]。
 *
 * 迁移（`SettingsViewModel.migrateLegacyKcalTarget`）成功后会把旧键**删除**，
 * 于是第 2 级自然失效 —— 此后 "用户归档了 kcal 目标" 会正确回落默认值，
 * 而不会被一个残留的旧键悄悄顶住。
 *
 * ⚠️ 为什么收成一个函数：消费方有 4 处（首页汇总 / 预警聚合 / 计划生成 / 今日摘要），
 *    散着写 4 遍正是"改一处漏一处"的温床。
 */
suspend fun kcalTargetOf(db: AppDatabase): Int {
    return db.goalDao().getByMetric(GoalMetrics.KCAL_DAILY)
        ?.targetValue?.toInt()?.takeIf { it > 0 }
        ?: db.settingsDao().get(SettingsKeys.TARGET_KCAL)?.toIntOrNull()?.takeIf { it > 0 }
        ?: GoalDefaults.TARGET_KCAL
}
