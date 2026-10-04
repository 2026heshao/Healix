package com.healix.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.healix.app.HealixApp
import com.healix.app.R
import com.healix.app.db.SettingsKeys
import com.healix.app.repo.SOURCE_NOTIFICATION
import com.healix.app.parse.dayKeyOf
import com.healix.app.parse.dayStartHourOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 常驻通知快捷录入服务（★关键模块）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 参考实现：Fertion/QuickMDCapture（Kotlin, MIT）
 * ══════════════════════════════════════════════════════════════════════════
 * 按团队要求，**只借鉴其 API 调用顺序，不复制其代码**。借鉴到的顺序是：
 *
 *   1. Service.onCreate 建 NotificationChannel（Android 8+ 必须先建 channel）
 *   2. onStartCommand 里立刻 `startForeground(...)`（必须在 5 秒内，否则 ANR/崩溃）
 *   3. RemoteInput.Builder(KEY).setLabel(...).build()
 *   4. 用 `PendingIntent.getBroadcast(MUTABLE_FLAG)` 包一个 receiver，
 *      再 `addRemoteInput` 到 Action，最后 `addAction(action)` 到通知
 *   5. 提交后 `notificationManager.notify(id, updated)` 原地更新同一条通知
 *
 * Healix 的差异点（必须自己实现的部分）：
 *   - 数据落 Room（QuickMDCapture 写文件）
 *   - 副标题动态化（被动监督，见下）
 *   - 提交后自动变"已记录 · 撤销"并 5 秒后恢复（功能补充 3.3）
 *
 * ══════════════════════════════════════════════════════════════════════════
 * Android 14 硬约束
 * ══════════════════════════════════════════════════════════════════════════
 * `startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`
 * —— **必须传第三个参数 type**。不传或传错会在 API 34 上抛
 * `InvalidForegroundServiceTypeException` / `MissingForegroundServiceTypeException`。
 * 这是本文件最关键的一行，改动前请对照 Android 14 行为变更文档。
 *
 * manifest 已声明 `android:foregroundServiceType="specialUse"` + 对应 property。
 * ⚠️ `specialUse` 在部分国产 ROM（含 MagicOS）上可能被降级或忽略，
 *    需真机验证 —— 见 docs/待核实清单.md。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 被动监督（不依赖后台定时器）
 * ══════════════════════════════════════════════════════════════════════════
 * 总方案第十节已确认国行 ROM 会杀后台定时任务 → 不做 AlarmManager / WorkManager 定时刷新。
 * 改为：**每次通知被重建时（点开 App / 服务重启 / 提交后刷新）**重新计算副标题：
 *   - 今日已有记录 → "点此记录"
 *   - 今日 0 条 且 当前已过 20:00 → "今天还没记录 · 点此记录"
 * 效果等价（用户看通知的时刻就是它被刷新的时刻），成本为零。
 */
