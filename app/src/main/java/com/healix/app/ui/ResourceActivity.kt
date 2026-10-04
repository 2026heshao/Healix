package com.healix.app.ui

import android.os.Bundle
import android.widget.ImageButton
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.ActivityResourceBinding
import com.healix.app.repo.ResourceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 资源清单页（白板式手动声明）：手头的食物 / 药物 / 运动条件。
 *
 * 与设置页画像行的分工：画像（忌口/疼痛/场景/作息）是**约束与习惯**，
 * 这里是**手头有什么** —— 三类自由文本，想到什么写什么（"白画布直接输入"），
 * AI 每次回答与计划自动读取（ChatViewModel / PlanReviewViewModel 走
 * [ResourceStore] 统一读口）。
 *
 * 保存时机与设置页背景项同一套（[SettingsActivity.setupBackground] 先例）：
 * **失焦** + **onPause 兜底**，内容没变不写库。不做 TextWatcher 实时写 ——
 * 每键一次 SQLite 是无谓 IO。
 */
internal class ResourceActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var binding: ActivityResourceBinding

    /** 各输入框最近一次已落库内容，用于避免重复写入。 */
    private val lastSaved = HashMap<EditText, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResourceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // 回显：三个字段一次读完（IO 线程），逐框填入并登记"已落库"基线
        val db = HealixApp.from(this).database
        lifecycleScope.launch(Dispatchers.IO) {
            val foods = ResourceStore.foods(db)
            val meds = ResourceStore.meds(db)
            val sport = ResourceStore.sport(db)
            launch(Dispatchers.Main) {
                bind(binding.editFoods, foods)
                bind(binding.editMeds, meds)
                bind(binding.editSport, sport)
            }
        }

        // 失焦保存（三个框同一处理器）
        val saver = android.view.View.OnFocusChangeListener { v, hasFocus ->
            if (!hasFocus) save(v as EditText)
        }
        binding.editFoods.onFocusChangeListener = saver
        binding.editMeds.onFocusChangeListener = saver
        binding.editSport.onFocusChangeListener = saver
    }

    /** 回显 + 登记基线（登记放在 setText 时，避免首次回显触发"内容变了"误写）。 */
    private fun bind(edit: EditText, value: String) {
        edit.setText(value)
        lastSaved[edit] = value
    }

    private fun save(edit: EditText) {
        val key = when (edit.id) {
            R.id.editFoods -> ResourceKeys.FOODS
            R.id.editMeds -> ResourceKeys.MEDS
            R.id.editSport -> ResourceKeys.SPORT
            else -> return
        }
        val text = edit.text.toString().trim()
        if (lastSaved[edit] == text) return
        lastSaved[edit] = text
        val db = HealixApp.from(this).database
        lifecycleScope.launch(Dispatchers.IO) {
            if (text.isEmpty()) {
                db.settingsDao().remove(key)
            } else {
                db.settingsDao().put(com.healix.app.db.SettingEntity(key, text))
            }
        }
    }

    override fun onPause() {
        // 兜底：用户直接按返回 / 切后台时不走失焦回调，这里补一次
        super.onPause()
        if (::binding.isInitialized) {
            save(binding.editFoods)
            save(binding.editMeds)
            save(binding.editSport)
        }
    }

    /** v6 11.2：二级页返回走 in_back 转场（覆盖返回键与手势返回）。 */
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.in_back, R.anim.out_back)
    }
}

/** save() 的 key 分派表（收口到 SettingsKeys 常量，Activity 里不写裸字符串）。 */
private object ResourceKeys {
    const val FOODS = com.healix.app.db.SettingsKeys.PROFILE_FOODS
    const val MEDS = com.healix.app.db.SettingsKeys.PROFILE_MEDS
    const val SPORT = com.healix.app.db.SettingsKeys.PROFILE_SPORT
}
