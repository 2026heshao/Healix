package com.healix.app

import android.app.Application
import android.content.Context
import com.healix.app.db.AppDatabase
import com.healix.app.notify.QuickInputService
import com.healix.app.repo.EventRepository
import com.healix.app.repo.QuotaGuard
import com.healix.app.security.SecretStore

/**
 * Healix Application。
 *
 * 职责只有一件事：**按需初始化并持有全局单例**。不做任何耗时 I/O（onCreate 在主线程）。
 * - AppDatabase：`AppDatabase.get()` 内部已做 double-check 单例，这里只是预热引用。
 * - SecretStore：EncryptedSharedPreferences + Keystore，有创建开销，进程内必须复用一份。
 * - EventRepository / QuotaGuard：无状态，持有 DAO 与 provider 工厂，直接构造。
 *
 * 刻意不在 onCreate 里启动 QuickInputService —— 前台服务在进程冷启动时可以被拉起，
 * 但 Android 12+ 对后台启动前台服务有限制，交由 MainActivity 在前台时显式启动更稳。
 * 开机恢复由 BootReceiver 负责。
 */
class HealixApp : Application() {

    /** 数据库。Room 侧已是线程安全单例，此处仅做一次预热引用。 */
    val database: AppDatabase by lazy { AppDatabase.get(this) }

    /**
     * 密钥存储。**可能为 null**（少数 ROM 的 Keystore 异常）——
     * 所有调用方必须容忍 null 并走"未配置 provider"的降级路径。
     */
    val secretStore: SecretStore? by lazy { SecretStore.get(this) }

    /** 事件仓库：串联 net → parse → db。 */
    val eventRepository: EventRepository by lazy { EventRepository(this) }

    /** 调用预算护栏。 */
    val quotaGuard: QuotaGuard by lazy { QuotaGuard(this) }

    override fun onCreate() {
        super.onCreate()
        // 触发 Room 单例构建（不打开数据库文件连接，真正的连接在首次查询时建立）。
        database
        // 触发密钥存储初始化。失败时返回 null，不崩溃。
        secretStore
    }

    companion object {
        /** 从任意 Context 取 Application 的便捷入口。 */
        fun from(context: Context): HealixApp =
            context.applicationContext as HealixApp

        /** 前台服务通知 id。全 App 共用一个常驻通知槽位。 */
        const val NOTIFICATION_ID_QUICK_INPUT: Int = 1001

        /** 前台服务 channel id（Android 8+ 必须）。 */
        const val CHANNEL_ID_QUICK_INPUT: String = "healix_quick_input"

        /**
         * 意图动作常量统一放这里，避免 receiver / service 各自定义字符串导致对不上。
         * 全部带包名前缀，防止与其它 App 冲突。
         */
        const val ACTION_QUICK_INPUT_SUBMIT: String = "com.healix.app.action.QUICK_INPUT_SUBMIT"
        const val ACTION_UNDO_LAST: String = "com.healix.app.action.UNDO_LAST"

        /**
         * 提示：[待核实: 国行 ROM 的后台存活策略]
         * MagicOS 对 specialUse 类型前台服务的处理需真机验证，
         * 见 docs/待核实清单.md。此处不做任何 ROM 适配 hack。
         */
        const val SERVICE_START_REQUEST_CODE: Int = 2001
    }
}

/** 供 Service 在无 Context 时便捷取 repo（避免各处重复强转）。 */
fun Context.healixApp(): HealixApp = HealixApp.from(this)

/** QuickInputService 的启动入口，供 MainActivity / BootReceiver 复用。 */
fun Context.startQuickInputService() {
    QuickInputService.start(this)
}
