package com.healix.app.ui

import com.healix.app.db.AppDatabase
import com.healix.app.parse.loadsLenient
import org.json.JSONObject

/**
 * 计划写路径落点（v0.3 B6，决策 CR-1 的语义落点）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么是独立文件、而不是给 `PlanGenerator` 加方法
 * ══════════════════════════════════════════════════════════════════════════
 * 设计 CR-1 指定落点为 `PlanGenerator.applyPlanChange`。但落地时 `PlanGenerator.kt`
 * 正处于**他会话未提交的 WIP** 状态（工作区纪律：一律只读、不改）—— 触碰它会把
 * 两路改动绞在一起。故把**同一语义**（读改写 `daily_plans.plan_json`）放这里承载：
 * 零接触 WIP 文件，行为契约不变。此为对 CR-1 **落点形式**的偏离（语义不变），
 * 已在交付说明中标注；`PlanGenerator` 内已有的 `PLAN_JSON_VERSION` / `persist`
 * 语义不受影响。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 契约
 * ══════════════════════════════════════════════════════════════════════════
 * - **读改写**：解析该日既有 `plan_json`（保留其全部字段，含版本号），只替换 `note`，
 *   再整份写回 —— 不新建计划、不改其它字段、不动 `source` / `generated_at`。
 * - **不凭空造计划**：该日没有计划行 → 返回 false（没什么可改）。
 * - **单协程内完成**：调用方在 `Dispatchers.IO` 的单协程里调用（读改写无并发缝隙）。
 */
internal object PlanChangeWriter {

    /**
     * 把某日计划的备注改为 [note]。
     *
     * @return true = 已写回；false = 该日没有计划 / `plan_json` 无法解析（调用方据此回执失败）。
     */
    suspend fun applyPlanChange(db: AppDatabase, date: String, note: String): Boolean {
        val row = db.planDao().getPlan(date) ?: return false
        val obj = parsePlanObject(row.planJson) ?: return false
        obj.put("note", note)
        db.planDao().upsertPlan(row.copy(planJson = obj.toString()))
        return true
    }

    /**
     * 解析 `plan_json` 为对象；空 / 非对象 / 解析失败 → null。
     * 防御式解析（与 `ChatViewModel.todayPlanNote` 同款），绝不抛异常。
     */
    private fun parsePlanObject(json: String?): JSONObject? = runCatching {
        if (json.isNullOrBlank()) return@runCatching null
        loadsLenient(json) as? JSONObject
    }.getOrNull()
}
