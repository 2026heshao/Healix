package com.healix.app.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.ActivityPlanReviewBinding
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 计划 / 复盘（同一页两个文字 Tab，设计规范系统 4.3）。
 *
 * 定位调整（UI 设计方案第八节）：**以计划为主，复盘降为辅**。
 * 对增重来说"今晚吃什么"比"今天吃得怎么样"更有价值。
 */
class PlanReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlanReviewBinding
    private lateinit var vm: PlanReviewViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlanReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = PlanReviewViewModel(HealixApp.from(this))

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { vm.refresh() }
        binding.tabPlan.setOnClickListener { selectTab(plan = true) }
        binding.tabReview.setOnClickListener { selectTab(plan = false) }

        selectTab(plan = true)
        observe()
    }

    private fun selectTab(plan: Boolean) {
        vm.showPlan(plan)

        // 选中态：text_1 + 下方 2dp accent 线；未选中：text_2 + 无底线
        binding.tabPlan.setTextColor(
            androidx.core.content.ContextCompat.getColor(this, if (plan) R.color.text_1 else R.color.text_2)
        )
        binding.tabReview.setTextColor(
            androidx.core.content.ContextCompat.getColor(this, if (plan) R.color.text_2 else R.color.text_1)
        )
        binding.tabPlanUnderline.visibility = if (plan) View.VISIBLE else View.INVISIBLE
        binding.tabReviewUnderline.visibility = if (plan) View.INVISIBLE else View.VISIBLE

        binding.planGroup.visibility = if (plan) View.VISIBLE else View.GONE
        binding.reviewGroup.visibility = if (plan) View.GONE else View.VISIBLE
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    vm.plan.collect { plan ->
                        binding.gapLabel.text = if (plan.gapLeft > 0) {
                            getString(R.string.plan_gap_label, plan.gapLeft)
                        } else {
                            getString(R.string.plan_reached)
                        }
                        renderItems(plan)
                        binding.planNote.text = plan.note
                        binding.planNote.visibility =
                            if (plan.note.isNullOrBlank()) View.GONE else View.VISIBLE
                    }
                }

                launch {
                    vm.review.collect { r ->
                        binding.statIn.text = if (r.kcalIn > 0) r.kcalIn.toString() else "—"
                        binding.statOut.text = if (r.kcalOut > 0) r.kcalOut.toString() else "—"
                        binding.statWeight.text = if (r.weightKg > 0) trimNumber(r.weightKg) else "—"
                        binding.reviewText.text = r.content.ifBlank { getString(R.string.nodata) }
                    }
                }
            }
        }
    }

    private fun renderItems(plan: PlanUiState) {
        binding.itemContainer.removeAllViews()
        if (plan.items.isEmpty()) {
            binding.planNote.text = getString(R.string.nodata)
            return
        }
        for (item in plan.items) {
            val row = layoutInflater.inflate(
                R.layout.item_plan_suggestion, binding.itemContainer, false,
            )
            val title = row.findViewById<android.widget.TextView>(R.id.itemTitle)
            val detail = row.findViewById<android.widget.TextView>(R.id.itemDetail)
            val logBtn = row.findViewById<android.widget.TextView>(R.id.btnLogThis)

            title.text = getString(R.string.plan_item_kcal, item.title, item.kcal)
            detail.text = item.detail
            logBtn.setOnClickListener { vm.logSuggestion(item) }
            binding.itemContainer.addView(row)
        }
    }

    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
