package io.github.blockman3063.vhsync.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.util.Log

/**
 * 后台保活辅助。
 *
 * M5 之后同步是「vivo 健康一写库就推」的自动流程，用户不会再去打开本应用，
 * 所以主进程必须能一直在：前台服务负责进程不被回收，**电池优化白名单**负责
 * 前台服务不被系统顺手清掉。两者缺一个，自动同步就会时灵时不灵。
 *
 * 只做一件事：没拿到豁免时弹一次系统对话框。拿不到也不影响已开启的同步，
 * 只是系统可能更积极地把服务杀掉（那时读取端会重发唤醒广播把它拉回来）。
 */
object KeepAlive {

    private const val TAG = "VHSyncKeepAlive"

    /** 是否已在电池优化白名单里。低版本没有这个机制，直接当作已豁免。 */
    fun isExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(true)
    }

    /**
     * 没豁免就去要一次。必须在 Activity 里调（要弹系统对话框）。
     *
     * 用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 而不是跳设置页：
     * 前者一句话就能确认，后者要用户自己在列表里翻。
     */
    fun requestExemptionIfNeeded(activity: Activity) {
        if (isExempt(activity)) return
        val intent = Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        runCatching { activity.startActivity(intent) }
            .onFailure {
                Log.w(TAG, "请求电池优化豁免失败，退回应用详情页", it)
                runCatching {
                    activity.startActivity(
                        Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                    )
                }
            }
    }
}
