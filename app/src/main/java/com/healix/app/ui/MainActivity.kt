package com.healix.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.healix.app.R
import com.healix.app.databinding.ActivityMainBinding

/**
 * 主界面宿主（**唯一 Activity**）。设计规范系统 11.1 v6；v8 导航骨架 §A.1。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * v8 结构：单 Activity + 全 Fragment
 * ══════════════════════════════════════════════════════════════════════════
 * - **三个常驻 Tab Fragment**（[RecordFragment] / [AssistantFragment] / [MineFragment]）
 *   挂在 `tabContainer` 上：`add` 一次 + `show/hide` 切换 —— Tab 互切**零窗口转场、
 *   零 Activity 重建**，这是需求 2「点 Tab 即响应」的根治。三页同为 Fragment，
 *   导航范式唯一（此前「助理」是 Fragment、「记录 / 我的」是 View 容器 = 半迁移）。
 * - **八个二级页**（状态详情/设置/计划/个人信息/知识库/资源/预设/调试）是
 *   `pageContainer` 上的 Fragment，经 [NavHost] 路由（`replace` + 回退栈），
 *   这是需求 1「二级页前进/后退卡顿」的根治。
 *
 * 本类只做"宿主"该做的事：承载容器 / 后台返回栈 / 外部入口（EXTRA_TAB、
 * EXTRA_FOCUS_INPUT）/ SAF 回传 / 通知权限。页面逻辑全部下放到各 Fragment。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 当前 Tab（`TabBar.TAB_RECORD` / `TAB_ASSISTANT` / `TAB_MINE`）。 */
    internal var currentTab: Int = TabBar.TAB_RECORD

    private var record: RecordFragment? = null
    private var assistant: AssistantFragment? = null
    private var mine: MineFragment? = null

    /**
     * Android 13+ 通知权限申请入口。
     *
     * 必须在 Activity **STARTED 之前**注册 —— 故放在属性初始化处（等价于 onCreate 阶段），
     * **不能**放进 onResume（否则抛 IllegalStateException: LifecycleOwner ... not in
     * CREATED state）。拒绝时不做处理，落到「我的」页的手动引导。
     */
    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 结果无需处理：拒绝则落到「我的」页的手动引导
        }

    /** 进程级标记：避免每次 onResume 反复弹（用户拒绝后不再骚扰）。 */
    private var askedNotifPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureTabs()

        currentTab = intent.getIntExtra(TabBar.EXTRA_TAB, TabBar.TAB_RECORD)
        TabBar.bind(this, currentTab) { tab -> showTab(tab) }
        // 键盘守卫：`@id/input` 在记录页与助理页各有一份，`hide()` 的 Fragment 视图仍
        // attach 在视图树上 → 必须按当前 Tab 解析（否则会命中隐藏的那个，结果不确定）。
        TabBar.bindImeGuard(this) {
            when {
                // 二级页盖住整个宿主时，不该再去动被盖住的 Tab 输入条
                NavHost.isOpen(this) -> null
                currentTab == TabBar.TAB_RECORD -> record?.view?.findViewById(R.id.input)
                currentTab == TabBar.TAB_ASSISTANT -> assistant?.view?.findViewById(R.id.input)
                else -> null
            }
        }

        // 二级页容器的命中判定（v8 T03）：空容器 clickable=false → 点击穿透到 Tab 页；
        // 有二级页时 clickable=true → 吞掉落在页面空白处的点击，不误触底下的 Tab。
        // ⚠️ 注册后必须**立即同步一次**：进程重建时回退栈里可能已有一个二级页，
        //    而 addOnBackStackChangedListener 只在"变化时"回调，不会补发当前状态。
        supportFragmentManager.addOnBackStackChangedListener { syncPageContainerHit() }
        syncPageContainerHit()

        showTabImmediate(currentTab)

        // 系统返回键：二级页优先弹栈；Tab 页非记录时返回 = 切回记录 tab
        // （原型 go() 语义：tab 平级、返回不退出）；已是记录 tab 才退出 App。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (NavHost.back(this@MainActivity)) return
                if (currentTab != TabBar.TAB_RECORD) {
                    showTab(TabBar.TAB_RECORD)
                } else {
                    finish()
                }
            }
        })
    }

    /**
     * 创建三个常驻 Tab Fragment（各 `add` 一次）。进程重建后 `findFragmentByTag`
     * 找回既有实例，不重复 add。
     */
    private fun ensureTabs() {
        val fm = supportFragmentManager
        record = fm.findFragmentByTag(TAG_RECORD) as? RecordFragment
            ?: RecordFragment().also { fm.beginTransaction().add(R.id.tabContainer, it, TAG_RECORD).commitNow() }
        assistant = fm.findFragmentByTag(TAG_ASSISTANT) as? AssistantFragment
            ?: AssistantFragment().also { fm.beginTransaction().add(R.id.tabContainer, it, TAG_ASSISTANT).commitNow() }
        mine = fm.findFragmentByTag(TAG_MINE) as? MineFragment
            ?: MineFragment().also { fm.beginTransaction().add(R.id.tabContainer, it, TAG_MINE).commitNow() }
    }

    /**
     * 通知 / 小工具等外部入口重开本页时走这里（本页已在栈顶）。
     * `EXTRA_TAB` 来自通知栏录入等外部入口（点通知直接落在指定 Tab）。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = intent.getIntExtra(TabBar.EXTRA_TAB, -1)
        if (tab in TabBar.TAB_RECORD..TabBar.TAB_MINE) {
            showTab(tab)
        }
        // 桌面小工具「记一笔」：本页已在栈顶时走这里 —— 记录 tab 下补聚焦速记框
        if (intent.getBooleanExtra(EXTRA_FOCUS_INPUT, false) &&
            currentTab == TabBar.TAB_RECORD
        ) {
            record?.requestFocusInput()
        }
    }

    /**
     * SAF 回传（v6 迁移，v8 T07 加导入、T08 加"就医材料"）：写文件/选文件入口在
     * 「我的」页（11.1），由 [MineFragment] 发起 —— 发起方用的是宿主的
     * `startActivityForResult`，回传必须在这里转发，否则用户选完文件后待写内容
     * 永远挂着、文件不会写入 / 备份不会被读（静默失败）。
     *
     * 两类回传的处理时机不同，是刻意的：
     * - **写文件**（导出备份 / 就医材料，[DocumentWriter]）在这里当场写，并立刻给
     *   Toast —— 内容已由发起方备好，写入是同步小操作。提示语按 [DocumentWriter.Kind]
     *   分流（两条流程共用同一套管线，但用户看到的文案必须说清刚才是哪件事）。
     * - **读文件**（导入，[ImportReader]）只在这里"交接"——读文件 + 写库是重活，
     *   放进它自己的 IO scope，结果经 `ImportReader.report` 回到「我的」页去展示。
     *   `onActivityResult` 跑在主线程上，绝不能在里面对 SQLite 做批量写。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        val docKind = DocumentWriter.onActivityResult(this, requestCode, resultCode, uri)
        if (docKind != null) {
            val ok = resultCode == RESULT_OK
            val msgRes = when (docKind) {
                DocumentWriter.Kind.EXPORT ->
                    if (ok) R.string.export_success else R.string.export_failed
                DocumentWriter.Kind.MEDICAL ->
                    if (ok) R.string.medical_save_success else R.string.medical_save_failed
            }
            android.widget.Toast.makeText(this, getString(msgRes), android.widget.Toast.LENGTH_SHORT)
                .show()
            return
        }
        ImportReader.onActivityResult(this, requestCode, resultCode, uri)
    }

    /**
     * Tab 平级切换：`hide(old)` + `show(new)`，**零动画、零窗口转场**（拍板 #2）。
     * 三个 Fragment 常驻不销毁 → 各 Tab 的滚动位置与输入草稿天然保留。
     * 高亮由 [TabBar.bind] 的触摸监听"按下即生效"，这里只切内容。
     */
    private fun showTab(target: Int) {
        if (currentTab == target) return
        currentTab = target
        TabBar.select(this, target)
        applyTab(target)
    }

    private fun showTabImmediate(target: Int) {
        currentTab = target
        applyTab(target)
    }

    /**
     * 三选一显现（其余 hide）。同一事务提交，避免中间帧出现两页叠影。
     *
     * 用 `commitNowAllowingStateLoss` 而非 `commitNow`：本方法也会从
     * [onNewIntent] 触发（外部入口重开本页），而该方法可能在 Activity 已
     * `onSaveInstanceState` 之后到达 → 普通 `commitNow/commit` 会抛
     * `IllegalStateException: Can not perform this action after onSaveInstanceState`。
     * Tab 显隐是纯视图状态、进程重建后由 `ensureTabs + showTabImmediate` 重放，允许丢失无害。
     */
    private fun applyTab(target: Int) {
        supportFragmentManager.beginTransaction().apply {
            setReorderingAllowed(true)
            record?.let { if (target == TabBar.TAB_RECORD) show(it) else hide(it) }
            assistant?.let { if (target == TabBar.TAB_ASSISTANT) show(it) else hide(it) }
            mine?.let { if (target == TabBar.TAB_MINE) show(it) else hide(it) }
        }.commitNowAllowingStateLoss()
    }

    /** 二级页容器命中态与回退栈同步（见 onCreate 注册处说明）。 */
    private fun syncPageContainerHit() {
        binding.pageContainer.isClickable = supportFragmentManager.backStackEntryCount > 0
    }

    override fun onResume() {
        super.onResume()
        // 回前台统一入口（G2/G5）：跨零点重算今日 day_key + 软删清理 + 刷新常驻通知副标题
        // + 规则扫描（PRD §7.4"打开时计算"，0 次 AI 调用）+ 桌面小工具推送。
        record?.onAppForeground()

        // 通知权限（G1）：Android 13+ 首次进入申请一次。POST_NOTIFICATIONS 未授予时
        // QuickInputService 的前台通知会被系统静默丢弃 → 「通知栏速记」入口整片消失。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotifPermission) {
            askedNotifPermission = true
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        /** 桌面小工具「记一笔」：打开本页并聚焦速记框（小工具侧 extra，
         *  见 HealixWidgetProvider.logIntent；冷启动由记录页 300ms 自动聚焦兜底）。 */
        const val EXTRA_FOCUS_INPUT = "healix.extra.FOCUS_INPUT"

        // 三个常驻 Tab Fragment 的 tag（= 类名，dumpsys 调试时一眼可辨）。
        private const val TAG_RECORD = "RecordFragment"
        private const val TAG_ASSISTANT = "AssistantFragment"
        private const val TAG_MINE = "MineFragment"
    }
}
