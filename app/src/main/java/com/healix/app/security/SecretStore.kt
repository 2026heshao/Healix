package com.healix.app.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API Key 的安全存储（安全关键类，C6 约束，不可协商）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 安全约束（本文件头注释即纪律，改动本文件前必须逐条确认）
 * ══════════════════════════════════════════════════════════════════════════
 * 1. API key **只**存 EncryptedSharedPreferences（AES-256-GCM，主密钥由 Android Keystore 托管）。
 * 2. API key **绝不**写入 SQLite（settings 表只存 baseUrl / model / 配额 / 重试参数）。
 *    —— 明文 SQLite 可被 root / adb backup / 第三方清理工具读到。
 * 3. API key **绝不**写入 Logcat、崩溃信息、埋点 error_head、导出备份。
 * 4. **绝不打印 key 本身，也绝不打印其长度**（防长度侧信道推断）。
 * 5. 只暴露三个方法：saveApiKey / apiKey / clear。不提供任何"调试用"的 dump 接口。
 *
 * 本类刻意不实现 toString()（避免对象被 %s 打印时泄露），
 * 也刻意不给 apiKey() 加任何日志。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * [待核实: androidx.security:security-crypto 版本]
 * ══════════════════════════════════════════════════════════════════════════
 * app/build.gradle 当前钉的是 `1.1.0-alpha06`，这是**占位版本号**。
 * 核实方式：打开 https://developer.android.com/jetpack/androidx/releases/security
 *   查看 security-crypto 当前稳定版（1.0.0 稳定，1.1.0 长期停留 alpha）。
 * 核实后同步修改 app/build.gradle 的依赖版本，并确认 MasterKey / EncryptedSharedPreferences
 * 的 API 签名在本项目使用的版本上未变（alpha06 与 1.0.0 的 Builder 参数一致）。
 */
class SecretStore private constructor(context: Context) {

    private val prefs: SharedPreferences

    init {
        // MasterKey：主密钥不落盘，由 Android Keystore 托管（AES256_GCM）。
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * 保存 API key。
     *
     * 传入空白串等同于清空 —— 设置页允许用户把 key 删空。
     * 注意：本方法不校验 key 格式（各平台格式不同，不做臆测），也不做任何日志输出。
     */
    fun saveApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            clear()
            return
        }
        prefs.edit().putString(KEY_API_KEY, trimmed).apply()
    }

    /**
     * 读取 API key。未设置时返回 null。
     *
     * ⚠️ 调用方拿到后只允许直接塞进 HTTP Authorization 头，
     * 禁止 log / 禁止拼进异常信息 / 禁止落库。参见 net/OpenAiCompatProvider 的脱敏逻辑。
     */
    fun apiKey(): String? {
        val value = prefs.getString(KEY_API_KEY, null) ?: return null
        return if (value.isEmpty()) null else value
    }

    /** key 是否已配置。**只返回布尔，不暴露任何 key 特征。** */
    fun hasApiKey(): Boolean = apiKey() != null

    /** 清除 key。卸载 / 重置设置时调用。 */
    fun clear() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    companion object {
        private const val TAG = "SecretStore"

        /** 独立文件名，与普通 SharedPreferences 隔离 */
        private const val PREFS_FILE_NAME = "healix_secret_store"

        private const val KEY_API_KEY = "api_key"

        @Volatile
        private var instance: SecretStore? = null

        /**
         * 单例。EncryptedSharedPreferences 的创建涉及 Keystore 操作，有开销，必须复用。
         *
         * 创建失败（极少数 ROM 的 Keystore 异常）时返回 null —— 调用方必须容忍 null，
         * 走"未配置 provider"的降级路径，而不是崩溃。
         */
        fun get(context: Context): SecretStore? {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: try {
                    SecretStore(context.applicationContext).also { instance = it }
                } catch (e: Exception) {
                    // 只记异常类型，不记任何 key 相关信息（此时也还没有 key）
                    Log.e(TAG, "初始化安全存储失败：${e.javaClass.simpleName}")
                    null
                }
            }
        }
    }
}
