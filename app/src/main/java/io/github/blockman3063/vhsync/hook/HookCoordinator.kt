package io.github.blockman3063.vhsync.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * Hook 侧总编排。
 *
 * 两个阶段，对应 LSPosed 的两个回调：
 *   onPackageLoaded —— App 创建**之前**。只装 hook，不碰数据库（此时还没有 Context）。
 *   onPackageReady  —— App 创建**之后**。拿 Context、兜底取库、出报告、推送。
 *
 * 之所以分两段：OPPO 健康在 Application 初始化时就打开数据库，若等 onPackageReady
 * 再挂钩，首次开库已经过去了，拿不到实例。
 */
class HookCoordinator(private val xposed: XposedInterface) {

    private val reader = TableReader()
    private val dbAccess = DbAccess(xposed)

    @Volatile private var reported = false
    @Volatile private var pushedOnce = false
    private var lastPushAt = 0L

    /** 阶段一：App 创建之前。 */
    fun onPackageLoaded(cl: ClassLoader) {
        // 先绑好回调再挂钩：hook 有可能在同一瞬间就回传数据库，
        // 若等到 onPackageReady 才绑定，首个句柄会被丢掉。
        bindDatabaseCallback()
        runCatching { dbAccess.installEarlyHooks(cl) }
            .onFailure { Log.e(TAG, "提前挂钩失败", it) }
    }

    /** 阶段二：App 创建之后。 */
    fun onPackageReady(cl: ClassLoader) {
        bindDatabaseCallback()

        // 此时 Application 往往还没挂上（实测 currentApplication() 返回 null），
        // 必须重试，否则整个模块永远拿不到 Context。
        retryContext()

        // 兜底：路 A 错过首次开库时，反复重试反射读 sActiveDatabases
        dbAccess.startFallbackLoop(cl)
    }

    /** 绑定取库回调。幂等，两个阶段都调用。 */
    private fun bindDatabaseCallback() {
        if (wired) return
        wired = true
        dbAccess.onDatabaseReady = { db ->
            reader.attach(db)
            reportTables()
            // M1 阶段：只验证「能拿到库 + 能读出表名」，不推送任何数据。
            // 映射未定稿前写 HC 只会产生脏数据，M3 再打开推送。
            Log.i(TAG, "M1：取库成功，暂不推送")
        }
    }

    @Volatile private var wired = false

    /** onPackageReady 时 Application 还没创建，隔一会儿再取，直到拿到为止。 */
    private fun retryContext() {
        if (AppContextHolder.context != null) {
            startEventChannel()
            return
        }
        Thread {
            var tries = 0
            while (AppContextHolder.context == null && tries < CONTEXT_MAX_TRIES) {
                tries++
                Thread.sleep(CONTEXT_RETRY_MS)
                AppContextHolder.init()
            }
            Log.i(TAG, "Context 在第 $tries 次重试后${if (AppContextHolder.context != null) "取得" else "仍失败"}")
            if (AppContextHolder.context != null) startEventChannel()
        }.apply { isDaemon = true }.start()
    }

    /**
     * 注册主进程 → 读取端的事件通道。
     *
     * 只有主进程 `com.vivo.health` 需要：vivo 健康有 4 个进程都会被注入，
     * 让子进程也回话只会收到一堆重复结果。
     */
    private fun startEventChannel() {
        if (channelStarted) return
        channelStarted = true

        val ctx = AppContextHolder.context ?: run {
            Log.w(TAG, "没有 Context，事件通道未启动")
            return
        }
        val pn = runCatching { android.app.Application.getProcessName() }.getOrNull()
        if (pn != null && pn != MAIN_PROCESS) {
            Log.i(TAG, "非主进程（$pn），不回话")
            return
        }

        // 先取一次配置：顺带完成口令配对，否则广播里的口令校验过不了
        if (RemoteConfig.fetch(force = true) == null) {
            Log.w(TAG, "首次取配置失败，口令可能没配上，事件会被丢弃")
        }

        SyncTrigger.register(
            ctx,
            onSyncNow = { Log.i(TAG, "收到同步请求：M2 阶段只读不写，暂不推送") },
            onConfigChanged = { Log.i(TAG, "收到配置变更") },
            onPing = { RemoteConfig.ping() },
            onPreview = { onPreviewRequested() },
        )
        Log.i(TAG, "事件通道已启动（进程=$pn）")
    }

    @Volatile private var channelStarted = false

