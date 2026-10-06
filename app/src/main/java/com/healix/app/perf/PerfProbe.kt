package com.healix.app.perf

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Printer
import android.view.Choreographer
import android.view.Display
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap

/**
 * Dispatch 行解析。系统 printer 原始串形如：
 * `>>>>> Dispatching to Handler (<handler全类名>) {<hash>} <callback>: <what>`
 * —— `<msg.target>` 的 toString 即 `Handler (全类名) {hash}`，`<callback>` 是
 * `msg.callback`（数字型消息时为字面 `null`），其后紧跟 `": "` 故 token 带尾随冒号。
 * 分组：
 * - group(1) = handler 全类名（`Handler (...)` 括号内内容）；
 * - group(2) = callback token（**含尾随 `:`**，调用端用 [String.removeSuffix] 去掉）；
 * - group(3) = msg.what。
 * 格式不符不匹配（调用端兜底 unknown / null / -1，绝不抛异常；v3 起未命中时还会把
 * 原始行截断暂存并随 `SLOW_MSG` 落盘 `raw="…"`，用于定位解析失败的真实串）。
 */
private val DISPATCH_RE =
    Regex("""Dispatching to Handler \(([^)]*)\) \{[^}]*\} (\S+) (\d+)""")

/**
 * 帧率探针（清单3 R4）：**独立诊断模块，零业务耦合**。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 定位与红线
 * ══════════════════════════════════════════════════════════════════════════
 * - 非用户功能：不上设置页、无用户可见业务入口。开关/生命周期接线 = [com.healix.app.HealixApp]
 *   （onCreate 链尾 init，默认关闭）与 [com.healix.app.ui.DebugFragment]（手动开关 + 导出）；
 *   另有**只读观测接线** = [com.healix.app.ui.NavHost]（在 open/back 前后调用 [mark] 打导航锚点，
 *   并调用 [ensureLifecycleMarks] 打生命周期锚点）。
 *   [mark] / [ensureLifecycleMarks] 都是纯观测点：探针关闭时**零开销**
 *   （[mark] 只读一次开关直接返回；[ensureLifecycleMarks] 未开启时**不注册回调**，
 *   且 [stop] 会注销已注册的回调并清表），
 *   **禁止在此写业务状态**（观测点保持单向只读）。
 * - **绝无任何模型调用**：纯被动观察，不依赖网络栈，与 QuotaGuard / provider 零交集
 *   （`llm_calls` 零新增行的埋点不变式）。
 * - **默认关闭**：关闭状态零采集、零写盘；开关态落 **filesDir 标志文件**而非 settings 键
 *   （诊断状态 ≠ 用户配置，进 settings 会触碰 `check_settings_keys` 与导出纪律两条红线）。
 *   标志文件存在且内容为 "true" = 开启（重启保持）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 采集实现（只记违例，不记全量）
 * ══════════════════════════════════════════════════════════════════════════
 * 1. **长帧**：[Choreographer.postFrameCallback] 自循环，相邻帧间隔 > 2×vsync
 *    （[Choreographer.frameIntervalNanos] 动态取，高刷屏自适应；60Hz ≈ 33ms）记
 *    `LONG_FRAME <耗时>ms`。
 * 2. **主线程消息耗时**：[Looper.setMessageLogging]，按 `>>>>> Dispatching to` /
 *    `<<<<< Finished to` 前缀配对，起止差 ≥ [MSG_SLOW_THRESHOLD_MS] 记
 *    `SLOW_MSG h=<handler简单类名> c=<callback简单类名> what=<what> <耗时>ms`
 *    （h / c 分别取 dispatch 行括号内 handler 全名与 callback token 的简单类名：
 *    数字型消息 callback 为字面 `null`；解析失败回退 unknown / null / -1，不抛异常）。
 *    v3 起：解析失败（h 回退 unknown）时把原始 printer 行截断 200 字符随行落
 *    `raw="<截断后的原始行>"`，用于定位 `what=unknown` 这类归因盲区的真实来源。
 *    [stop] 时 `setMessageLogging(null)` 还原。
 * 3. **导航 / 生命周期锚点**：[mark] 在探针开启时经同一条落盘通道写一行 `MARK <tag>`，
 *    把 `LONG_FRAME` / `SLOW_MSG` 对齐到「具体哪次导航」——[com.healix.app.ui.NavHost]
 *    在 open/back 前后各调一次写 `MARK nav.*`；[ensureLifecycleMarks] 另对 `pageContainer`
 *    内的页在生命周期节点写 `MARK frag.*`，把单笔事务消息的几十 ms 拆成
 *    「inflate + onCreateView」「onViewCreated + onStart + onResume」等子阶段。
 *    关闭态零开销，探测点只读不写业务状态。
 * 4. **落盘**：专用 `HandlerThread("perf-probe")` 单线程顺序写（主线程零 I/O，压低
 *    观察者效应）；行格式 `<HH:mm:ss.SSS> <TAG> <detail> @<uptimeMs>`——`@<uptimeMs>`
 *    是 [write] 在**主线程调用点**采样的 `SystemClock.uptimeMillis()`（**绝不在落盘
 *    线程取**），落盘线程只负责 I/O，从而能复原同一笔消息内部的子阶段先后；
 *    文件 `filesDir/perf/perf_probe_YYYYMMDD.log`，单文件 > [LOG_MAX_BYTES] 轮转覆写。
 */
