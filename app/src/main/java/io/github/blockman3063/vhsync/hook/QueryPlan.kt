package io.github.blockman3063.vhsync.hook

import android.util.Log
import io.github.blockman3063.vhsync.core.RecordMapper
import io.github.blockman3063.vhsync.core.RecordType
import io.github.blockman3063.vhsync.core.SyncRecord
import io.github.blockman3063.vhsync.hook.TableReader.Companion.safeIdent
import org.json.JSONArray
import java.util.TimeZone

/**
 * M3：vivo 健康库 -> SyncRecord 的查询方案。
 *
 * 和 OHSync 一样，**不硬编码表结构**：表名取自 sqlite_master，列名取自
 * PRAGMA table_info，SQL 用真实列名拼。目标表存在但列签名对不上时直接判失败，
 * 由 [QueryPlanResult.ok] 让 Pusher 停下报告，绝不降级猜测或吞错继续跑。
 *
 * 已确认的源语义（见 docs/01-映射设计草案.md，来自本机实测）：
 *   DATE_STEP_COUNT_BEAN —— _id = 当天 00:00 绝对毫秒，VALUE = 全天步数
 *   WATCH_HEART_RATE     —— _id = 当天 00:00 绝对毫秒，
 *                           POINT_LIST = [{"timestamp":ms,"value":bpm}, ...]
 *
 * 全程只发 SELECT。日志只写条数与范围，不打印任何健康数据正文。
 */
internal class TableSpec(
    val type: RecordType,
    val sql: String,
    /** 一行源数据可以产出 0..N 条记录：心率一天的点要切成很多段。 */
    val toRecords: (Map<String, String?>) -> List<SyncRecord>,
)

/** 查询方案的结果。ok=false 表示源库结构变了，调用方必须放弃本次推送。 */
internal data class QueryPlanResult(
    val specs: List<TableSpec>,
    val ok: Boolean,
    val note: String,
)

internal object QueryPlan {

    private const val TAG = "VHSyncPlan"

    private const val T_STEPS = "DATE_STEP_COUNT_BEAN"
    private const val T_HR = "WATCH_HEART_RATE"

    private const val DAY_MS = 86_400_000L
    /** 绝对 10 分钟桶：以 epoch 为基准，不是「从首点起算 600 秒」，重解析时边界稳定。 */
    private const val BUCKET_MS = 600_000L
    /** 同桶内相邻点间隔超过 5 分钟就切开，不把断档并进同一条 record。 */
    private const val GAP_MS = 300_000L
    /** 单段样本硬上限，防止某天采样异常密集导致一条 record 过大。 */
    private const val MAX_SAMPLES = 200

    /** M3 只验证最近 2 天（含今天）；全窗口回填是 M5 的事。 */
    private const val VERIFY_DAYS = 2

    /** 一次最多读几行日级源数据，防止 POINT_LIST 大文本一次性灌进内存。 */
    private const val MAX_SOURCE_ROWS = 5

    private data class HrPoint(val ts: Long, val bpm: Double)

    /**
     * @param incremental M3 阶段一律取最近 2 天窗口，此参数暂不生效（M5 接增量时启用）。
     */
    @Suppress("UNUSED_PARAMETER")
    fun forTables(reader: TableReader, incremental: Boolean = false): QueryPlanResult {
        val byUpper = reader.tableNames().associateBy { it.uppercase() }
        val out = ArrayList<TableSpec>()
        val problems = ArrayList<String>()

        val now = System.currentTimeMillis()
        // 下界按「本地当天 00:00」对齐：源表 _id 就是当天 00:00 的绝对毫秒。
        // 直接用 now - N*86400000 会落在当天中间，边界上的那行可能被漏掉。
        val lowerBound = localDayStart(now) - (VERIFY_DAYS - 1) * DAY_MS

        // ---- 步数 ----
        val stepsTable = byUpper[T_STEPS]
        if (stepsTable == null) {
            problems += "缺少 $T_STEPS"
        } else {
            val cols = reader.columns(stepsTable)
            val idCol = col(cols, "_ID")
            val valueCol = col(cols, "VALUE")
            if (idCol == null || valueCol == null) {
                problems += "$stepsTable 列签名不符（需要 _ID / VALUE）"
            } else {
                val t = stepsTable.safeIdent()
                out += TableSpec(
                    RecordType.STEPS,
                    // 显式 CAST：探库产物里这两列不保证是 INTEGER 类型。
                    // SQLite 中 TEXT 与数值比较时 TEXT 恒大于数值，不 CAST 会让
                    // 时间窗口和 VALUE>0 两个条件同时失效，多写窗口外的记录。
                    "SELECT $idCol, $valueCol FROM `$t` " +
                        "WHERE CAST($idCol AS INTEGER) >= $lowerBound " +
                        "AND CAST($valueCol AS REAL) > 0 " +
                        "ORDER BY CAST($idCol AS INTEGER) DESC LIMIT $MAX_SOURCE_ROWS",
                ) { row -> stepRecords(row, idCol, valueCol, now, lowerBound) }
            }
        }

        // ---- 心率 ----
        val hrTable = byUpper[T_HR]
        if (hrTable == null) {
            problems += "缺少 $T_HR"
        } else {
            val cols = reader.columns(hrTable)
            val idCol = col(cols, "_ID")
            val pointCol = col(cols, "POINT_LIST")
            if (idCol == null || pointCol == null) {
                problems += "$hrTable 列签名不符（需要 _ID / POINT_LIST）"
            } else {
                val t = hrTable.safeIdent()
                out += TableSpec(
                    RecordType.HEART_RATE,
                    "SELECT $idCol, $pointCol FROM `$t` " +
                        "WHERE CAST($idCol AS INTEGER) >= $lowerBound " +
                        "ORDER BY CAST($idCol AS INTEGER) DESC LIMIT $MAX_SOURCE_ROWS",
                ) { row -> hrRecords(row, pointCol, lowerBound) }
            }
        }

        if (problems.isNotEmpty()) {
            Log.e(TAG, "源库结构对不上，放弃推送：$problems")
        } else {
            Log.i(TAG, "M3 方案：${out.size} 类，窗口下界=$lowerBound")
        }
        return QueryPlanResult(out, problems.isEmpty(), problems.joinToString("；"))
    }

