package com.healix.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.databinding.FragmentResourceBinding
import com.healix.app.repo.ResourceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 资源清单页（白板式手动声明）：手头的食物 / 药物 / 运动条件。
 *
 * v8 T03：由 [ResourceActivity] 迁为 Fragment（进宿主 `pageContainer`，
 * 零窗口转场）。迁移等价性——
 * - `onCreate` 体 → [onViewCreated]；`setContentView` → 返回 `binding.root`；
 * - 读回显挂 `viewLifecycleOwner.lifecycleScope`（视图销毁即取消，绝不错写已销毁的视图）；
 * - 写库挂 **fragment** 的 `lifecycleScope`（写语义必须活过视图拆解，
 *   否则"失焦/离开瞬间的最后一笔"会被取消丢掉）；
 * - `finish()` → `NavHost.back()`。
 *
 * 与设置页画像行的分工：画像（忌口/疼痛/场景/作息）是**约束与习惯**，
 * 这里是**手头有什么** —— 三类自由文本，想到什么写什么（"白画布直接输入"），
 * AI 每次回答与计划自动读取（ChatViewModel / PlanReviewViewModel 走
 * [ResourceStore] 统一读口）。
 *
 * 保存时机与个人信息页背景项一致：**失焦** + **onPause 兜底**，
 * 内容没变不写库。不做 TextWatcher 实时写 —— 每键一次 SQLite 是无谓 IO。
 *
 * v0.3 B3：改继承 [PageFragment] → 重新可见时经 [onPageShown] 重读并**逐框守卫**回填
 *   （本页是一次性读库，不随 Room Flow 实时刷新，属"真陈旧页"）。
 *   **不得用 `onResume` 替代**（keep-alive 下不触发）。
 */
internal class ResourceFragment : PageFragment() {

    private var _binding: FragmentResourceBinding? = null
    private val binding get() = _binding!!

    /** 各输入框最近一次已落库内容，用于避免重复写入。 */
    private val lastSaved = HashMap<EditText, String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentResourceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener { NavHost.back(requireContext()) }

        // 回显：三个字段一次读完（IO 线程），逐框填入并登记"已落库"基线
        val db = HealixApp.from(requireContext()).database
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val foods = ResourceStore.foods(db)
            val meds = ResourceStore.meds(db)
            val sport = ResourceStore.sport(db)
            launch(Dispatchers.Main) {
                bind(binding.editFoods, foods)
                bind(binding.editMeds, meds)
                bind(binding.editSport, sport)
            }
        }

        // 失焦保存 + 获焦滚动进可视区（P1-7）：两个职责由**同一个**监听器承载，
        // 不用单槽赋值再挂一次（那会把既有的失焦保存覆盖掉）。
        val contentScroll = binding.contentScroll
        val handler = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                // 把焦点行滚进可视区。v.top 相对直接父容器（ScrollView 的唯一子
                // LinearLayout，无额外 padding），故可直接当滚动目标 y。
                contentScroll.smoothScrollTo(0, v.top)
            } else {
                save(v as EditText)
            }
        }
        binding.editFoods.onFocusChangeListener = handler
        binding.editMeds.onFocusChangeListener = handler
        binding.editSport.onFocusChangeListener = handler
    }

    /** 回显 + 登记基线（登记放在 setText 时，避免首次回显触发"内容变了"误写）。 */
    private fun bind(edit: EditText, value: String) {
        edit.setText(value)
        lastSaved[edit] = value
    }

    /**
     * 重新可见时重读 [ResourceStore] 并**逐框守卫**回填（v0.3 B3，仅 [onPageShown] 调用）。
     *
     * ⚠️ **逐框守卫**：仅当 `!edit.hasFocus() && edit.text.toString() == lastSaved[edit]`
     *    （输入框未获焦，且当前文本 == 最近一次已落库基线 → 无未落库草稿）才回填；
     *    否则**跳过该框** —— 避免覆盖用户正在编辑 / 已改但尚未失焦保存的内容。
     *    口径对齐 [PersonalInfoFragment] 背景框的 `hasFocus()` 守卫。
     */
    private fun reloadFields() {
        val db = HealixApp.from(requireContext()).database
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val foods = ResourceStore.foods(db)
            val meds = ResourceStore.meds(db)
            val sport = ResourceStore.sport(db)
            launch(Dispatchers.Main) {
                refill(binding.editFoods, foods)
                refill(binding.editMeds, meds)
                refill(binding.editSport, sport)
            }
        }
    }

    /** 单框守卫回填：带未落库草稿（获焦 / 文本 ≠ 基线）则跳过该框。 */
    private fun refill(edit: EditText, value: String) {
        if (edit.hasFocus()) return
        if (edit.text.toString() != lastSaved[edit]) return
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
        val db = HealixApp.from(requireContext()).database
        // 用 fragment 作用域而非 viewLifecycleOwner：视图拆解不该取消一次已开始的落库
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
        val b = _binding ?: return
        save(b.editFoods)
        save(b.editMeds)
        save(b.editSport)
    }

    /** 重新可见（keep-alive 下由 [PageFragment.onHiddenChanged]`(false)` 触发）：重读 + 逐框守卫回填。 */
    protected override fun onPageShown() {
        reloadFields()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 视图引用必须释放：Fragment 常驻于回退栈外的宿主 Activity，
        // 不置 null 会把整棵视图树连同 Activity 一起钉在内存里。
        _binding = null
    }
}

/** save() 的 key 分派表（收口到 SettingsKeys 常量，Fragment 里不写裸字符串）。 */
private object ResourceKeys {
    const val FOODS = com.healix.app.db.SettingsKeys.PROFILE_FOODS
    const val MEDS = com.healix.app.db.SettingsKeys.PROFILE_MEDS
    const val SPORT = com.healix.app.db.SettingsKeys.PROFILE_SPORT
}