object PerfProbe {

    // ── 文件布局（filesDir/perf）──────────────────────────────────
    private const val DIR_NAME = "perf"
    private const val FLAG_FILE_NAME = "probe_enabled"
    private const val FLAG_TRUE = "true"
    private const val LOG_PREFIX = "perf_probe_"
    private const val LOG_SUFFIX = ".log"
    private const val LOG_DATE_FMT = "yyyyMMdd"
    private const val LOG_TIME_FMT = "HH:mm:ss.SSS"

    /** 单文件滚动上限：> 2MB 时删旧覆写（PRD R4 推荐口径）。 */
    private const val LOG_MAX_BYTES = 2L * 1024 * 1024

    /** 主线程消息耗时阈值（ms）：≥ 16ms 记一条 SLOW_MSG（PRD R4 推荐口径）。 */
    private const val MSG_SLOW_THRESHOLD_MS = 16L

    /** 解析失败时随 SLOW_MSG 落盘的原始 printer 行**截断长度上限**（字符，防日志膨胀）。 */
    private const val RAW_LINE_MAX_CHARS = 200

    /** 违例行写入缓冲：一次 write() 太碎，凑一行直接投递即可（HandlerThread 顺序写）。 */
    private const val LINE_FEED = '\n'

    /** 进程级运行态：只允许一个 start/stop 循环存在。 */
    @Volatile
    private var running: Boolean = false

    /** 落盘专用线程 + Handler（懒建，stop 不退出 —— 复用，重启探针零重建成本）。 */
    private var writerThread: HandlerThread? = null
    private var writerHandler: Handler? = null

    /** init/setEnabled 时持有的 applicationContext（写盘回调里解析 filesDir 用）。 */
    @Volatile
    private var appContext: Context? = null

    /**
     * 已注册「生命周期打点」回调的 FragmentManager（**弱键**）→ 该 FM 上已注册的回调实例。
     * 用弱键而非强引用：[PerfProbe] 是进程级单例，强引用被销毁的 FM 会经
     * `FM -> Activity` 泄漏宿主；弱键随 FM 回收自动清理。保存回调**实例**是为了
     * [stop] 时能用 [FragmentManager.unregisterFragmentLifecycleCallbacks] 精确注销；
     * [ensureLifecycleMarks] 靠 `containsKey` 保证「同一 FM 不重复注册」。
     */
    private val lifecycleHookedFms =
        WeakHashMap<FragmentManager, FragmentManager.FragmentLifecycleCallbacks>()

    // ── 采集状态（只在主线程触碰：Choreographer 回调与 Printer 都在主线程）────
    private var lastFrameNanos: Long = 0L
    private var dispatchStartUptimeMs: Long = 0L
    /** dispatch 行括号内 handler **全类名**（简单类名在落盘时再取）；解析失败 = "unknown"。 */
    private var dispatchHandler: String = "unknown"
    /** callback token（已去尾随 `:`，**保留全名**）；数字型消息 = 字面 "null"。 */
    private var dispatchCallback: String = "null"
    private var dispatchWhat: Int = -1
    /**
     * [DISPATCH_RE] 未命中时暂存的原始 printer 行（截断至 [RAW_LINE_MAX_CHARS] 字符）；
     * 命中时清空。仅用于「解析失败时随 SLOW_MSG 落盘原始串」，**不对 what=… 做任何
     * 来源断言**（那是待日志证实的事，代码里只如实记录）。
     */
    private var dispatchRawLine: String = ""

