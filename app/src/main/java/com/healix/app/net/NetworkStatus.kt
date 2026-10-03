package com.healix.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.annotation.RequiresPermission

/**
 * 网络可达性判断。**唯一目的：把"没网"和"没配置"区分开。**
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么需要这个类（2026-10-03）
 * ══════════════════════════════════════════════════════════════════════════
 * `MainViewModel.stateOf()` 过去把 `needsConfiguration` 映射成 `MainUiState.Offline`。
 * 这是**语义错配**：`needsConfiguration` 的含义是"用户还没填 API Key"，
 * 而 `Offline` 的含义是"设备没网"。两者需要用户做的动作完全不同：
 *
 *   - 没配置 → 去设置页填 key
 *   - 没网络 → 检查 Wi-Fi / 移动数据，或稍后重试
 *
 * 更糟的是，由于 settings 键名分裂 bug，`loadProviderConfig()` 永远返回 null，
 * 于是**每一次提交都会返回 needsConfiguration = true**，界面就会一直显示
 * "离线" —— 用户描述为「记一笔无法正常联网」，实际是配置读不到 + 状态语义错位。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * Wi-Fi 与移动数据都能用的前提
 * ══════════════════════════════════════════════════════════════════════════
 * 本 App 的清单里已声明 `INTERNET` + `ACCESS_NETWORK_STATE`，
 * 且**没有**任何代码把请求绑定到特定网络传输类型（无 `bindProcessToNetwork`，
 * 无 `Network.openConnection` 指定 transport）—— 这是好事：
 * Android 默认会把 socket 交给**系统当前选中的默认网络**，
 * 无论它是 Wi-Fi 还是蜂窝数据，都不需要我们干预。
 *
 * 因此"Wi-Fi 和移动数据都能接入"**不需要额外代码**，只需要：
 *   1. 不写死任何一个网络（本文件不提供"选网络"能力，仅做可达性判断）
 *   2. 不用 `NetworkCapabilities.TRANSPORT_WIFI` 之类的过滤去判定可用性
 *      —— 那样会在纯移动数据下误判为"离线"
 *
 * 本类的 [isOnline] 用 `NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED`
 * 判断，**不区分传输类型**，天然同时覆盖 Wi-Fi 与移动数据。
 */
object NetworkStatus {

    /**
     * 当前是否有可用网络。
     *
     * 判据同时要求：
     * - `NET_CAPABILITY_INTERNET`：这个网络声明能上公网
     * - `NET_CAPABILITY_VALIDATED`：系统已实际探测通过（能解析并连上）
     *
     * 只要 VALIDATED 是因为"连上 Wi-Fi 但没网"（captive portal / 假热点）
     * 是最常见、也最误导人的场景 —— 此时 INTERNET 为 true 但 VALIDATED 为 false，
     * 直接发请求会全部超时。用 VALIDATED 才能给出"没网，请检查网络"的正确提示。
     *
     * ⚠️ 调用方必须有 `ACCESS_NETWORK_STATE` 权限（清单已声明）。
     *    API 31+ 起 `activeNetwork` 需要该权限，缺失会抛 SecurityException，
     *    这里兜底返回 true —— **宁可让请求去尝试并失败，也不要误报"离线"**
     *    把用户挡在门外。
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
    fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return true

            val network: Network = cm.activeNetwork ?: return false
            val caps: NetworkCapabilities =
                cm.getNetworkCapabilities(network) ?: return false

            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            // 权限异常 / ROM 实现差异：不阻塞主流程
            true
        }
    }

    /**
     * 当前默认网络是否是蜂窝（移动数据）。仅用于给用户更精确的提示文案，
     * **不参与任何连通性判定**。
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
    fun isCellular(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 注册默认网络变化监听。返回的 [AutoCloseable] 不持有 Activity，
     * 用 Application context 注册即可安全忽略 onDestroy。
     *
     * 提供这个能力是为了让"恢复联网后自动继续"成为可能 ——
     * 但当前 UI 采用更简单的策略（用户手动重试），
     * 所以本方法暂未被调用，保留给 S3+ 的自动重试队列用。
     */
    fun observeDefaultNetwork(
        context: Context,
        onChange: (online: Boolean) -> Unit,
    ): AutoCloseable {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return AutoCloseable { }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange(isOnline(context))
            override fun onLost(network: Network) = onChange(isOnline(context))
            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) = onChange(isOnline(context))
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            }
            AutoCloseable {
                try {
                    cm.unregisterNetworkCallback(callback)
                } catch (ignored: Exception) {
                    // 已注销 / 进程退出：忽略
                }
            }
        } catch (e: Exception) {
            AutoCloseable { }
        }
    }
}
