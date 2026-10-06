package com.healix.app.perf

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Printer
import android.view.Choreographer
import android.view.Display
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dispatch 行解析。系统 printer 原始串形如：
 * `>>>>> Dispatching to Handler (<handler全类名>) {<hash>} <callback>: <what>`
 * —— `<msg.target>` 的 toString 即 `Handler (全类名) {hash}`，`<callback>` 是
 * `msg.callback`（数字型消息时为字面 `null`），其后紧跟 `": "` 故 token 带尾随冒号。
 * 分组：
 * - group(1) = handler 全类名（`Handler (...)` 括号内内容）；
 * - group(2) = callback token（**含尾随 `:`**，调用端用 [String.removeSuffix] 去掉）；
 * - group(3) = msg.what。
 * 格式不符不匹配（调用端兜底 unknown / null / -1，绝不抛异常）。
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
 *   另有**只读观测接线** = [com.healix.app.ui.NavHost]（在 open/back 前后调用 [mark] 打导航锚点）。
 *   [mark] 是纯观测点：探针关闭时**零开销**直接返回（不写盘、不打日志、不开线程），
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
 *    数字型消息 callback 为字面 `null`；解析失败回退 unknown / null / -1，不抛异常）；
 *    [stop] 时 `setMessageLogging(null)` 还原。
 * 3. **导航锚点**：[mark] 在探针开启时经同一条落盘通道写一行 `MARK <tag>`，把
 *    `LONG_FRAME` / `SLOW_MSG` 对齐到「具体哪次导航」（[com.healix.app.ui.NavHost]
 *    在 open/back 前后各调一次）；关闭态零开销，探测点只读不写业务状态。
 * 4. **落盘**：专用 `HandlerThread("perf-probe")` 单线程顺序写（主线程零 I/O，压低
 *    观察者效应）；行格式 `<HH:mm:ss.SSS> <TAG> <detail>`；文件
 *    `filesDir/perf/perf_probe_YYYYMMDD.log`，单文件 > [LOG_MAX_BYTES] 轮转覆写。
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

    // ── 采集状态（只在主线程触碰：Choreographer 回调与 Printer 都在主线程）────
    private var lastFrameNanos: Long = 0L
    private var dispatchStartUptimeMs: Long = 0L
    /** dispatch 行括号内 handler **全类名**（简单类名在落盘时再取）；解析失败 = "unknown"。 */
    private var dispatchHandler: String = "unknown"
    /** callback token（已去尾随 `:`，**保留全名**）；数字型消息 = 字面 "null"。 */
    private var dispatchCallback: String = "null"
    private var dispatchWhat: Int = -1

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
     * unknown / null / -1，不抛异常）。
     */
    private val printer = object : Printer {
        override fun println(x: String?) {
            if (!running || x == null) return
            if (x.startsWith(">>>>> Dispatching to")) {
                val groups = DISPATCH_RE.find(x)?.groupValues
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
                    write(
                        "SLOW_MSG h=${dispatchHandler.substringAfterLast('.')} " +
                            "c=${dispatchCallback.substringAfterLast('.')} " +
                            "what=$dispatchWhat ${elapsedMs}ms",
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
     * 用途：把日志里的 `LONG_FRAME` / `SLOW_MSG` 对齐到「哪一次导航」——
     * [com.healix.app.ui.NavHost] 在 open/back 前后各调一次，判读时看违例落在
     * 哪两条 MARK 之间即可。
     *
     * ⚠️ 红线：本函数是**纯观测接线**，**禁止在此写业务状态**（保持单向只读）。
     */
    fun mark(tag: String) {
        if (!running) return
        write("MARK $tag")
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

    /** 投递一行违例记录到落盘线程（主线程零 I/O）。未 start 时静默丢弃。 */
    private fun write(detail: String) {
        val handler = writerHandler ?: return
        handler.post { appendToLog(detail) }
    }

    /** 追加一行 `<HH:mm:ss.SSS> <detail>`；先按大小轮转，再顺序写。 */
    private fun appendToLog(detail: String) {
        val ctx = appContext ?: return
        val file = logFilePath(ctx)
        runCatching {
            rotateIfNeeded(file)
            val time = SimpleDateFormat(LOG_TIME_FMT, Locale.US).format(Date())
            java.io.FileOutputStream(file, true).use { out ->
                out.write("$time $detail".toByteArray(Charsets.UTF_8))
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
