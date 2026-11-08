package io.github.blockman3063.vhsync.hook

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.github.blockman3063.vhsync.core.Ids
import io.github.blockman3063.vhsync.core.PayloadCodec
import io.github.blockman3063.vhsync.core.SyncRecord

/**
 * vivo 健康库 -> SyncRecord -> 主进程。
 *
 * 「读什么」交给 QueryPlan，这里只负责取数、按 Binder 事务大小分批、递送。
 * M3 推步数与心率，M4 追加距离、卡路里、睡眠、血氧、体重/身高、运动。
 * 行级转换在 M4Mappers。
 *
 * [SleepBuilder] 仍是按 OPPO 表（DBSleepPiece）写的旧实现，**不要启用**：
 * vivo 的睡眠在 WATCH_SLEEP_BEAN 的四个分段列表里，由 M4Mappers 处理。
 *
 * 主进程可能不在（本机不会因为数据接口被访问就去启动一个没在跑的应用），
 * 所以每次推送前先发唤醒广播、等数据接口可用再发。
 */
object Pusher {

    private const val TAG = "VHSyncPush"
    private const val VHSYNC_PACKAGE = Ids.APP_ID
    private const val PROVIDER_SUFFIX = Ids.PROVIDER_SUFFIX

    /** 唤醒广播，与主进程侧 WakeReceiver 约定一致。 */
    private const val ACTION_WAKE = Ids.ACTION_WAKE

    /** 等主进程就绪的最长时间。 */
    private const val WAIT_READY_MS = 12_000L

    /**
     * 单次 Binder 事务的 payload 上限。
     * 心率一条 record 最多 200 个样本（约 20 KB），按条数分批会随时炸掉
     * 事务上限，所以按**实际编码后的字节数**切批。留足余量给 token/type 等字段。
     */
    private const val MAX_PAYLOAD_BYTES = 400_000

    fun push(reader: TableReader, incremental: Boolean = false) {
        val plan = QueryPlan.forTables(reader, incremental)
        if (!plan.ok) {
            Log.e(TAG, "源库结构对不上，本次推送整体放弃：${plan.note}")
            return
        }
        if (plan.specs.isEmpty()) {
            Log.w(TAG, "没有可同步的表，本次推送放弃")
            return
        }
        if (!ensureHostReady()) {
            Log.e(TAG, "主进程未就绪，本次推送放弃（下轮会重试）")
            return
        }

        var total = 0
        for (spec in plan.specs) {
            val rows = reader.query(spec.sql)
            val records = rows.flatMap(spec.toRecords)
            if (records.isEmpty()) {
                Log.i(TAG, "${spec.type.label}：0 条")
                continue
            }
            var sent = 0
            var batchNo = 0
            for (chunk in batches(records)) {
                batchNo++
                if (!send(spec.type.id, chunk)) {
                    Log.e(TAG, "${spec.type.label} 第 $batchNo 批推送失败，停止该类型（剩余不重发）")
                    break
                }
                sent += chunk.size
            }
            total += sent
            Log.i(TAG, "${spec.type.label}：$sent / ${records.size} 条")
        }
        Log.i(TAG, "推送完成，共 $total 条${if (incremental) "（增量）" else "（验证窗口）"}")
        RemoteConfig.reportSyncDone(System.currentTimeMillis())
    }

    /** 按编码后的字节数分批，避免 ContentValues 超过 Binder 事务上限。 */
    private fun batches(records: List<SyncRecord>): List<List<SyncRecord>> {
        val out = ArrayList<List<SyncRecord>>()
        var cur = ArrayList<SyncRecord>()
        var size = 2 // JSON 数组的 "[]"
        for (r in records) {
            val one = PayloadCodec.encode(listOf(r)).length + 1
            if (one > MAX_PAYLOAD_BYTES) {
                Log.w(TAG, "单条记录超过事务上限，跳过（不打印内容）")
                continue
            }
            if (cur.isNotEmpty() && size + one > MAX_PAYLOAD_BYTES) {
                out += cur
                cur = ArrayList()
                size = 2
            }
            cur += r
            size += one
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /**
     * 确保主进程的数据接口可用。
     *
     * 先发唤醒广播（事件驱动，正常情况主进程收到后立刻启动服务），再轮询试探接口。
     * 唤醒后系统还需要一点时间把进程和服务拉起来，所以留了等待窗口。
     */
    private fun ensureHostReady(): Boolean {
        if (AppContextHolder.context == null) AppContextHolder.init()
        val ctx = AppContextHolder.context ?: return false
        if (RemoteConfig.fetch(force = true) != null) return true

        val token = TokenHolder.token
        if (token.isEmpty()) return false

        Log.i(TAG, "主进程不在，发唤醒广播")
        runCatching {
            ctx.sendBroadcast(
                Intent(ACTION_WAKE).setPackage(VHSYNC_PACKAGE).putExtra("token", token)
            )
        }.onFailure { Log.w(TAG, "发唤醒广播失败", it) }

        val deadline = System.currentTimeMillis() + WAIT_READY_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(700L)
            } catch (_: InterruptedException) {
                return false
            }
            if (RemoteConfig.fetch(force = true) != null) {
                Log.i(TAG, "主进程已就绪")
                return true
            }
        }
        return false
    }

    private fun send(type: String, records: List<SyncRecord>): Boolean {
        if (RemoteConfig.fetch() == null || TokenHolder.token.isEmpty()) return false
        val ctx = AppContextHolder.context ?: return false
        return try {
            val payload = PayloadCodec.encode(records)
            val values = ContentValues().apply {
                put("token", TokenHolder.token)
                put("type", type)
                put("payload", payload)
            }
            val uri = ctx.contentResolver.insert(
                Uri.parse("content://$VHSYNC_PACKAGE$PROVIDER_SUFFIX/records"),
                values,
            )
            if (uri == null) {
                // provider 通道被挡（HMA 隐藏等），改用显式组件绑定
                val ok = Bridge.insert(ctx, TokenHolder.token, type, payload)
                if (!ok) Log.e(TAG, "推送 $type 失败（provider 与 Bridge 都不通，${records.size} 条，${payload.length} 字节）")
                ok
            } else {
                Log.i(TAG, "发送 $type：${records.size} 条，${payload.length} 字节")
                true
            }
        } catch (t: Throwable) {
            val ok = try {
                Bridge.insert(ctx, TokenHolder.token, type, PayloadCodec.encode(records))
            } catch (e: Throwable) {
                false
            }
            if (!ok) Log.e(TAG, "推送 $type 失败", t)
            ok
        }
    }
}
