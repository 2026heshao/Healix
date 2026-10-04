package com.healix.app.repo

import com.healix.app.db.AppDatabase
import com.healix.app.db.SettingsKeys

/**
 * 资源清单（白板式手动声明）的统一读写口。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么收敛到这里（与 SettingsKeys 同一个教训：键名/口径分叉是编译期不可见的）
 * ══════════════════════════════════════════════════════════════════════════
 * 三类资源（食物/药物/运动条件）有 **4 个消费方**：资源清单页（写）、
 * ChatViewModel（对话注入）、PlanReviewViewModel（行动条）、MinePage（入口值）。
 * 若各自裸写 `settingsDao().get(...)`，「运动条件读哪个 key」这种口径一旦
 * 分叉（比如有人忘了旧 gear 迁移兜底），不会有任何编译错误 —— 只有 AI
 * 开始推荐你没有的东西时才会暴露。所以读端只许走这里。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 旧数据迁移（profile_gear → profile_sport，合并升级）
 * ══════════════════════════════════════════════════════════════════════════
 * 采取**非破坏性兜底**而不是一次性搬移：sport 为空时回落读 gear。
 * 理由：一次性搬移必须找全局写入时机（App 启动钩子），而 sport 被填过之后
 * 兜底自然失效，效果等价且零风险（不写库、失败不丢数据）。gear 键只在
 * 兜底里被读，新 UI 禁止再写它。
 */
object ResourceStore {

    /** 手头现成的食物（自由文本，多行）。空串 = 未填写。 */
    suspend fun foods(db: AppDatabase): String =
        db.settingsDao().get(SettingsKeys.PROFILE_FOODS).orEmpty().trim()

    /** 常备药物（自由文本）。空串 = 未填写。 */
    suspend fun meds(db: AppDatabase): String =
        db.settingsDao().get(SettingsKeys.PROFILE_MEDS).orEmpty().trim()

    /**
     * 运动条件（器材 + 场地 + 时段，自由文本）。
     * 旧数据兜底：sport 从未填过时回落到原「可用器材」（profile_gear，
     * JSON 数组），并把 JSON 的引号括号剥掉当纯文本用 —— 只为过渡期
     * 不丢已有数据，显示口径上等价。
     */
    suspend fun sport(db: AppDatabase): String {
        val own = db.settingsDao().get(SettingsKeys.PROFILE_SPORT).orEmpty().trim()
        if (own.isNotEmpty()) return own
        val legacy = db.settingsDao().get(SettingsKeys.PROFILE_GEAR).orEmpty().trim()
        return legacy.removePrefix("[").removeSuffix("]").replace("\"", "").trim()
    }

    /** 「我的」页入口值：三类里已填几类（0–3）。 */
    suspend fun filledCount(db: AppDatabase): Int =
        listOf(foods(db), meds(db), sport(db)).count { it.isNotEmpty() }

    /**
     * 自由文本 → 食物名列表（行动条用）：按 换行/顿号/逗号/分号/· 切，
     * 去空去重，保序。白板输入不强制格式，这里做宽容解析。
     */
    fun splitItems(text: String): List<String> =
        text.split('\n', '、', '，', '；', ';', '·')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