class QuickInputService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val repository by lazy { HealixApp.from(this).eventRepository }

    /** 当前通知的副标题状态，避免每次重建都写库 */
    @Volatile
    private var lastKnownNudgeState: NudgeState = NudgeState.Unknown

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ── 关键：必须在 5 秒内 startForeground，且 API 34 必须传 type ──
        // 每次都先以默认态 startForeground：保证匿名 startId 有对应的 startForeground 调用，
        // 避免 "did not then call Service.startForeground()" 类崩溃。
        startForegroundCompat(buildNotification(NotificationContent.Default(R.string.notif_subtitle)))

        // 命令分派：receiver 通过 startService + action 驱动通知更新
        when (intent?.action) {
            ACTION_SHOW_RECORDED -> {
                val cid = intent.getStringExtra(EXTRA_CLIENT_EVENT_ID).orEmpty()
                val labelRes = intent.getIntExtra(EXTRA_TYPE_LABEL_RES, R.string.type_other)
                val valueText = intent.getStringExtra(EXTRA_VALUE_TEXT).orEmpty()
                if (cid.isNotEmpty()) {
                    showRecorded(
                        NotificationContent.Recorded(
                            clientEventId = cid,
                            typeLabelRes = labelRes,
                            valueText = valueText,
                        )
                    )
                }
            }

            ACTION_SHOW_FAILED -> showFailed()

            ACTION_REFRESH_SUBTITLE -> {
                lastKnownNudgeState = NudgeState.Unknown
                refreshNudgeSubtitle()
            }

            else -> {
                // 默认：副标题动态化。异步算，算完再刷新一次通知（不阻塞 onStartCommand）
                refreshNudgeSubtitle()
            }
        }

        // START_STICKY：被系统回收后自动重建，保证常驻通知不消失
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 前台服务启动（API 34 分支）
    // ------------------------------------------------------------------

    /**
     * Android 14 (API 34) 起，`startForeground` 必须显式传 foregroundServiceType。
     * 用 Build.VERSION 分支而不是 try/catch —— 让约束在编译期可见，而不是运行期静默降级。
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ------------------------------------------------------------------
    // 通知构建
    // ------------------------------------------------------------------

    private fun createChannel() {
        // importance = LOW：常驻通知不响铃、不弹横幅、不亮屏 —— 它是入口，不是提醒
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * 构建通知。
     *
     * @param content 通知内容形态（默认 / 已记录 / 失败）
     */
    private fun buildNotification(content: NotificationContent): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify_healix)
            .setContentTitle(getString(R.string.notif_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        when (content) {
            is NotificationContent.Default -> {
                builder.setContentText(getString(content.subtitleRes))
                // 点击通知主体 → 打开主界面（而非直接弹键盘，主界面进入 300ms 后自动弹）
                builder.setContentIntent(mainActivityPendingIntent())
                builder.addAction(buildRemoteInputAction())
            }

            is NotificationContent.Recorded -> {
                val text = getString(
                    R.string.notif_recorded,
                    getString(content.typeLabelRes),
                    content.valueText,
                )
                builder.setContentText(getString(R.string.notif_recorded_with_undo, text))
                builder.setContentIntent(mainActivityPendingIntent())
                // 已记录态不再提供 RemoteInput —— 立刻再记一笔会把"撤销"挤掉
                builder.addAction(buildUndoAction(content.clientEventId))
            }

            is NotificationContent.Failed -> {
                builder.setContentText(getString(R.string.notif_failed))
                builder.setContentIntent(mainActivityPendingIntent())
                builder.addAction(buildRemoteInputAction())
            }
        }

        return builder.build()
    }

    /**
     * RemoteInput 动作。
     *
     * API 调用顺序（借鉴 QuickMDCapture）：
     *   1. RemoteInput.Builder(key).setLabel(hint).setAllowFreeFormInput(true).build()
     *   2. PendingIntent.getBroadcast(..., FLAG_MUTABLE)  ← 必须 MUTABLE，否则 RemoteInput 无法注入
     *   3. ActionCompat(icon, title, pi).addRemoteInput(remoteInput).build()
     *   4. builder.addAction(action)
     *
     * ⚠️ FLAG_IMMUTABLE 会让 RemoteInput 完全失效（系统无法写入结果）。
     *    这是一个极易踩的坑：Android 12+ 默认要求显式 flag，很多人加 IMMUTABLE 后就"输入框没反应"。
     */
    private fun buildRemoteInputAction(): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_QUICK_INPUT_TEXT)
            // 用设计规范里的话术，不是技术提示
            .setLabel(getString(R.string.notif_input_hint))
            .setAllowFreeFormInput(true)
            .build()

        val intent = Intent(this, QuickInputReceiver::class.java).apply {
            action = HealixApp.ACTION_QUICK_INPUT_SUBMIT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            REQUEST_CODE_INPUT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        return NotificationCompat.Action.Builder(
            R.drawable.ic_notify_send,
            getString(R.string.notif_action_input),
            pendingIntent,
        ).addRemoteInput(remoteInput).build()
    }

    /** 撤销动作：软删除刚刚入库的那条。 */
    private fun buildUndoAction(clientEventId: String): NotificationCompat.Action {
        val intent = Intent(this, UndoReceiver::class.java).apply {
            action = HealixApp.ACTION_UNDO_LAST
            putExtra(EXTRA_CLIENT_EVENT_ID, clientEventId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            REQUEST_CODE_UNDO,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Action.Builder(
            R.drawable.ic_notify_undo,
            getString(R.string.undo),
            pendingIntent,
        ).build()
    }

    /**
     * 点击通知 → 打开 MainActivity。
     *
     * 用 `SingleTop + CLEAR_TOP` 避免重复实例；由 MainActivity 自己决定是否弹键盘。
     */
    private fun mainActivityPendingIntent(): PendingIntent {
        val intent = Intent().apply {
            setClassName(packageName, "com.healix.app.ui.MainActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_CODE_MAIN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ------------------------------------------------------------------
    // 外部驱动：副标题刷新 / 展示结果
    // ------------------------------------------------------------------

    /**
     * 重新计算"今日是否无记录"并刷新通知副标题。
     *
     * 触发时机：onStartCommand（服务启动 / 被重建）、每次提交完成后。
     * 不做定时器 —— 见类注释"被动监督"。
     */
    private fun refreshNudgeSubtitle() {
        scope.launch {
            val state = try {
                // 日界线**必须与主 App 同口径**：读用户设置（经唯一入口 dayStartHourOf），
                // 不能写死 4 —— 否则通知栏算出的 day_key 与主 App 写入的 day_key 会跨天，
                // 副标题"今天还没记录"会误报/漏报（同族 bug：写入日键与读取日键分叉）。
                val db = HealixApp.from(this@QuickInputService).database
                val dayStartHour = withContext(Dispatchers.IO) {
                    dayStartHourOf(db.settingsDao().get(SettingsKeys.DAY_START))
                }
                val dayKey = dayKeyOf(System.currentTimeMillis(), dayStartHour)
                val hasRecord = repository.hasAnyEventToday(dayKey)
                val isPastEvening = isPastHour(NAG_HOUR, dayStartHour)
                if (!hasRecord && isPastEvening) NudgeState.Nagging else NudgeState.Normal
            } catch (e: Exception) {
                NudgeState.Normal
            }

            if (state == lastKnownNudgeState) return@launch
            lastKnownNudgeState = state

            val subtitleRes = if (state == NudgeState.Nagging) {
                R.string.notif_subtitle_nag
            } else {
                R.string.notif_subtitle
            }
            notifyUpdated(NotificationContent.Default(subtitleRes))
        }
    }

    /** 判断当前（按日界线口径）是否已过 [hour] 点。 */
    private fun isPastHour(hour: Int, dayStartHour: Int): Boolean {
        val cal = Calendar.getInstance()
        val nowHour = cal.get(Calendar.HOUR_OF_DAY)
        // 日界线之后的时间视为"当天"，日界线之前视为"昨天深夜"，后者不算过点
        if (nowHour < dayStartHour) return false
        return nowHour >= hour
    }

    /** 原地更新同一条通知（不重新 startForeground）。 */
    private fun notifyUpdated(content: NotificationContent) {
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(content))
        } catch (e: SecurityException) {
            // 通知权限被撤销：忽略，App 仍可用
        }
    }

    /**
     * 提交完成回调。由 [QuickInputReceiver] 通过 Intent 驱动。
     *
     * 已记录态显示 5 秒，然后恢复默认态（功能补充 3.3 / 设计规范 4.6）。
     * 用 Handler 而非协程 delay：这个 5 秒是"UI 停留时长"，不是业务逻辑，
     * 且 Service 可能在这期间被回收 —— 被回收后通知会随 onStartCommand 重建为默认态，
     * 行为仍然正确。
     */
    private fun showRecorded(content: NotificationContent.Recorded) {
        lastKnownNudgeState = NudgeState.Unknown // 强制下次重新计算
        notifyUpdated(content)

        mainHandler.postDelayed(revertRunnable, UNDO_WINDOW_MS)
    }

    private fun showFailed() {
        lastKnownNudgeState = NudgeState.Unknown
        notifyUpdated(NotificationContent.Failed)
        mainHandler.postDelayed(revertRunnable, UNDO_WINDOW_MS)
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val revertRunnable = Runnable {
        // 恢复默认态并重新计算副标题
        notifyUpdated(NotificationContent.Default(R.string.notif_subtitle))
        refreshNudgeSubtitle()
    }

    // ------------------------------------------------------------------
    // 通知内容形态
    // ------------------------------------------------------------------

    /** 通知的三种展示形态。 */
    sealed interface NotificationContent {
        /** 默认：带 RemoteInput，等待录入。subtitleRes 动态决定是否显示"催记录"。 */
        data class Default(val subtitleRes: Int) : NotificationContent

        /** 已记录：显示类型 + 数值 + 撤销。 */
        data class Recorded(
            val clientEventId: String,
            val typeLabelRes: Int,
            val valueText: String,
        ) : NotificationContent

        /** 失败：给重试入口。 */
        data object Failed : NotificationContent
    }

    private enum class NudgeState { Unknown, Normal, Nagging }

    companion object {
        /** RemoteInput 结果的 key。receiver 侧必须用同一个常量。 */
        const val KEY_QUICK_INPUT_TEXT = "healix_quick_input_text"

        /** 撤销所需的事件 id */
        const val EXTRA_CLIENT_EVENT_ID = "healix_client_event_id"

        /** Intent action：驱动 service 展示"已记录"态 */
        const val ACTION_SHOW_RECORDED = "com.healix.app.action.SHOW_RECORDED"

        /** Intent action：驱动 service 展示"失败"态 */
        const val ACTION_SHOW_FAILED = "com.healix.app.action.SHOW_FAILED"

        /** Intent action：刷新副标题 */
        const val ACTION_REFRESH_SUBTITLE = "com.healix.app.action.REFRESH_SUBTITLE"

        /** 已记录态停留时长（可撤销窗口）。设计规范 4.6：5 秒 */
        const val UNDO_WINDOW_MS = 5_000L

        /** 催记录的时间点：20:00（设计规范 4.6） */
        private const val NAG_HOUR = 20

        private const val NOTIFICATION_ID = HealixApp.NOTIFICATION_ID_QUICK_INPUT
        private const val CHANNEL_ID = HealixApp.CHANNEL_ID_QUICK_INPUT

        private const val REQUEST_CODE_INPUT = 3001
        private const val REQUEST_CODE_UNDO = 3002
        private const val REQUEST_CODE_MAIN = 3003

        /**
         * 启动（或确保已在运行）服务。
         *
         * 用 `startForegroundService` 而非 `startService`：
         * Android 8+ 后台启动普通 service 会抛 IllegalStateException，
         * 且前台服务能显著降低被 ROM 杀掉的概率。
         *
         * ⚠️ Android 12+ 对**后台**启动前台服务有限制（ForegroundServiceStartNotAllowedException）。
         * 因此本方法只应在 App 处于前台（MainActivity.onStart）或响应
         * BOOT_COMPLETED / 通知交互时调用。BootReceiver 场景属于系统豁免。
         */
        fun start(context: Context) {
            val intent = Intent(context, QuickInputService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // 后台启动被拒（Android 12+）：静默失败。
                // 用户下次进 App 时会重试启动，不影响功能可用性。
            }
        }

        /** 命令：展示"已记录"。用于 receiver 通知 service 更新通知。 */
        fun showRecorded(
            context: Context,
            clientEventId: String,
            typeLabelRes: Int,
            valueText: String,
        ) {
            val intent = Intent(context, QuickInputService::class.java).apply {
                action = ACTION_SHOW_RECORDED
                putExtra(EXTRA_CLIENT_EVENT_ID, clientEventId)
                putExtra(EXTRA_TYPE_LABEL_RES, typeLabelRes)
                putExtra(EXTRA_VALUE_TEXT, valueText)
            }
            startAndDeliver(context, intent)
        }

        /** 命令：展示"失败"。 */
        fun showFailed(context: Context) {
            val intent = Intent(context, QuickInputService::class.java).apply {
                action = ACTION_SHOW_FAILED
            }
            startAndDeliver(context, intent)
        }

        /** 命令：刷新副标题（MainActivity 每次进入前台时调用，实现"被动监督"）。 */
        fun refreshSubtitle(context: Context) {
            val intent = Intent(context, QuickInputService::class.java).apply {
                action = ACTION_REFRESH_SUBTITLE
            }
            startAndDeliver(context, intent)
        }

        private const val EXTRA_TYPE_LABEL_RES = "healix_type_label_res"
        private const val EXTRA_VALUE_TEXT = "healix_value_text"

        /**
         * 启动 service 并投递命令。
         *
         * 用 startService 快路径：若服务已在运行，startService 只是投递 onStartCommand，
         * 不会重走 onCreate/startForeground。若未运行，用 startForegroundService 保证合法。
         */
        private fun startAndDeliver(context: Context, intent: Intent) {
            try {
                context.startService(intent)
            } catch (e: Exception) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    }
                } catch (e2: Exception) {
                    // 两条路径都失败（后台限制）：静默
                }
            }
        }
    }
}
