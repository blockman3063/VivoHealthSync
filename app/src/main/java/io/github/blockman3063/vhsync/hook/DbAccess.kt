package io.github.blockman3063.vhsync.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 拿到 vivo 健康的数据库实例（只读使用）。
 *
 * vivo 健康 7.1.3.13 用的是 net.sqlcipher.database.SQLiteDatabase（GreenDAO + SQLCipher）。
 * 探库实测该类的静态字段 sActiveDatabases（WeakHashMap）里常驻着所有已打开的库实例，
 * 所以「取库」比 OPPO（需要 hook 开库方法 + 反射单例 + 主动触发初始化三路兜底）简单得多：
 *
 *   路 A（主）：反射读 sActiveDatabases，按 mPath 过滤 cc_health_*，直接拿到现成句柄。
 *   路 B（兜底）：hook vivo 自己的封装类 SqlcipherSingleDbHelper.getWritableDatabase()，
 *                在它每次取库时捕获返回值。
 *   路 C（再兜底）：hook SQLCipher 的 openDatabase / openOrCreateDatabase 返回值。
 *
 * 三条路都只观察、不改行为，全部包 try/catch。
 *
 * 注意：不做跨 ClassLoader 的类型判断。模块自己也可能带一份 sqlite 相关类，
 * 与宿主里的同名类不是同一个 Class，instanceof / 强转永远不成立。
 * 这里全程用 Any + 反射，拿到对象后按行为（getPath / rawQuery 是否可用）验证。
 */
class DbAccess(private val xposed: XposedInterface) {

    /** 拿到可用数据库时回调，只会回调一次。 */
    var onDatabaseReady: ((Any) -> Unit)? = null

    @Volatile private var delivered = false

    /** 阶段一：App 创建之前装 hook，赶在 vivo 自己开库之前。 */
    fun installEarlyHooks(classLoader: ClassLoader) {
        var installed = 0

        // 路 B：vivo 的封装 helper（探库时在进程里看到的就是这个类）
        for (name in HELPER_CLASSES) {
            val c = runCatching { Class.forName(name, false, classLoader) }.getOrNull()
            if (c == null) {
                Log.i(TAG, "候选类不存在：$name")
                continue
            }
            val getters = c.declaredMethods.filter { it.name in DB_GETTERS && it.parameterCount == 0 }
            Log.i(TAG, "$name 上找到 ${getters.size} 个取库方法：${getters.map { it.name }}")
            for (m in getters) {
                runCatching { m.isAccessible = true }
                xposed.captureReturn(m, "db.${c.simpleName}.${m.name}") { ret ->
                    if (ret != null) deliver(ret, "hook ${c.simpleName}.${m.name}")
                }
                installed++
            }
        }

        // 路 C：SQLCipher 开库方法（重载多，按方法名 + 返回类型匹配，不按参数类型）
        runCatching {
            val c = Class.forName(SQLCIPHER_DB, false, classLoader)
            val opens = c.declaredMethods.filter {
                Modifier.isStatic(it.modifiers) &&
                    (it.name == "openDatabase" || it.name == "openOrCreateDatabase") &&
                    c.isAssignableFrom(it.returnType)
            }
            Log.i(TAG, "SQLCipher 开库方法 ${opens.size} 个")
            for (m in opens) {
                runCatching { m.isAccessible = true }
                xposed.captureReturn(m, "sqlcipher.${m.name}") { ret ->
                    if (ret != null) deliver(ret, "hook SQLCipher.${m.name}")
                }
                installed++
            }
        }.onFailure { Log.i(TAG, "SQLCipher 类不可用：${it.message}") }

        Log.i(TAG, "阶段一完成，挂钩 $installed 处")
    }

    /**
     * 路 A：反复重试反射读 sActiveDatabases。
     *
     * 库可能晚于 Application 创建才打开（要等首页加载数据），单次尝试没有意义。
     * 拿到第一个 cc_health_* 就停。
     */
    fun startFallbackLoop(classLoader: ClassLoader) {
        if (fallbackStarted) return
        fallbackStarted = true
        Thread {
            var tries = 0
            while (!delivered && tries < MAX_TRIES) {
                tries++
                try {
                    if (tryActiveDatabases(classLoader)) return@Thread
                } catch (t: Throwable) {
                    Log.e(TAG, "路 A 第 $tries 次失败", t)
                }
                if (!delivered) Thread.sleep(RETRY_MS)
            }
            if (!delivered) Log.w(TAG, "路 A 重试 $MAX_TRIES 次仍未拿到库实例")
        }.apply { isDaemon = true }.start()
    }

    private var fallbackStarted = false

    /** 读 sActiveDatabases 里的所有实例，按路径挑出健康主库。 */
    private fun tryActiveDatabases(classLoader: ClassLoader): Boolean {
        val c = runCatching { Class.forName(SQLCIPHER_DB, false, classLoader) }.getOrNull() ?: return false
        val f = runCatching { c.getDeclaredField("sActiveDatabases").apply { isAccessible = true } }
            .getOrNull() ?: return false
        val map = runCatching { f.get(null) as? Map<*, *> }.getOrNull() ?: return false
        val keys = runCatching { map.keys.toList() }.getOrNull() ?: return false

        if (keys.isNotEmpty() && !loggedOnce) {
            loggedOnce = true
            val paths = keys.mapNotNull { raw -> raw?.let { pathOf(it) } }
            Log.i(TAG, "活跃库 ${keys.size} 个：$paths")
        }
        for (raw in keys) {
            val db = raw ?: continue
            val path = pathOf(db) ?: continue
            if (!path.contains(DB_NAME_KEYWORD)) continue
            deliver(db, "sActiveDatabases($path)")
            return true
        }
        return false
    }

    /** 反射读 mPath 字段（SQLCipher 的 getPath() 在跨 ClassLoader 场景下不一定可调）。 */
    private fun pathOf(db: Any): String? {
        var k: Class<*>? = db.javaClass
        while (k != null) {
            val fd = runCatching { k.getDeclaredField("mPath").apply { isAccessible = true } }.getOrNull()
            if (fd != null) return runCatching { fd.get(db) as? String }.getOrNull()
            k = k.superclass
        }
        return null
    }

    private fun deliver(database: Any, how: String) {
        if (delivered) return
        // 只认健康主库/设备库，避免把埋点库当成数据源
        val path = pathOf(database)
        if (path != null && !path.contains(DB_NAME_KEYWORD)) return
        delivered = true
        Log.i(TAG, "已取得数据库实例（$how）：${database.javaClass.name}")
        onDatabaseReady?.invoke(database)
    }

    @Volatile private var loggedOnce = false

    companion object {
        private const val SQLCIPHER_DB = "net.sqlcipher.database.SQLiteDatabase"

        /** vivo 自己的 SQLCipher 单库封装（探库实测存在）。 */
        private val HELPER_CLASSES = listOf(
            "com.vivo.vcodeimpl.db.sqlcipher.single.SqlcipherSingleDbHelper",
        )

        /** 库文件名关键字：cc_health_<hash>.db / cc_health_device_<hash>.db */
        private const val DB_NAME_KEYWORD = "cc_health"

        private val DB_GETTERS = setOf("getReadableDatabase", "getWritableDatabase")
        private const val RETRY_MS = 3000L
        private const val MAX_TRIES = 200
    }
}
