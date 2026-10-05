package com.healix.app

import android.content.Context
import android.os.Build
import android.os.Looper
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获器（v0.2.0 工程兜底）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么需要
 * ══════════════════════════════════════════════════════════════════════════
 * 用户真机（MagicOS）拿不到 logcat，崩溃后只剩「Healix 屡次停止运行」——
 * 没有堆栈就无法定位。本类在崩溃发生时把堆栈写入 App 私有存储，
 * **下次启动**由 [com.healix.app.ui.MainActivity] 弹对话框展示，
 * 用户可直接「复制 / 分享」把真实堆栈发回来。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 纪律（与既有架构对齐）
 * ══════════════════════════════════════════════════════════════════════════
 * 1. **纯增量**：只挂 [Thread.setDefaultUncaughtExceptionHandler] 并**链回**
 *    默认 handler —— 系统"停止运行"弹窗、进程终止等既有行为完全不变。
 * 2. **不碰 UI**：handler 可能在任意线程（含主线程）执行，这里只做文件 IO。
 * 3. **写入失败不吞崩溃**：整个写盘包在 runCatching 里 —— 捕获器自身失败
 *    绝不能打断默认 handler 的执行。
 * 4. **零新依赖**：纯框架 API；落点 `filesDir/crash/last_crash.txt`
 *    （内部存储，无需任何权限；卸载重装自然清除，无需主动清理）。
 * 5. **只保留 1 份**：固定文件名覆盖写 —— 排障只需要最近一次。
 * 6. **不记录敏感内容**：只写时间 / 版本 / 线程 / 堆栈；绝不主动读
 *    SecretStore 或数据库内容（堆栈本身除外，那是系统给的）。
 */
object CrashCatcher {

    /** 崩溃报告目录（`filesDir` 下）。 */
    private const val DIR_NAME = "crash"

    /** 崩溃报告文件名。固定单一文件 = 天然"只保留最近 1 份"。 */
    private const val FILE_NAME = "last_crash.txt"

    /** 对话框里展示的摘要上限（字符）：全堆栈可能上万字符，超出给截断提示。 */
    const val SUMMARY_MAX_CHARS = 1600

    /**
     * 安装全局崩溃捕获。**必须在 [HealixApp.onCreate] 尽早调用**，
     * 覆盖后续所有线程的未捕获异常。
     */
    fun install(appContext: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable) }
            // 链回默认 handler：保留系统"停止运行"弹窗与进程终止行为。
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 崩溃报告文件路径。读取方（MainActivity）与写入方共用同一落点。 */
    fun reportFile(context: Context): File =
        File(File(context.filesDir, DIR_NAME), FILE_NAME)

    /** 是否有待展示的上次崩溃报告。 */
    fun hasPendingReport(context: Context): Boolean =
        runCatching { reportFile(context).isFile }.getOrDefault(false)

    /** 读取上次崩溃报告全文；任何 IO 失败返回空串（展示方按"无报告"处理）。 */
    fun readReport(context: Context): String =
        runCatching { reportFile(context).readText(Charsets.UTF_8) }.getOrDefault("")

    /** 对话框展示用摘要：超长截断并附提示（全文走复制 / 分享）。 */
    fun summarize(report: String): String {
        if (report.length <= SUMMARY_MAX_CHARS) return report
        return report.take(SUMMARY_MAX_CHARS) + "\n…（已截断，复制或分享可获取全文）"
    }

    /**
     * 写入崩溃报告。崩溃时进程已在死亡路径上，这里刻意用最朴素的
     * 同步 IO —— 不开线程、不加锁，写完立刻返回，把控制权还给默认 handler。
     */
    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
            .toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            .format(Date(System.currentTimeMillis()))
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("unknown")
        val text = buildString {
            appendLine("Healix crash report")
            appendLine("time        : $time")
            appendLine("version     : $version")
            appendLine("android     : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("device      : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("thread      : ${thread.name} (main=${thread === Looper.getMainLooper().thread})")
            appendLine("process pid : ${android.os.Process.myPid()}")
            appendLine("stacktrace  :")
            append(stack)
        }
        val file = reportFile(context)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }
}