    /**
     * 长帧自循环回调。间隔 > 2×vsync 记 `LONG_FRAME`，随后无条件续订自己
     * （running=false 时不再续订，避免 stop 后残留回调）。
     */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val prev = lastFrameNanos
            lastFrameNanos = frameTimeNanos
            if (prev > 0L) {
                val intervalMs = (frameTimeNanos - prev) / 1_000_000.0
                val thresholdMs = frameIntervalMs() * 2.0
                if (intervalMs > thresholdMs) {
                    write("LONG_FRAME %.1fms (threshold %.1fms)".format(intervalMs, thresholdMs))
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * 主线程消息配对 Printer。`>>>>> Dispatching to Handler (<handler全类名>) {..} <callback>: <what> ..`
     * 用正则取 handler 全名 / callback token / what（防御性解析：格式不符回退
     * unknown / null / -1，不抛异常；未命中时把原始行截断暂存，随 SLOW_MSG 落盘）。
     */
    private val printer = object : Printer {
        override fun println(x: String?) {
            if (!running || x == null) return
            if (x.startsWith(">>>>> Dispatching to")) {
                val match = DISPATCH_RE.find(x)
                // 解析失败时暂存原始行（截断），随 SLOW_MSG 落盘以定位不明来源的消息；
                // 命中时清空。此处只如实记录原始串，不对其含义做断言。
                dispatchRawLine = if (match == null) x.take(RAW_LINE_MAX_CHARS) else ""
                val groups = match?.groupValues
                dispatchHandler =
                    groups?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "unknown"
                // callback token 的尾随冒号来自 printer 串的 `callback + ": "`，去掉后再取简单类名。
                dispatchCallback =
                    groups?.getOrNull(2)?.removeSuffix(":")?.takeIf { it.isNotBlank() } ?: "null"
                dispatchWhat = groups?.getOrNull(3)?.toIntOrNull() ?: -1
                dispatchStartUptimeMs = SystemClock.uptimeMillis()
            } else if (x.startsWith("<<<<< Finished to")) {
                if (dispatchStartUptimeMs <= 0L) return
                val elapsedMs = SystemClock.uptimeMillis() - dispatchStartUptimeMs
                dispatchStartUptimeMs = 0L
                if (elapsedMs >= MSG_SLOW_THRESHOLD_MS) {
                    // 解析失败（h=unknown）时把原始行随行落盘，解开归因盲区。
                    val rawSuffix =
                        if (dispatchRawLine.isNotEmpty()) " raw=\"$dispatchRawLine\"" else ""
                    write(
                        "SLOW_MSG h=${dispatchHandler.substringAfterLast('.')} " +
                            "c=${dispatchCallback.substringAfterLast('.')} " +
                            "what=$dispatchWhat ${elapsedMs}ms$rawSuffix",
                    )
                }
            }
        }
    }

    // ── 公开接口（HealixApp / DebugFragment / NavHost 调用方）──────────

    /**
     * 进程启动时调用（[com.healix.app.HealixApp.onCreate] 链尾）：
     * 读标志文件，启用则自启（满足「开关重启保持」）。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (isEnabled(context)) start()
    }

    /** 开关是否为开。标志文件存在且内容为 "true" 才算开（防御性读，异常按关闭处理）。 */
    fun isEnabled(context: Context): Boolean {
        val file = flagFile(context)
        if (!file.exists()) return false
        return runCatching { file.readText().trim() == FLAG_TRUE }.getOrDefault(false)
    }

    /**
     * 拨开关：写标志文件 + start()/stop()。关闭 = 删文件（下次判定按关闭）。
     * 小文件同步写在主线程完成（几字节，与 init 同口径）；日志写盘永远在专用线程。
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        appContext = context.applicationContext
        val file = flagFile(context)
        runCatching {
            file.parentFile?.mkdirs()
            if (enabled) {
                file.writeText(FLAG_TRUE)
            } else {
                file.delete()
            }
        }
        if (enabled) start() else stop()
    }

    /** 当日日志文件路径：`filesDir/perf/perf_probe_YYYYMMDD.log`。 */
    fun logFilePath(context: Context): File =
        File(ensurePerfDir(context), LOG_PREFIX + today() + LOG_SUFFIX)

    /**
     * 读全部探针日志（按文件名升序拼接，DebugFragment 导出用）。空 = 空串。
     */
    fun readLog(context: Context): String {
        val dir = File(context.filesDir, DIR_NAME)
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(LOG_PREFIX) }
            ?.sortedBy { it.name } ?: return ""
        return files.joinToString(LINE_FEED.toString()) { f ->
            runCatching { f.readText() }.getOrDefault("")
        }.trim()
    }

    /**
     * 轻量标记锚点（只读观测点）：探针**未开启时只读一次 [running] 直接返回**
     * ——零开销、零写盘、零日志；开启时经既有落盘通道写一行 `MARK <tag>`
     * （沿用 [write]，不新开线程、不新开文件）。
     *
     * 用途：把日志里的 `LONG_FRAME` / `SLOW_MSG` 对齐到「哪一次导航 / 哪个生命周期节点」——
     * [com.healix.app.ui.NavHost] 在 open/back 前后各调一次写 `MARK nav.*`；
     * [ensureLifecycleMarks] 的生命周期回调复用本函数写 `MARK frag.*`。
     * 判读时看违例落在哪两条 MARK 之间即可。
     *
     * ⚠️ 红线：本函数是**纯观测接线**，**禁止在此写业务状态**（保持单向只读）。
     */
    fun mark(tag: String) {
        if (!running) return
        write("MARK $tag")
    }

    /**
     * 生命周期打点（只读观测接线）：探针开启时给 [fm] 注册一个
     * [FragmentManager.FragmentLifecycleCallbacks]，对 [containerId] 容器内的二级页在
     * 各生命周期节点各写一行 `MARK frag.<state>`，把单笔事务消息里的几十 ms
     * **拆成可对齐的子阶段**：
     * - `frag.created → frag.viewCreated` = inflate + onCreateView 成本；
     * - `frag.viewCreated → frag.resumed` = onViewCreated + onStart + onResume 成本；
     * - `MARK nav.open… → frag.resumed` = 整笔事务 + 排队延迟。
     * 三者都带 `@<uptimeMs>`（[write] 主线程调用点采样），故子阶段可精确相减。
     *
     * **为什么按调用点采样 uptime**：wall-clock 前缀在落盘线程生成、只到「哪次导航」，
     * 而复原子阶段必须用「回调真正发生的时刻」——生命周期回调本就在主线程，
     * 由 [write] 在入口取 `SystemClock.uptimeMillis()` 即天然满足。
     *
     * **为什么关闭态零开销**：钩子只在探针开启时挂上（未开启时**一个回调都不注册**，
     * 而非注册后在回调体里 early-return）；且 [stop] 会**注销**已注册的回调并清表 ——
     * 故「曾开启 → 关闭」后 FM 上不再残留回调，连每个生命周期事件的一次方法分发与
     * `f.id == containerId` 比较都不会发生。这是 [com.healix.app.ui.NavHost]
     * KDoc 里「探针关闭时零开销」承诺的兑现前提。
     *
     * **为什么是纯只读观测**：回调全部只调用 [mark] 落一行锚点，不读不写任何业务状态、
     * 不改变事务、不改变可见性；接线方（[com.healix.app.ui.NavHost]）只在事务提交前
     * 调一次本函数，除此之外与探针无耦合。
     *
     * **容器 id 由调用方注入**（[containerId] 参数，而非探针内硬编码资源 id）：探针模块
     * 不感知任何 UI 资源 id，维持本模块「零业务耦合」的红线；仅对落在该容器里的页打点。
     *
     * 幂等：[lifecycleHookedFms]（弱键）保证同一 FM 只注册一次；recursive=false ——
     * 二级页无子 FragmentManager，无需递归注册（避免误伤子 fragment 的视图）。
     *
     * 回调签名与 **androidx.fragment 1.5.4 sources** 逐字核对（本机无 JDK，写错必在
     * CI 报 `overrides nothing`）：`onFragmentCreated` / `onFragmentViewCreated` 末参为
     * `@Nullable Bundle savedInstanceState`，`onFragmentViewCreated` 另有 `@NonNull View v`，
     * 其余 `onFragmentXxx(fm, f)` 两参。
     */
    fun ensureLifecycleMarks(fm: FragmentManager, containerId: Int) {
        // 硬要求：探针关闭时**不注册任何回调** —— 真零开销，而非注册后在回调体里早退。
        if (!running) return
        if (lifecycleHookedFms.containsKey(fm)) return
        val callback = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentCreated(
                fm: FragmentManager,
                f: Fragment,
                savedInstanceState: Bundle?,
            ) {
                if (f.id == containerId) mark("frag.created")
            }

            override fun onFragmentViewCreated(
                fm: FragmentManager,
                f: Fragment,
                v: View,
                savedInstanceState: Bundle?,
            ) {
                if (f.id == containerId) mark("frag.viewCreated")
            }

            override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                if (f.id == containerId) mark("frag.started")
            }

            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                if (f.id == containerId) mark("frag.resumed")
            }

