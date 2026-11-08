package io.github.blockman3063.vhsync.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier

/**
 * 源库写入信号。
 *
 * 只做一件事：vivo 健康往它自己的库里写东西时，把一个「有人写过」的信号传出去，
 * 让自动同步线程立刻醒来看一眼指纹。**不改任何行为、不读不改参数、不碰数据**。
 *
 * 为什么不直接靠定时轮询：用户要的是「有数据变化就同步」。轮询要么慢（等一整轮），
 * 要么费电（高频查库）。挂一层写信号能把延迟压到一次去抖窗口（约 10 秒），
 * 平时则完全不查库。
 *
 * 挂的是 SQLCipher 的写入口，因为 vivo 健康用 GreenDAO：单条 insert 走
 * `SQLiteStatement.executeInsert()`，update 走 `execute()`，批量/建表走 `execSQL()`。
 * 三类都挂上，覆盖它的全部写路径。
 *
 * 全部 PROTECTIVE + try/catch：hook 出问题绝不能影响 vivo 健康正常存数据。
 */
internal object WriteWatcher {

    private const val TAG = "VHSyncWrite"

    private const val SQLCIPHER_DB = "net.sqlcipher.database.SQLiteDatabase"
    private const val SQLCIPHER_STMT = "net.sqlcipher.database.SQLiteStatement"

    /** 回调节流窗口：写入再密也至少隔这么久才叫醒一次同步线程。 */
    private const val NOTIFY_GAP_MS = 500L

    /** 数据库层写方法。 */
    private val DB_WRITE_METHODS = setOf(
        "insert", "insertOrThrow", "insertWithOnConflict",
        "replace", "replaceOrThrow",
        "update", "updateWithOnConflict", "delete", "execSQL",
    )

    /** 预编译语句层写方法（GreenDAO 实际走的就是这几个）。 */
    private val STMT_WRITE_METHODS = setOf(
        "execute", "executeInsert", "executeUpdateDelete",
        "executeForChangedRowCount", "executeForLastInsertedRowId",
    )

    @Volatile private var installed = false

    @Volatile private var lastWriteAt = 0L

    @Volatile private var lastNotifyAt = 0L

    /** 有人写入时回调。实现必须极轻（只做一次 notify），否则会拖慢宿主写库。 */
    @Volatile var onWrite: (() -> Unit)? = null

    fun lastWriteAt(): Long = lastWriteAt

    /**
     * 记一次写入。
     *
     * 时间戳每次都更新（去抖要靠它判断「最后一笔写入是什么时候」），
     * 但回调做了节流：批量写心率时 `executeInsert` 可能连调上千次，
     * 每次都去抢锁 notify 纯属浪费，还会跟宿主写库抢 DB 线程的 CPU。
     */
    private fun note() {
        val now = System.currentTimeMillis()
        lastWriteAt = now
        if (now - lastNotifyAt < NOTIFY_GAP_MS) return
        lastNotifyAt = now
        try {
            onWrite?.invoke()
        } catch (_: Throwable) {
            // 回调自己出问题不能影响写入
        }
    }

    /** 阶段一（App 创建前）安装。幂等。 */
    fun install(xposed: XposedInterface, classLoader: ClassLoader) {
        if (installed) return
        installed = true

        var count = 0
        count += hookClass(xposed, classLoader, SQLCIPHER_DB, DB_WRITE_METHODS, "db")
        count += hookClass(xposed, classLoader, SQLCIPHER_STMT, STMT_WRITE_METHODS, "stmt")

        if (count == 0) {
            Log.w(TAG, "没挂上任何写入口，自动同步会退化为定时轮询")
        } else {
            Log.i(TAG, "写信号已挂接 $count 个方法")
        }
    }

    private fun hookClass(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        className: String,
        names: Set<String>,
        tag: String,
    ): Int {
        val c = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
        if (c == null) {
            Log.i(TAG, "类不存在：$className")
            return 0
        }
        // 只挂实例方法：静态的 openOrCreateDatabase 之类跟写入无关
        val targets = c.declaredMethods.filter {
            !Modifier.isStatic(it.modifiers) && it.name in names
        }
        for (m in targets) {
            runCatching { m.isAccessible = true }
            xposed.observeHook(m, "write.$tag.${m.name}") { note() }
        }
        Log.i(TAG, "$className 挂接 ${targets.size} 个写方法：${targets.map { it.name }.distinct()}")
        return targets.size
    }
}