    /** M2：读一份原始数据概况送回主进程。全程只读，不碰 Health Connect。 */
    private fun onPreviewRequested() {
        Thread {
            runCatching {
                if (!reader.isReady()) {
                    Log.w(TAG, "数据库未就绪，预览跳过")
                } else {
                    val data = PreviewReader.collect(reader)
                    val ok = RemoteConfig.pushPreview(data)
                    Log.i(TAG, "预览回传${if (ok) "成功" else "失败"}")
                }
            }.onFailure { Log.e(TAG, "预览失败", it) }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 真实表结构报告：表名取自 sqlite_master，是权威来源。
     * 认不出语义的表照实列出来（进未识别清单），不猜。
     */
    private fun reportTables() {
        if (reported) return
        reported = true
        val tables = reader.tableNames()
        Log.i(TAG, "数据库共 ${tables.size} 张表")
        var known = 0
        for (t in tables) {
            val type = TableRules.resolve(t.lowercase()) ?: continue
            known++
            val mark = if (type.hcRecord == null) "（无 HC 对应，废弃）" else ""
            Log.i(TAG, "  [表] $t -> ${type.label}$mark")
        }
        val unknown = tables.filter { TableRules.resolve(it.lowercase()) == null }
        Log.i(TAG, "可识别 $known / 共 ${tables.size}")
        Log.i(TAG, "未识别：$unknown")
    }

    /**
     * 同步调度：事件驱动 + 本地计时，不做轮询。
     *
     *   - 用户点「立即同步」/ 改设置 → 主进程发广播 → 立即唤醒
     *   - 自动同步 → 本地按用户设的间隔计时
     *
     * 平时只在本地 wait，不产生任何跨进程调用。这一点很重要：早期版本每 5 秒
     * 查一次配置，等于把 VHSync 主进程永久钉在内存里（实测常驻 PSS 约 55 MB）。
     *
     * 另留一个低频兜底刷新（[CONFIG_REFRESH_MS]），防止广播被省电策略丢弃。
     */
    private fun startSyncScheduler() {
        if (schedulerStarted) return
        schedulerStarted = true

        val ctx = AppContextHolder.context
        if (ctx == null) {
            Log.w(TAG, "没有 Context，事件通道未注册，只能靠本地计时")
        } else {
            SyncTrigger.register(
                ctx,
                onSyncNow = { manualRequest = true; configDirty = true; wake() },
                onConfigChanged = { configDirty = true; wake() },
                onPing = { RemoteConfig.ping() },
                onPreview = { onPreviewRequested() },
            )
        }

        Thread {
            var lastPush = 0L
            var cfg: HookConfig? = null
            var cfgAt = 0L
            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    val stale = cfg == null || now - cfgAt >= CONFIG_REFRESH_MS
                    if (stale || configDirty) {
                        RemoteConfig.fetch(force = true)?.let {
                            cfg = it
                            cfgAt = now
                            configDirty = false
                        }
                    }

                    val manual = manualRequest
                    val minutes = cfg?.intervalMinutes ?: 0
                    val due = minutes > 0 && now - lastPush >= minutes * 60_000L

                    if (manual || due) {
                        if (reader.isReady()) {
                            pushNow(withProbe = manual, incremental = !manual)
                            lastPush = System.currentTimeMillis()
                            manualRequest = false
                        } else {
                            Log.w(TAG, "数据库未就绪，稍后再试")
                        }
                    }
                    waitForEvent(TICK_MS)
                } catch (t: Throwable) {
                    Log.e(TAG, "同步调度异常", t)
                    waitForEvent(60_000L)
                }
            }
        }.apply { isDaemon = true }.start()
        Log.i(TAG, "同步调度已启动（事件驱动，本地计时）")
    }

    private var schedulerStarted = false

    /** 用户在应用里点了「立即同步」。 */
    @Volatile private var manualRequest = false

    /** 配置有变化，下一轮重新取。 */
    @Volatile private var configDirty = false

    private fun wake() = synchronized(wakeLock) { wakeLock.notifyAll() }

    private fun waitForEvent(ms: Long) {
        synchronized(wakeLock) {
            try {
                wakeLock.wait(ms)
            } catch (_: InterruptedException) {
            }
        }
    }

    private val wakeLock = Object()

    /** UI 点「同步历史数据」时也会走这里。 */
    /**
     * @param withProbe   顺带跑一次证据探针（只在手动同步时，探针输出很啰嗦）
     * @param incremental 只取上次同步之后的新数据；手动同步传 false 走完整窗口
     */
    private fun pushNow(withProbe: Boolean, incremental: Boolean = false) {
        if (!reader.isReady()) {
            Log.w(TAG, "数据库未就绪，跳过推送")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastPushAt < 1500) return
        lastPushAt = now
        Thread {
            runCatching {
                if (withProbe) Probe.run(reader)
                Pusher.push(reader, incremental)
            }.onFailure { Log.e(TAG, "推送失败", it) }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "VHSyncHook"

        /** 本地计时粒度。只 sleep，不做任何 IPC。 */
        private const val TICK_MS = 30_000L

        /** 兜底刷新配置的间隔（正常情况下由事件驱动）。 */
        private const val CONFIG_REFRESH_MS = 3_600_000L

        /** 取 Context 的重试节奏：2 秒一次，最多 30 秒。 */
        private const val CONTEXT_RETRY_MS = 2_000L
        private const val CONTEXT_MAX_TRIES = 15

        /** 只有这个进程负责回话，其余子进程不参与。 */
        private const val MAIN_PROCESS = "com.vivo.health"
    }
}
