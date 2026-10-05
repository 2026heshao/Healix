package com.healix.app.rules

import android.content.Context
import com.healix.app.R
import com.healix.app.db.EventEntity
import com.healix.app.notify.EventText
import com.healix.app.repo.parseFoodsJson

/**
 * 「最近记录」一键复用（v8 需求 9 功能 5；借鉴 MyFitnessPal 历史快速记录）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 它解决什么
 * ══════════════════════════════════════════════════════════════════════════
 * 首页预设横条已有「一键记录」（摩擦最低的路径），但预设是用户**预先建立**的；
 * 而人真正高频重复的是**最近记过的那几件**（今天的午饭、昨天的跑步）。本类把
 * 「最近记录」整理成同样可一键复用的 chip，接在预设横条右侧。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 边界纪律
 * ══════════════════════════════════════════════════════════════════════════
 * 1. **不调 AI**：复用 = 把一条历史记录的**结构化字段原样**再入库（见 `MainViewModel.logRecent`），
 *    不重新解析、不触网 —— 与预设一键记录同一条线。
 * 2. **只汇总事实**：chip 文案来自记录自身字段，不臆造。
 * 3. **隐私优先**：体重记录在 `HIDE_WEIGHT` 打开时显示遮罩（见 [label]）——
 *    首页三卡已经这么做了，这里若漏掉，用户一低头就在旁边看到被"隐藏"的数字。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 结构：[pick] / [fingerprintOf] 纯计算，[label] 贴文案
 * ══════════════════════════════════════════════════════════════════════════
 * [pick] 与 [fingerprintOf] 无 Context 无 IO，可在 `testDebugUnitTest` 里逐条断言；
 * [label] 只负责把事实贴进 `strings.xml` 模板。
 */
object RecentChips {

    /** 横条上最多展示几个「最近」chip（调研原文：取最近 3 条）。 */
    const val MAX_CHIPS: Int = 3

    /**
     * 从库里取多少条供去重。
     *
     * ⚠️ 必须 **> [MAX_CHIPS]**：用户常连续记同一样东西（今天三顿都记「米饭」），
     * 若只取 3 条再去重，可能只剩 1 个 chip。取 12 条即可在绝大多数情况下
     * 去重后仍凑满 3 个**不同**内容。
     */
    const val SCAN_LIMIT: Int = 12

    /** chip 文案上限：横条里 `wrap_content` 的长文案会把其它 chip 挤出屏幕。 */
    private const val MAX_LABEL_LEN: Int = 10

    /**
     * 从「最近记录」（已按 `ts DESC`）里去重取前 [count] 个。
     *
     * 去重键 = [fingerprintOf]：同类型 + 同内容只保留**最新的一条**。理由是
     * 连续记三次「米饭」若渲染成三个一模一样的 chip，既没信息量又占了横条。
     *
     * 纯函数：不读库、不看时钟、不改输入。
     */
    fun pick(events: List<EventEntity>, count: Int = MAX_CHIPS): List<EventEntity> {
        if (count <= 0) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<EventEntity>(count)
        for (event in events) {
            if (seen.add(fingerprintOf(event))) {
                out.add(event)
                if (out.size >= count) break
            }
        }
        return out
    }

    /**
     * 内容指纹：判断两条记录是不是「同一件事」。
     *
     * 按类型取该类型的**主体内容**，而不是 `rawText` —— 同一顿饭用户可能
     * 一次写「午饭」、一次写「中午吃了牛肉面」，`rawText` 不同但 `foods` 相同，
     * 应视为同一条。
     *
     * `body` / `sleep` 用数值（同体重 / 同时长才算重复）；其余类型回落 `rawText`。
     */
    fun fingerprintOf(event: EventEntity): String = when (event.type) {
        "meal" -> "meal|" + event.foods
        "exercise" -> "exercise|" + event.exercise.trim() + "|" + event.amount.trim()
        "body" -> "body|" + event.weightKg
        "sleep" -> "sleep|" + event.sleepH
        "illness" -> "illness|" + event.symptom.trim()
        else -> event.type + "|" + event.rawText.trim()
    }

    /**
     * chip 文案：贴 `strings.xml`，并遵循 [hideWeight] 隐私开关。
     *
     * - `meal` → 首项食物名（`foods` 解析失败则回落 `rawText`）—— 用户要复现的是"吃了什么"，
     *   不是热量数字；
     * - `exercise` → 动作名 + 数量（如「跑步 30 分钟」）；
     * - `body` → [hideWeight] 时遮罩（[R.string.recent_masked]），否则「65.4 kg」；
     * - 其余（`sleep` / `illness` / 其它）复用 [EventText.summary] 的口径，
     *   拿不到就回落 `rawText`，再拿不到就回落**类型名**，保证 chip 永不为空。
     */
    fun label(ctx: Context, event: EventEntity, hideWeight: Boolean): String {
        val text = when (event.type) {
            "meal" -> parseFoodsJson(event.foods).firstOrNull { it.isNotBlank() } ?: event.rawText
            "exercise" -> exerciseText(event)
            "body" -> if (hideWeight) {
                ctx.getString(R.string.recent_masked)
            } else {
                EventText.summary(ctx, event) ?: event.rawText
            }
            else -> EventText.summary(ctx, event) ?: event.rawText
        }
        val trimmed = text.trim().ifBlank { EventText.typeName(ctx, event.type) }
        return truncate(trimmed)
    }

    /** 动作名与数量拼接（如「跑步」「30 分钟」→「跑步 30 分钟」）；都空则回落 `rawText`。 */
    private fun exerciseText(event: EventEntity): String {
        val parts = listOf(event.exercise, event.amount)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.isEmpty()) event.rawText else parts.joinToString(" ")
    }

    /** 超长文案截断（`…` 与 `MedicalSummary.oneLine` 同口径）。 */
    private fun truncate(text: String): String =
        if (text.length <= MAX_LABEL_LEN) text else text.take(MAX_LABEL_LEN) + "…"
}