    /** 步数：一天一条 StepsRecord。当天没过完时 endTime 取现在，HC 拒收未来时间。 */
    private fun stepRecords(
        row: Map<String, String?>,
        idCol: String,
        valueCol: String,
        now: Long,
        lowerBound: Long,
    ): List<SyncRecord> {
        val day = row[idCol]?.toLongOrNull() ?: return emptyList()
        val count = row[valueCol]?.toDoubleOrNull()?.toLong() ?: return emptyList()
        // SQL 已按 CAST 过滤，这里再兜一道：列类型若与预期不符也不写出窗口外的记录
        if (day < lowerBound || day <= 0L || count <= 0L) return emptyList()

        val end = minOf(day + DAY_MS, now)
        if (end <= day) return emptyList() // 刚跨天且还没数据，HC 不接受 start == end

        return listOf(
            SyncRecord(
                type = RecordType.STEPS.id,
                sourceKey = "vivo-step-$day",
                startTime = day,
                endTime = end,
                values = mapOf(RecordMapper.V_COUNT to count.toDouble()),
            )
        )
    }

    /** 心率：把一天的数千个点切成若干段，每段一条 HeartRateRecord。 */
    private fun hrRecords(
        row: Map<String, String?>,
        pointCol: String,
        lowerBound: Long,
    ): List<SyncRecord> {
        val raw = row[pointCol] ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrElse {
            Log.w(TAG, "POINT_LIST 不是合法 JSON，跳过该行（不猜测格式）")
            return emptyList()
        }

        val pts = ArrayList<HrPoint>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ts = o.optLong("timestamp", 0L)
            val bpm = o.optDouble("value", 0.0)
            // 越界读数直接丢弃，不往 HC 里写脏数据；也不打印具体数值
            if (ts < lowerBound || bpm <= 0.0 || bpm > 250.0) continue
            pts += HrPoint(ts, bpm)
        }
        if (pts.isEmpty()) return emptyList()

        pts.sortBy { it.ts }
        // 同一毫秒的重复点会让 HC 拒绝该条记录，先去重
        val unique = ArrayList<HrPoint>(pts.size)
        for (p in pts) {
            if (unique.isEmpty() || p.ts != unique.last().ts) unique += p
        }

        return split(unique).map { seg ->
            val first = seg.first().ts
            val last = seg.last().ts
            // 单点段也要保证 end > start；样本时刻仍落在 [start, end] 内
            val end = if (last > first) last else first + 1L

            val values = HashMap<String, Double>(seg.size * 2 + 1)
            seg.forEachIndexed { i, p ->
                values["sample_${i}_bpm"] = p.bpm
                values["sample_${i}_at"] = p.ts.toDouble()
            }
            // 兜底值：RecordMapper 有样本时不会用，但保持字段齐全
            values[RecordMapper.V_BPM] = seg.first().bpm

            SyncRecord(
                type = RecordType.HEART_RATE.id,
                // 段内首点时间戳：切段后仍唯一，重复解析结果完全稳定
                sourceKey = "vivo-hr-$first",
                startTime = first,
                endTime = end,
                values = values,
            )
        }
    }

    /** 绝对 10 分钟分桶 + 断档切段。 */
    private fun split(pts: List<HrPoint>): List<List<HrPoint>> {
        val out = ArrayList<MutableList<HrPoint>>()
        var cur = ArrayList<HrPoint>()
        var curBucket = Long.MIN_VALUE
        var prevTs = 0L
        for (p in pts) {
            val bucket = p.ts / BUCKET_MS * BUCKET_MS
            val needNew = cur.isEmpty() || bucket != curBucket ||
                p.ts - prevTs > GAP_MS || cur.size >= MAX_SAMPLES
            if (needNew) {
                if (cur.isNotEmpty()) out += cur
                cur = ArrayList()
                curBucket = bucket
            }
            cur.add(p)
            prevTs = p.ts
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** 取真实列名（大小写不敏感），并过滤成安全标识符。 */
    private fun col(cols: Set<String>, wanted: String): String? =
        cols.firstOrNull { it.equals(wanted, ignoreCase = true) }?.safeIdent()

    /** 本地当天 00:00 对应的绝对毫秒。源库按 GMT+8 分天，这里跟随系统时区。 */
    private fun localDayStart(ts: Long): Long {
        val off = TimeZone.getDefault().getOffset(ts).toLong()
        return (ts + off) / DAY_MS * DAY_MS - off
    }
}
