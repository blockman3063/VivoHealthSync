package io.github.blockman3063.vhsync.hook

import android.content.Context
import android.util.Log

/**
 * 跨进程配对口令。
 *
 * 主进程生成，Hook 侧通过配置接口取到。
 *
 * M5 之前只留在内存里（怕往目标进程私有目录塞东西）。但 M5 的自动同步要求
 * 「用户不打开 VHSync 也能同步」，一旦主进程被杀，内存里的口令就没了，
 * 而唤醒广播需要口令 —— 于是「没口令 → 不能唤醒 → 拿不到口令」形成死锁，
 * 自动同步彻底停摆（实测确认过）。
 *
 * 所以改为持久化：用**宿主进程自己的 Context**写一份 SharedPreferences。
 * 存的是一个 32 字节随机串，不含任何健康数据；文件落在 com.vivo.health 的
 * 私有目录里，只有它自己和 root 能读。这是解除死锁最小的一步改动。
 */
object TokenHolder {

    private const val TAG = "VHSyncToken"
    private const val PREFS = "vhsync_hook"
    private const val KEY_TOKEN = "token"

    @Volatile
    var token: String = ""
        private set

    @Volatile private var loaded = false

    /** 进程刚起来时从宿主私有目录恢复一次。幂等。 */
    fun load() {
        if (loaded || token.isNotBlank()) return
        loaded = true
        if (AppContextHolder.context == null) AppContextHolder.init()
        val ctx = AppContextHolder.context ?: run {
            loaded = false // 还没 Context，下次再试
            return
        }
        val saved = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)
        }.getOrNull()
        if (!saved.isNullOrBlank()) {
            token = saved
            Log.i(TAG, "已从宿主私有目录恢复口令，长度 ${token.length}")
        }
    }

    fun pair(newToken: String) {
        if (newToken.isBlank()) return
        val t = newToken.trim()
        if (t == token) return
        token = t
        loaded = true
        Log.i(TAG, "口令已配对，长度 ${token.length}")
        val ctx = AppContextHolder.context ?: return
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_TOKEN, token).apply()
        }.onFailure { Log.w(TAG, "口令落盘失败，进程重启后需要重新配对", it) }
    }

    fun isPaired(): Boolean = token.isNotBlank()
}