            override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
                if (f.id == containerId) mark("frag.paused")
            }

            override fun onFragmentStopped(fm: FragmentManager, f: Fragment) {
                if (f.id == containerId) mark("frag.stopped")
            }

            override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
                if (f.id == containerId) mark("frag.viewDestroyed")
            }

            override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
                if (f.id == containerId) mark("frag.destroyed")
            }
        }
        fm.registerFragmentLifecycleCallbacks(callback, false)
        lifecycleHookedFms[fm] = callback
    }

    // ── 内部实现 ──────────────────────────────────────────────────

    @Synchronized
    private fun start() {
        if (running) return
        if (appContext == null) return
        running = true
        ensureWriter()
        lastFrameNanos = 0L
        dispatchStartUptimeMs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
        Looper.getMainLooper().setMessageLogging(printer)
    }

    @Synchronized
    private fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        // 还原主线程消息日志（探针自身的观察者效应一并撤除）
        Looper.getMainLooper().setMessageLogging(null)
        // 注销已注册的 Fragment 生命周期回调并清表：关闭态连「事件分发 + id 比较」都不再发生。
        // 先对 entries 取快照再遍历（避免边遍历边改 map）；FM 若已销毁，unregister 为无副作用
        // 调用，整体 runCatching 兜底 —— 探针绝不因自身故障影响宿主（与 CrashCatcher 同哲学）。
        for ((fm, callback) in lifecycleHookedFms.entries.toList()) {
            runCatching { fm.unregisterFragmentLifecycleCallbacks(callback) }
        }
        lifecycleHookedFms.clear()
    }

    /**
     * vsync 帧间隔（ms，动态取，≈16.67 @60Hz、≈8.33 @120Hz）。
     * CI 修复：`Choreographer.frameIntervalNanos` 非公开 API（Unresolved reference）——
     * 改走默认显示屏刷新率（[DisplayManager]，applicationContext 可取），
     * 与架构设计 Q5 假设②「2×vsync 动态阈值、高刷屏自适应」同效。
     */
    private fun frameIntervalMs(): Double {
        val refreshHz = runCatching {
            appContext?.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate
        }.getOrNull()?.takeIf { it > 0f } ?: 60f
        return 1000.0 / refreshHz
    }

    private fun ensureWriter() {
        if (writerThread == null) {
            val thread = HandlerThread("perf-probe")
            thread.start()
            writerThread = thread
            writerHandler = Handler(thread.looper)
        }
    }

    /**
     * 投递一行违例记录到落盘线程（主线程零 I/O）。未 start 时静默丢弃。
     *
     * `uptimeMs` 必须在此刻（**主线程调用点**）采样：这是 v3 统一时钟的落点 ——
     * 三个写入来源（[frameCallback] / [printer] / [mark]）都在主线程，采到的即
     * 「违例真正发生」的 `SystemClock.uptimeMillis()`；**绝不在落盘线程里取**，
     * 否则跨行对比会退化成只能对齐到「哪次导航」。
     */
    private fun write(detail: String) {
        val handler = writerHandler ?: return
        val uptimeMs = SystemClock.uptimeMillis()
        handler.post { appendToLog(detail, uptimeMs) }
    }

    /** 追加一行 `<HH:mm:ss.SSS> <detail> @<uptimeMs>`；先按大小轮转，再顺序写。 */
    private fun appendToLog(detail: String, uptimeMs: Long) {
        val ctx = appContext ?: return
        val file = logFilePath(ctx)
        runCatching {
            rotateIfNeeded(file)
            val time = SimpleDateFormat(LOG_TIME_FMT, Locale.US).format(Date())
            java.io.FileOutputStream(file, true).use { out ->
                out.write("$time $detail @$uptimeMs".toByteArray(Charsets.UTF_8))
                out.write(LINE_FEED.code)
            }
        }
        // 吞掉写盘异常：探针是诊断工具，绝不因自身故障影响宿主（与 CrashCatcher 同哲学）
    }

    /**
     * 滚动覆写（PRD「单文件 > 2MB 轮转覆写，删最旧」）：当前文件超限时整文件删除重开
     * （同日文件名重建即覆写）；同目录更早日期的日志文件一并清掉（删最旧）。
     */
    private fun rotateIfNeeded(current: File) {
        if (current.length() <= LOG_MAX_BYTES) return
        current.delete()
        current.parentFile?.listFiles { f -> f.isFile && f.name.startsWith(LOG_PREFIX) }
            ?.forEach { if (it.name != current.name) it.delete() }
    }

    private fun flagFile(context: Context): File =
        File(ensurePerfDir(context), FLAG_FILE_NAME)

    private fun ensurePerfDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun today(): String =
        SimpleDateFormat(LOG_DATE_FMT, Locale.US).format(Date())
}
