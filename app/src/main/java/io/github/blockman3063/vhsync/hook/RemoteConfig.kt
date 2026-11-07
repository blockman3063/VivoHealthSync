package io.github.blockman3063.vhsync.hook

import android.net.Uri
import android.util.Log
import io.github.blockman3063.vhsync.core.Ids

/** 主进程下发的配置：口令、同步间隔、时间窗口。 */
data class HookConfig(
    val token: String,
    val intervalMinutes: Int,
    val windowDays: Int,
    val backfillRequested: Boolean,
    val lastSyncAt: Long,
)

/**
 * 从主进程取配置。
 *
 * Hook 侧跑在 OPPO 健康进程里，没有自己的界面，用户的设置只能这样取。
 * 取不到（主进程没开、provider 被拦）时返回 null，调用方按保守默认值走。
 */
object RemoteConfig {

    private const val TAG = "VHSyncCfg"
    // 包名与动作名集中定义在 core/Ids.kt，避免改包名时漏改
    private const val VHSYNC_PACKAGE = Ids.APP_ID
    private const val PROVIDER_SUFFIX = Ids.PROVIDER_SUFFIX

    @Volatile private var cached: HookConfig? = null
    @Volatile private var lastFetchAt = 0L

    /** 缓存 5 分钟，避免每轮都跨进程取。 */
    fun fetch(force: Boolean = false): HookConfig? {
        val now = System.currentTimeMillis()
        if (!force) {
            cached?.takeIf { now - lastFetchAt < 300_000L }?.let { return it }
        }
        if (AppContextHolder.context == null) AppContextHolder.init()
        val ctx = AppContextHolder.context ?: return if (force) null else cached
        val got = try {
            val b = ctx.contentResolver.call(
                Uri.parse("content://$VHSYNC_PACKAGE$PROVIDER_SUFFIX"),
                "config", null, null,
            )
            parse(b)
        } catch (t: Throwable) {
            // provider 可能被 HMA 之类藏掉（Unknown authority）：退回显式组件绑定
            val alt = try {
                Bridge.call(ctx, "config", null)
            } catch (e: Throwable) {
                null
            }
            if (alt == null) {
                Log.w(TAG, "取配置失败（provider 与 Bridge 都不通）", t)
                diagnose(ctx)
                null
            } else {
                parse(alt)
            }
        }
        if (got != null) {
            cached = got
            lastFetchAt = now
            TokenHolder.pair(got.token)
        }
        // force=true 表示调用方要的是「此刻主进程在不在」这个事实，
        // 此时**不能**回退到旧缓存 —— 否则进程已经死了也会被判为就绪，
        // 结果就是推送全部失败（踩过）。
        return if (force) got else got ?: cached
    }

    private fun parse(b: android.os.Bundle?): HookConfig? {
        val token = b?.getString("token")
        if (token.isNullOrEmpty()) return null
        return HookConfig(
            token = token,
            intervalMinutes = b.getInt("intervalMinutes", 60),
            windowDays = b.getInt("windowDays", 90),
            backfillRequested = b.getBoolean("backfillRequested", false),
            lastSyncAt = b.getLong("lastSyncAt", 0L),
        )
    }

    @Volatile private var diagnosed = false

    /** 临时诊断：从宿主进程视角看 VHSync 是否可见、provider 能否解析。 */
    private fun diagnose(ctx: android.content.Context) {
        if (diagnosed) return
        diagnosed = true
        try {
            val pm = ctx.packageManager
            val vis = try {
                pm.getPackageInfo(VHSYNC_PACKAGE, 0)
                "可见"
            } catch (e: Throwable) {
                "不可见(${e.javaClass.simpleName})"
            }
            val cp = try {
                val info = pm.resolveContentProvider("$VHSYNC_PACKAGE$PROVIDER_SUFFIX", 0)
                if (info == null) "null" else "有(${info.processName})"
            } catch (e: Throwable) {
                "异常(${e.javaClass.simpleName})"
            }
            Log.w(TAG, "诊断：包=$vis，provider=$cp，uid=${android.os.Process.myUid()}，进程=${android.os.Process.myProcessName()}")
        } catch (_: Throwable) {
        }
    }

    /** 告诉主进程本次推送完成的时间点，下次增量只取这之后的数据。 */
    fun reportSyncDone(at: Long) {
        if (AppContextHolder.context == null) AppContextHolder.init()
        val ctx = AppContextHolder.context ?: return
        runCatching {
            ctx.contentResolver.call(
                Uri.parse("content://$VHSYNC_PACKAGE$PROVIDER_SUFFIX"),
                "syncDone", null,
                android.os.Bundle().apply { putLong("at", at) },
            )
        }
    }

    /**
     * 回应应用界面的在线确认。
     *
     * 应用打开时发一次广播问「读取端在吗」，这里回一个 provider 调用。
     * 事件驱动，只在用户看界面时发生一次，不是轮询。
     */
    fun ping() {
        if (AppContextHolder.context == null) AppContextHolder.init()
        val ctx = AppContextHolder.context ?: return
        runCatching {
            ctx.contentResolver.call(
                Uri.parse("content://$VHSYNC_PACKAGE$PROVIDER_SUFFIX"),
                "ping", TokenHolder.token, null,
            )
        }
    }

    /**
     * 把一份原始数据概况送回主进程（M2 调试用）。
     *
     * 走 provider；provider 被 HMA 之类挡掉时退回显式组件绑定。
     */
    fun pushPreview(data: android.os.Bundle): Boolean {
        if (TokenHolder.token.isBlank()) {
            // 没配对过说明还没成功取到过配置，先取一次
            fetch(force = true)
        }
        val token = TokenHolder.token
        if (token.isEmpty()) {
            Log.w(TAG, "没有口令，无法回传预览")
            return false
        }
        if (AppContextHolder.context == null) AppContextHolder.init()
        val ctx = AppContextHolder.context ?: return false

        val viaProvider = runCatching {
            ctx.contentResolver.call(
                Uri.parse("content://$VHSYNC_PACKAGE$PROVIDER_SUFFIX"),
                "preview", token, data,
            )?.getBoolean("ok") == true
        }.getOrElse {
            Log.w(TAG, "provider 回传预览失败，改用 Bridge", it)
            false
        }
        if (viaProvider) return true

        val viaBridge = runCatching {
            Bridge.call(ctx, "preview", token, data)?.getBoolean("ok") == true
        }.getOrElse {
            Log.w(TAG, "Bridge 回传预览也失败", it)
            false
        }
        return viaBridge
    }
}
