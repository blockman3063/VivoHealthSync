package io.github.blockman3063.vhsync.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.blockman3063.vhsync.core.RecordType

/**
 * Hook 侧总编排。
 *
 * 两个阶段，对应 LSPosed 的两个回调：
 *   onPackageLoaded —— App 创建**之前**。装 hook（取库、写信号），不碰数据库。
 *   onPackageReady  —— App 创建**之后**。拿 Context、兜底取库、起事件通道与自动同步。
 *
 * 之所以分两段：vivo 健康在 Application 初始化时就打开数据库，若等 onPackageReady
 * 再挂钩，首次开库已经过去了，拿不到实例。
 *
 * ## M5 的同步模型
 *
 * 用户要求：vivo 健康在跑的时候，不用打开 VHSync，数据一变就同步出去。
 *
 * 做法是「写信号 + 指纹比对」两层：
 *   1. [WriteWatcher] 挂在 SQLCipher 的写入口上，vivo 健康一写库就把循环叫醒；
 *   2. 醒来不立刻推，先等一个去抖窗口（[QUIET_MS]），让一阵连续写入落定；
 *   3. 然后给 8 张源表各算一个指纹（[ChangeDetector]），只有指纹变了的类型才查库、
 *      才推送 —— 心率变了就只推心率，不把 9 类几百条全走一遍；
 *   4. 兜底：即使一次写信号都没收到，每 [FULL_CHECK_MS] 也会比一次指纹。
 *
 * 推送失败不丢状态：保留「待处理」标记，[RETRY_MS] 后重试，成功后指纹才推进。
 */
class HookCoordinator(private val xposed: XposedInterface) {

    private val reader = TableReader()
    private val dbAccess = DbAccess(xposed)

    @Volatile private var reported = false
    private var lastPushAt = 0L

