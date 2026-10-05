package com.healix.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * SAF「把一段文本存成文件」——全 App **唯一**的写文件管道。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么抽出来（v8 需求 9 功能 3）
 * ══════════════════════════════════════════════════════════════════════════
 * 原先是 `ExportWriter` 自己拿着 `REQUEST_CODE` / `pendingPayload` / 异常处理。
 * 「就医材料」（[MedicalSummaryFragment]）要做的**是同一件事**：让用户选位置、
 * 把一段文本写过去。管线抄第二份意味着以后任何一处修正（MIME 白名单、
 * 写失败的处置、进程被杀后的提示）都要记得改两处 —— 所以收敛到这里，
 * 双方只提供 `(mime, 文件名, 内容)`。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么用 `startActivityForResult` 而不是 `registerForActivityResult`
 * ══════════════════════════════════════════════════════════════════════════
 * launcher 必须**在宿主创建阶段**注册，而两个入口都是「我的」页里一个普通行 ——
 * 没有独立的注册时机。回传由宿主 [MainActivity.onActivityResult] 转发到这里。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么按 requestCode 区分流程（而不是只留一个槽位）
 * ══════════════════════════════════════════════════════════════════════════
 * 系统选择器在前台时进程可能被杀；回来时 `pending` 已经没了，**但 requestCode 还在**。
 * 只有靠它才能告诉用户"刚才那次导出/保存失败了"，而不是静默什么都不做
 * （用户视角就是"点了没反应"）。
 */
internal object DocumentWriter {

    /** 导出备份（`.json`）。4011 是既有值，不能改 —— 老版本回调路径已按它分发。 */
    const val REQUEST_EXPORT = 4011

    /** 就医材料（`.txt`）。与 4011 / [ImportReader] 的 4012 都错开。 */
    const val REQUEST_MEDICAL = 4013

    /**
     * 帧率探针日志（`.log`，清单3 R4）。与 4011 / 4012 / 4013 都错开。
     * `kindOf` 映射到 [Kind.EXPORT]：复用宿主导出提示语，
     * [com.healix.app.ui.MainActivity.onActivityResult] 分发零改动。
     */
    const val REQUEST_PROBE = 4014

    const val MIME_JSON = "application/json"
    const val MIME_TEXT = "text/plain"

    /** 回传归属：决定宿主弹哪一条提示。 */
    enum class Kind { EXPORT, MEDICAL }

    /**
     * requestCode → 待写内容。
     * 用 `ConcurrentHashMap` 是因为写入发生在 IO 线程（`openOutputStream().write`），
     * 而 `remove` 由主线程的回调发起 —— 虽然此刻实际上不会并发，但把一个
     * "靠时序侥幸成立"的假设写进共享可变状态，是下次改动的陷阱。
     */
    private val pending = ConcurrentHashMap<Int, String>()

    /** 打开系统选择器；`payload` 暂存到回传时再写。 */
    fun launch(
        activity: Activity,
        requestCode: Int,
        mime: String,
        fileName: String,
        payload: String,
    ) {
        pending[requestCode] = payload
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        activity.startActivityForResult(intent, requestCode)
    }

    /**
     * 宿主回传入口。
     *
     * @return 本次回传属于哪个流程；**`null` = 不是本管道发起的**（调用方继续问下一位
     *         处理者）。取消 / 写失败同样返回 kind —— 调用方要据此提示失败，
     *         不能静默（"点了没反应"是比报错更糟的反馈）。
     */
    fun onActivityResult(
        context: Context,
        requestCode: Int,
        resultCode: Int,
        data: Uri?,
    ): Kind? {
        val kind = kindOf(requestCode) ?: return null
        val payload = pending.remove(requestCode)
        if (payload == null || resultCode != Activity.RESULT_OK || data == null) return kind
        try {
            context.contentResolver.openOutputStream(data)?.use { out: OutputStream ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (_: Exception) {
            // 吞掉：写失败由调用方的提示语呈现，不在这里弹（本类没有 UI 依赖）
        }
        return kind
    }

    private fun kindOf(requestCode: Int): Kind? = when (requestCode) {
        REQUEST_EXPORT -> Kind.EXPORT
        REQUEST_MEDICAL -> Kind.MEDICAL
        REQUEST_PROBE -> Kind.EXPORT // 探针日志复用导出提示（清单3 R4）
        else -> null
    }
}
