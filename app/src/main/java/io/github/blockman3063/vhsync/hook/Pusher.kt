package io.github.blockman3063.vhsync.hook

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.github.blockman3063.vhsync.core.Ids
import io.github.blockman3063.vhsync.core.PayloadCodec
import io.github.blockman3063.vhsync.core.RecordType
import io.github.blockman3063.vhsync.core.SyncRecord

/**
 * vivo 健康库 -> SyncRecord -> 主进程。
 *
 * 「读什么」交给 QueryPlan，这里只负责取数、按 Binder 事务大小分批、递送。
 * M3 推步数与心率，M4 追加距离、卡路里、睡眠、血氧、体重/身高、运动，
 * M5 起由自动同步按「源库哪张表变了」只推对应类型。
 * 行级转换在 M4Mappers。
 *
 * [SleepBuilder] 那份按 OPPO 表（DBSleepPiece）写的旧实现已在 M5 删除：
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

    /** 唤醒广播的重发节奏：一次广播可能被省电策略丢掉，多发两次。 */
    private const val WAKE_RESEND_MS = 4_000L

    /** 等待窗口内最多发几次唤醒广播。 */
    private const val MAX_WAKE_TIMES = 3

    /**
     * 单次 Binder 事务的 payload 上限。
     * 心率一条 record 最多 200 个样本（约 20 KB），按条数分批会随时炸掉
     * 事务上限，所以按**实际编码后的字节数**切批。留足余量给 token/type 等字段。
     */
    private const val MAX_PAYLOAD_BYTES = 400_000

    /**
     * @param only 只推这些类型；null = 全量（手动同步 / 首次建立基线）。
     * @return 是否**全部**类型都推成功。有任一批失败就返回 false，
     *         调用方据此保留「待处理」状态，下轮重试（写 HC 是幂等的）。
     */
    fun push(reader: TableReader, only: Set<RecordType>? = null): Boolean {
        val plan = QueryPlan.forTables(reader, only)
        if (!plan.ok) {
            Log.e(TAG, "源库结构对不上，本次推送整体放弃：${plan.note}")
            return false
        }
        if (plan.specs.isEmpty()) {
            Log.w(TAG, "没有可同步的表，本次推送放弃")
            return true // 没东西要推，不算失败
        }
        if (!ensureHostReady()) {
            Log.e(TAG, "主进程未就绪，本次推送放弃（下轮会重试）")
            return false
        }

        var total = 0
        var allOk = true
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
                    allOk = false
                    break
                }
                sent += chunk.size
            }
            total += sent
            Log.i(TAG, "${spec.type.label}：$sent / ${records.size} 条")
        }
        Log.i(
            TAG,
            "推送${if (allOk) "完成" else "未完成"}，共 $total 条" +
                (if (only == null) "（全量）" else "（${only.size} 类）"),
        )
        // 只有真的全推成功才回报「同步完成」，否则下次增量窗口会被错误推进
        if (allOk) RemoteConfig.reportSyncDone(System.currentTimeMillis())
        return allOk
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
     * 实测单次广播可能被省电策略丢弃，所以每 [WAKE_RESEND_MS] 重发一次，
     * 一共发 3 次 —— 唤醒是自动同步唯一的「叫醒」手段，值得多发。
     */
    private fun ensureHostReady(): Boolean {
        if (AppContextHolder.context == null) AppContextHolder.init()
        val ctx = AppContextHolder.context ?: return false
        if (RemoteConfig.fetch(force = true) != null) return true

        // 主进程不在时，口令只能从宿主私有目录里恢复的那份来（见 TokenHolder）
        TokenHolder.load()
        val token = TokenHolder.token
        if (token.isEmpty()) {
            // 从来没成功取到过配置，也就没有口令，唤醒广播会被主进程拒收。
            // 这种情况只出现在「装完模块还没打开过 VHSync」，属于一次性配置。
            Log.w(TAG, "还没有配对口令：请先打开一次 VHSync 完成授权，之后就不用再打开了")
            return false
        }

        Log.i(TAG, "主进程不在，发唤醒广播")
        val deadline = System.currentTimeMillis() + WAIT_READY_MS
        var nextWakeAt = 0L
        var wakeCount = 0
        while (System.currentTimeMillis() < deadline) {
            val now = System.currentTimeMillis()
            if (now >= nextWakeAt && wakeCount < MAX_WAKE_TIMES) {
                wakeCount++
                nextWakeAt = now + WAKE_RESEND_MS
                runCatching {
                    ctx.sendBroadcast(
                        Intent(ACTION_WAKE)
                            .setPackage(VHSYNC_PACKAGE)
                            // 关键：应用刚装完/被强制停止时处于 stopped 状态，
                            // 系统默认会丢弃发给 stopped 包的广播（连收都收不到，
                            // 表现就是「发了 3 次广播，对方进程始终没起来」）。
                            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                            .putExtra("token", token)
                    )
                }.onFailure { Log.w(TAG, "发唤醒广播失败", it) }
            }
            try {
                Thread.sleep(700L)
            } catch (_: InterruptedException) {
                return false
            }
            if (RemoteConfig.fetch(force = true) != null) {
                Log.i(TAG, "主进程已就绪（第 $wakeCount 次唤醒后）")
                return true
            }
        }
        Log.w(TAG, "唤醒 $wakeCount 次仍未就绪")
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