    /** 阶段一：App 创建之前。 */
    fun onPackageLoaded(cl: ClassLoader) {
        // 先绑好回调再挂钩：hook 有可能在同一瞬间就回传数据库，
        // 若等到 onPackageReady 才绑定，首个句柄会被丢掉。
        bindDatabaseCallback()
        runCatching { dbAccess.installEarlyHooks(cl) }
            .onFailure { Log.e(TAG, "提前挂钩失败", it) }
        // 写信号也在这里装：要赶在 vivo 第一次写库之前
        runCatching { WriteWatcher.install(xposed, cl) }
            .onFailure { Log.e(TAG, "写信号挂钩失败", it) }
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
            Log.i(TAG, "取库成功，自动同步线程会跟进数据变化")
        }
    }

    @Volatile private var wired = false

    /** onPackageReady 时 Application 还没创建，隔一会儿再取，直到拿到为止。 */
    private fun retryContext() {
        if (AppContextHolder.context != null) {
            if (isMainProcess()) {
                startEventChannel()
                startAutoSync()
            }
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
            if (AppContextHolder.context != null && isMainProcess()) {
                startEventChannel()
                startAutoSync()
            }
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

        // 先取一次配置：顺带完成口令配对，否则广播里的口令校验过不了
        if (RemoteConfig.fetch(force = true) == null) {
            Log.w(TAG, "首次取配置失败，口令可能没配上，事件会被丢弃")
        }

        SyncTrigger.register(
            ctx,
            // 用户点「立即同步」→ 全量重读一次（不做指纹过滤），并跑一遍证据探针
            onSyncNow = { manualRequest = true; wake() },
            onConfigChanged = { Log.i(TAG, "收到配置变更") },
            onPing = { RemoteConfig.ping() },
            onPreview = { onPreviewRequested() },
        )
        Log.i(TAG, "事件通道已启动")
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
     * 表结构报告：只报我们真正会读的那 8 张表在不在。
     *
     * 早期版本把 154 张表整个打出来、认不出的还列一遍 —— 那是 OPPO 时代
     * `TableRules` 的遗留，对 vivo 的表名一个都认不出，除了刷屏没别的用
     * （一两千字符的日志也是把自己刷进 logd 限流的原因之一）。删掉。
     */
    private fun reportTables() {
        if (reported) return
        reported = true
        val tables = reader.tableNames()
        val byUpper = tables.associateBy { it.uppercase() }
        val missing = ArrayList<String>()
        var found = 0
        for (name in ChangeDetector.watchedTables()) {
            if (byUpper.containsKey(name)) found++ else missing += name
        }
        Log.i(TAG, "源库共 ${tables.size} 张表；同步用到的 $found/${ChangeDetector.watchedTables().size} 张可用")
        if (missing.isNotEmpty()) Log.w(TAG, "缺少的表：$missing")
    }

    // ---------------------------------------------------------------- 自动同步

    /** 用户手动点了「立即同步」，下一轮走全量。 */
    @Volatile private var manualRequest = false

    private fun wake() = synchronized(wakeLock) { wakeLock.notifyAll() }

    private fun waitForSignal(ms: Long) {
        synchronized(wakeLock) {
            try {
                wakeLock.wait(ms)
            } catch (_: InterruptedException) {
            }
        }
    }

    private val wakeLock = Object()

    @Volatile private var autoStarted = false

    /**
     * 自动同步主循环。
     *
     * 只在一个线程里跑，状态都在本地变量上，没有共享可变状态需要同步。
     */
    private fun startAutoSync() {
        if (autoStarted) return
        autoStarted = true

        // 写库 → 立刻叫醒循环（回调里只做一次 notify，不能干别的）
        WriteWatcher.onWrite = { wake() }

        // 尽早恢复口令：主进程不在时，唤醒广播全指望它
        TokenHolder.load()

        Thread {
            // 上次成功同步后的指纹。null = 还没有基线，下一轮做一次全量对齐。
            var baseline: Map<RecordType, String>? = null
            /** 第一次检测到「有待处理变化」的时刻；0 表示当前没有待处理变化。 */
            var dirtyAt = 0L
            /** 已见过的最近一次写信号时间（用于去抖：等最后一次写入之后 10 秒）。 */
            var writeSeen = 0L
            /** 失败后的最早重试时刻。 */
            var retryAt = 0L
            /** 兜底指纹检查的下一次时刻。 */
            var nextFullCheck = 0L
            /** 「库还没就绪」日志的节流时间戳。 */
            var lastNotReadyLog = 0L
            /** 「有写入但指纹没变」日志的节流时间戳。 */
            var lastNoChangeLog = 0L

            Log.i(TAG, "自动同步已启动（写信号驱动 + 定时兜底）")

            while (true) {
                try {
                    val now = System.currentTimeMillis()

                    // 1. 有新写入？只记下来，先不推 —— 等这一阵写完
                    val w = WriteWatcher.lastWriteAt()
                    if (w > writeSeen) {
                        writeSeen = w
                        if (dirtyAt == 0L) dirtyAt = now
                    }

                    val due = now >= nextFullCheck
                    val quiet = dirtyAt > 0 && now - writeSeen >= QUIET_MS
                    val starved = dirtyAt > 0 && now - dirtyAt >= MAX_WAIT_MS

                    // 2. 没事可做：睡到下一次兜底检查，期间有写信号会被叫醒
                    if (!manualRequest && !quiet && !starved && !due) {
                        val sleep = if (dirtyAt > 0) {
                            // 在去抖窗口里，睡到窗口结束
                            (writeSeen + QUIET_MS - now).coerceAtLeast(POLL_MS)
                        } else {
                            (nextFullCheck - now).coerceIn(POLL_MS, MAX_IDLE_WAIT_MS)
                        }
                        waitForSignal(sleep)
                        continue
                    }
                    if (now < retryAt) {
                        waitForSignal((retryAt - now).coerceIn(POLL_MS, RETRY_MS))
                        continue
                    }
                    if (!reader.isReady()) {
                        // 库还没开起来（vivo 健康要等首页加载数据才开库），稍后再看。
                        // 半分钟报一次，避免刷屏又能在日志里看出「到底卡在哪」。
                        if (now - lastNotReadyLog >= 30_000L) {
                            lastNotReadyLog = now
                            Log.w(TAG, "数据库实例尚未取得，自动同步继续等待")
                        }
                        waitForSignal(POLL_MS)
                        continue
                    }

                    // 3. 算指纹，决定推什么
                    val manual = manualRequest
                    val snapshot = runCatching { ChangeDetector.snapshot(reader) }.getOrNull()
                    if (snapshot == null || snapshot.isEmpty()) {
                        Log.w(TAG, "指纹计算失败或没有可识别的表，稍后重试")
                        retryAt = now + RETRY_MS
                        waitForSignal(POLL_MS)
                        continue
                    }

                    val types = if (manual) snapshot.keys.toSet()
                    else ChangeDetector.changed(baseline, snapshot)

                    if (types.isEmpty()) {
                        // 有写信号、但 8 张同步表的指纹都没变（vivo 写的是别的表）。
                        // 这条日志用来区分「根本没写入」和「写入了但没有我们要的数据」——
                        // 排查时这两者看起来是同一回事。5 分钟最多报一次。
                        if (dirtyAt > 0 && now - lastNoChangeLog >= 5 * 60_000L) {
                            lastNoChangeLog = now
                            Log.i(TAG, "源库有写入，但同步用的 8 张表都没变，跳过推送")
                        }
                        dirtyAt = 0L
                        nextFullCheck = now + fullCheckInterval()
                        waitForSignal(POLL_MS)
                        continue
                    }

                    Log.i(TAG, "准备推送 ${types.size} 类：${types.joinToString { it.label }}" +
                        if (manual) "（手动，全量）" else "（按变化）")

                    val ok = runCatching { Pusher.push(reader, types) }
                        .onFailure { Log.e(TAG, "推送异常", it) }
                        .getOrDefault(false)

                    if (ok) {
                        baseline = snapshot
                        dirtyAt = 0L
                        manualRequest = false
                        nextFullCheck = System.currentTimeMillis() + fullCheckInterval()
                    } else {
                        // 关键：不推进基线、不清 dirty，下轮重来
                        Log.w(TAG, "推送未完成，${RETRY_MS / 1000} 秒后重试")
                        retryAt = System.currentTimeMillis() + RETRY_MS
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "自动同步循环异常", t)
                    waitForSignal(60_000L)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 兜底指纹检查的间隔，取自用户设置；0 表示关闭兜底（只靠写信号）。 */
    private fun fullCheckInterval(): Long {
        val minutes = runCatching { RemoteConfig.fetch()?.intervalMinutes }.getOrNull()
            ?: DEFAULT_INTERVAL_MINUTES
        return if (minutes <= 0) Long.MAX_VALUE / 4 else minutes * 60_000L
    }

    // ---------------------------------------------------------------- 其它

    /**
     * 判断自己是不是 vivo 健康的主进程。
     *
     * 不能只靠 `Application.getProcessName()`：它在 Application 还没挂上时可能返回
     * null，那样就会被误判成主进程，导致设备进程/常驻进程也去推送 —— 同一批数据
     * 被写四五遍，还会把 Health Connect 的 clientRecordVersion 打乱。
     * 所以拿不到就退回读 `/proc/self/cmdline`，两条路都失败才按主进程处理。
     */
    private fun resolveProcessName(): String? {
        val fromApi = runCatching { android.app.Application.getProcessName() }.getOrNull()
        if (!fromApi.isNullOrEmpty()) return fromApi
        return runCatching {
            java.io.File("/proc/self/cmdline").readText().trim().trimEnd('\u0000')
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun isMainProcess(): Boolean {
        val pn = resolveProcessName()
        if (pn == null) {
            Log.w(TAG, "进程名取不到，按主进程处理")
            return true
        }
        if (pn != MAIN_PROCESS) {
            Log.i(TAG, "非主进程（$pn），不回话也不自动同步")
            return false
        }
        return true
    }

    companion object {
        private const val TAG = "VHSyncHook"

        /** 写信号后的去抖窗口：等这期间没有新写入才推。 */
        private const val QUIET_MS = 10_000L

        /** 写入一直不停时的最长等待，防止饿死。 */
        private const val MAX_WAIT_MS = 120_000L

        /** 推送失败后的重试间隔。 */
        private const val RETRY_MS = 30_000L

        /** 无信号时的等待粒度。 */
        private const val POLL_MS = 1_000L

        /** 空闲时单次最长睡眠，保证配置变更能被及时看到。 */
        private const val MAX_IDLE_WAIT_MS = 30 * 60_000L

        /** 取不到配置时的兜底检查间隔（与 Settings.DEFAULT_INTERVAL 一致）。 */
        private const val DEFAULT_INTERVAL_MINUTES = 60

        /** 取 Context 的重试节奏：2 秒一次，最多 30 秒。 */
        private const val CONTEXT_RETRY_MS = 2_000L
        private const val CONTEXT_MAX_TRIES = 15

        /** 只有这个进程负责回话，其余子进程不参与。 */
        private const val MAIN_PROCESS = "com.vivo.health"
    }
}
