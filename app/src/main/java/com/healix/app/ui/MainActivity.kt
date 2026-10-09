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
import androidx.core.view.ViewCompat
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
 * - **九个二级页**（状态详情/设置/计划/个人信息/知识库/资源/预设/调试/就医材料）是
 *   `pageContainer` 上的 Fragment，经 [NavHost] 路由（`add` + `hide/show` 保活，
 *   页面栈由 [NavHost] 自持、**不再用 FragmentManager 回退栈**），
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

    /** 进程级标记：避免同一次进程存续期内 onResume 反复弹。 */
    private var askedNotifPermission = false

    /**
     * 跨进程的「已询问过通知权限」标记（稳定性修复 2026-10-08）。
     *
     * 为什么不用 settings 表：[onResume] 在首帧前就要做同步判定，而 settings 表是
     * Room（异步）；且该标记是纯内部状态、不是用户可配置项，进 settings 表还会
     * 随导出/导入搬运，语义不对。普通 SharedPreferences（私有目录）同步可读、
     * 覆盖安装（`install -r`）不丢 —— 正好覆盖「覆盖安装后首启又弹一次」的实测问题。
     *
     * 口径：**只在真正弹出系统对话框前写入**。已授权路径不依赖它
     * （checkSelfPermission 恒过）；拒绝过的用户不再自动骚扰，落到「我的」页手动引导。
     */
    private val notifAskedPrefs by lazy {
        getSharedPreferences(PREFS_FLAGS, MODE_PRIVATE)
    }

    private fun hasAskedNotifPermission(): Boolean =
        notifAskedPrefs.getBoolean(PREF_NOTIF_ASKED, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureTabs()

        currentTab = intent.getIntExtra(TabBar.EXTRA_TAB, TabBar.TAB_RECORD)
        TabBar.bind(this, currentTab) { tab -> onTabSelected(tab) }
        // 键盘守卫（W2）：`@id/input` 在记录页与助理页各有一份。边距归零/还原必须
        // 对**两条** inputBar 全量求值 —— 只写「当前可见 Tab」那条时，记录页 IME 打开
        // （margin=0）→ 切助理 Tab 还原事件够不到记录条 → 切回时记录条贴底被 tabbar
        // 盖住（bug A）。`hide()` 的 Fragment 视图仍 attach 在树上，改 margin 安全。
        // 二级页盖住宿主时不该再去动被盖住的 Tab 输入条 → emptyList（语义保留）。
        TabBar.bindImeGuard(this) {
            if (NavHost.isOpen(this)) {
                emptyList()
            } else {
                listOfNotNull(
                    record?.view?.findViewById(R.id.input),
                    assistant?.view?.findViewById(R.id.input),
                )
            }
        }

        // 二级页栈还原（keep-alive 结构改造）：[NavHost] 是进程级 object，页面栈存在它的
        // 内存里，Activity 重建必须在这里把栈喂回去 —— 否则返回键会失效（见 NavHost KDoc）。
        // savedInstanceState == null（全新启动）即清空，显式复位、不沿用上一次会话的栈。
        NavHost.restoreState(savedInstanceState)

        // 二级页容器的命中判定（v8 T03）：没有可见二级页 clickable=false → 点击穿透到
        // Tab 页；有二级页时 clickable=true → 吞掉落在页面空白处的点击，不误触底下的 Tab。
        // 结构改造后已无 FragmentManager 回退栈，事件源换成 NavHost 自己的栈变化钩子
        // （addOnBackStackChangedListener 再也不会有回调）。注册后必须**立即同步一次**：
        // 钩子只在"变化时"回调，不会补发当前状态（进程重建时页已在、栈是刚还原的）。
        NavHost.onPageStackChanged = { syncPageContainerHit() }
        syncPageContainerHit()
        // keep-alive 保险：Activity 重建后校验容器内只有栈顶页可见
        // （hidden 状态未随重建恢复时兜底，见 NavHost.ensureRestoredVisibility）。
        NavHost.ensureRestoredVisibility(this)

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

        maybeShowLastCrash()

        // 二级页布局预热（2026-10-08，性能）：主线程空闲期把高频二级页的布局各热一遍，
        // 消掉「每次启动后**第一次**进入某页」那份冷 inflate（类加载 / 资源解析 / JIT）。
        // 挂在 IdleHandler 上 → 队列不空就完全不执行，不抢首帧与用户输入（见 PagePrewarm）。
        PagePrewarm.schedule(this)
    }

    /**
     * 二级页栈持久化（keep-alive 结构改造）：[NavHost] 的页面栈是**进程内**状态，
     * Activity 重建必须经 Bundle 往返一次，否则返回键失效（详见 [NavHost.saveState]）。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        NavHost.saveState(outState)
    }

    /**
     * 解引用栈变化钩子：钩子由宿主注册、[NavHost] 是**进程级** object —— 不在销毁时清掉
     * 就会在进程内悬着一个已销毁的 Activity（重建时由新的 `onCreate` 覆盖注册）。
     */
    override fun onDestroy() {
        NavHost.onPageStackChanged = null
        super.onDestroy()
    }

    /**
     * 崩溃报告展示（v0.2.0 工程兜底，与 [com.healix.app.CrashCatcher] 配对）：
     * 上次运行发生未捕获异常时，`filesDir/crash/last_crash.txt` 里留有真实堆栈 ——
     * 用户拿不到 logcat，这是唯一的回传通道。展示后**不删文件**
     * （卸载重装自然清除；保留最近 1 份，下次启动还会提示，直到用户转发出去）。
     *
     * 任何一步失败都静默跳过 —— 展示是加分项，绝不能反过来把启动弄崩。
     */
    private fun maybeShowLastCrash() {
        // 整体保护：show() 在窗口尚未 attach / ROM 差异下可能抛
        // BadTokenException 等 —— 展示是加分项，任何异常都静默跳过，
        // 绝不能反过来把启动本身弄崩（readReport 等读盘路径也一并兜住）。
        runCatching {
            if (!com.healix.app.CrashCatcher.hasPendingReport(this)) return@runCatching
            val report = com.healix.app.CrashCatcher.readReport(this)
            if (report.isBlank()) return@runCatching

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.crash_dialog_title)
                .setMessage(com.healix.app.CrashCatcher.summarize(report))
                .setPositiveButton(R.string.crash_dialog_share) { _, _ -> shareCrash(report) }
                .setNeutralButton(R.string.crash_dialog_copy) { _, _ -> copyCrash(report) }
                .setNegativeButton(R.string.crash_dialog_close, null)
                .show()
        }
    }

    /** 崩溃报告 → 剪贴板（用户粘贴回对话即可）。 */
    private fun copyCrash(report: String) {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(
            android.content.ClipData.newPlainText(getString(R.string.crash_dialog_title), report),
        )
        android.widget.Toast.makeText(this, R.string.crash_copied, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    /** 崩溃报告 → 系统分享面板（text/plain，用户选微信/邮件等任意通道发回）。 */
    private fun shareCrash(report: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.crash_dialog_title))
            putExtra(android.content.Intent.EXTRA_TEXT, report)
        }
        runCatching {
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.crash_dialog_share)))
        }
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
        val docResult = DocumentWriter.onActivityResult(this, requestCode, resultCode, uri)
        if (docResult != null) {
            // ⚠️ 判据用 `docResult.written`，**不是** `resultCode == RESULT_OK`（2026-10-09 修复）：
            //    resultCode 只说明"用户没在选择器里取消"，写入本身可能已经失败
            //    （provider 拒绝 / 空间不足 / openOutputStream 返回 null）。
            //    原先只看 resultCode → 写失败也弹「已导出到所选位置」，是**假成功**。
            val ok = docResult.written
            val msgRes = when (docResult.kind) {
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
        // W2（修 bug A 状态残留）：切 Tab 后强制 insets 重放 —— 让键盘守卫按最新
        // 的 Tab 可见性重新求值 tabbar 显隐与两条输入条边距（否则记录条可能永远卡 0）。
        ViewCompat.requestApplyInsets(window.decorView)
    }

    /**
     * Tab 点击入口（v6 §5.1）：异 Tab → 平级切换；**复点当前 Tab → 该页列表滚回顶部**。
     *
     * 刻意**不**把「滚回顶部」塞进 [showTab] 的 early-return 分支：`onNewIntent`
     * （通知栏速记 / 桌面小工具重开本页）也走 [showTab]，塞进去会让外部入口重开
     * 顺带触发滚动 —— 那是另一条语义（应保持"落在原位置"）。
     */
    private fun onTabSelected(target: Int) {
        if (target == currentTab) {
            scrollActiveTabToTop()
        } else {
            showTab(target)
        }
    }

    /**
     * 复点当前 Tab → 当前页滚动轴归零（记录/助理 = RecyclerView，我的 = ScrollView）。
     * 三页常驻不销毁，`_binding` 在视图销毁后为 null，故各页自身做空值守卫。
     */
    private fun scrollActiveTabToTop() {
        when (currentTab) {
            TabBar.TAB_RECORD -> record?.scrollToTop()
            TabBar.TAB_ASSISTANT -> assistant?.scrollToTop()
            TabBar.TAB_MINE -> mine?.scrollToTop()
        }
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

    /** 二级页容器命中态与页面栈同步（见 onCreate 注册处说明）。
     *  判据用 [NavHost.isOpen]（是否有**可见**页）而非「容器非空」：keep-alive 下退出的页
     *  仍留在容器里但已不可见，此时必须让点击穿透回 Tab 页。 */
    private fun syncPageContainerHit() {
        binding.pageContainer.isClickable = NavHost.isOpen(this)
        // W2：二级页开/关改变「谁可见」→ 强制 insets 重放，键盘守卫按新状态求值
        //（二级页打开时 bars 返回 emptyList，还原交给 Tab 切换后的重放）。
        ViewCompat.requestApplyInsets(window.decorView)
    }

    override fun onResume() {
        super.onResume()
        // 回前台统一入口（G2/G5）：跨零点重算今日 day_key + 软删清理 + 刷新常驻通知副标题
        // + 规则扫描（PRD §7.4"打开时计算"，0 次 AI 调用）+ 桌面小工具推送。
        record?.onAppForeground()

        // 通知权限（G1）：Android 13+ 首次进入申请一次。POST_NOTIFICATIONS 未授予时
        // QuickInputService 的前台通知会被系统静默丢弃 → 「通知栏速记」入口整片消失。
        // 稳定性修复（2026-10-08）：原实现只有进程级 askedNotifPermission —— 每次冷启动
        // （含覆盖安装后）只要权限仍未授予就再弹一次系统对话框。改为「系统对话框真正
        // 弹出前」写持久化标记：弹过一次（无论允许/拒绝）就不再自动弹；
        // 已授权路径本来就会被 checkSelfPermission 挡住，不依赖该标记。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotifPermission) {
            askedNotifPermission = true
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED && !hasAskedNotifPermission()
            ) {
                notifAskedPrefs.edit().putBoolean(PREF_NOTIF_ASKED, true).apply()
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        /** 桌面小工具「记一笔」：打开本页并聚焦速记框（小工具侧 extra，
         *  见 HealixWidgetProvider.logIntent；冷启动由记录页 300ms 自动聚焦兜底）。 */
        const val EXTRA_FOCUS_INPUT = "healix.extra.FOCUS_INPUT"

        /** 内部标记 SharedPreferences 文件名（非用户配置，不进 settings 表）。 */
        private const val PREFS_FLAGS = "healix_flags"

        /** 通知权限「已弹出过系统对话框」标记键（见 [hasAskedNotifPermission]）。 */
        private const val PREF_NOTIF_ASKED = "post_notifications_asked"

        // 三个常驻 Tab Fragment 的 tag（= 类名，dumpsys 调试时一眼可辨）。
        private const val TAG_RECORD = "RecordFragment"
        private const val TAG_ASSISTANT = "AssistantFragment"
        private const val TAG_MINE = "MineFragment"
    }
}
