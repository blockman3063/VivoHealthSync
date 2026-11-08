package io.github.blockman3063.vhsync.hook

import android.util.Log
import io.github.blockman3063.vhsync.core.RecordMapper
import io.github.blockman3063.vhsync.core.RecordType
import io.github.blockman3063.vhsync.core.SyncRecord
import io.github.blockman3063.vhsync.hook.TableReader.Companion.safeIdent
import org.json.JSONArray
import java.util.TimeZone

/**
 * vivo 健康库 -> SyncRecord 的查询方案。
 *
 * 和 OHSync 一样，**不硬编码表结构**：表名取自 sqlite_master，列名取自
 * PRAGMA table_info，SQL 用真实列名拼。目标表存在但列签名对不上时直接判失败，
 * 由 [QueryPlanResult.ok] 让 Pusher 停下报告，绝不降级猜测或吞错继续跑。
 *
 * 已确认的源语义（见 docs/01 与 docs/05，全部来自本机实测）：
 *   DATE_STEP_COUNT_BEAN —— _id = 当天 00:00 绝对毫秒，VALUE = 全天步数
 *   DATE_DISTANCE_BEAN   —— 同上，VALUE = 米
 *   DATE_CALORIE_BEAN    —— 同上，VALUE = 千卡（活动消耗，不含基础代谢）
 *   WATCH_HEART_RATE     —— POINT_LIST = [{"timestamp":ms,"value":bpm}, ...]
 *   WATCH_OXYGEN         —— POINT_LIST = [{"timestamp":ms,"value":0-100}, ...]
 *   WATCH_SLEEP_BEAN     —— 四个 *_SLEEP_LIST，元素 {enterTime,exitTime} 绝对毫秒
 *   WEIGHT_BEAN          —— WEIGHT 千克 / HEIGHT 厘米
 *   SPORT_RECORD_BY_WATCH_BEAN —— TYPE 是 vivo 的运动编码，映射见 M4Mappers
 *
 * 本类只写 SQL；行级转换在 [M4Mappers]（M4）与本文件的 stepRecords/hrRecords（M3）。
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

    // ---- M4 ----
    private const val T_DIST = "DATE_DISTANCE_BEAN"
    private const val T_CAL = "DATE_CALORIE_BEAN"
    private const val T_SLEEP = "WATCH_SLEEP_BEAN"
    private const val T_SPO2 = "WATCH_OXYGEN"
    private const val T_WEIGHT = "WEIGHT_BEAN"
    private const val T_SPORT = "SPORT_RECORD_BY_WATCH_BEAN"

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

    /**
     * 睡眠回看天数。睡眠是稀疏事件（实测每晚 1~2 条），30 天约 30~60 行，
     * 全量重拼代价可忽略；一次灌入两年反而会让首次同步变慢。
     */
    private const val SLEEP_DAYS = 30L

    // 非日级表的行数上限。这些表行数本来就少（血氧 8 行、体重 1 行、运动 47 行），
    // 设上限只为防御异常膨胀，不是常规截断。
    private const val MAX_ROWS_SLEEP = 400
    private const val MAX_ROWS_SPO2 = 5000
    private const val MAX_ROWS_BODY = 50
    private const val MAX_ROWS_SPORT = 5000

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

        // ---- 距离 / 卡路里（日聚合，与步数同构） ----
        addDaily(byUpper, reader, out, problems, T_DIST, RecordType.DISTANCE,
            RecordMapper.V_DISTANCE_M, "vivo-dist", lowerBound, now)
        addDaily(byUpper, reader, out, problems, T_CAL, RecordType.ACTIVE_CALORIES,
            RecordMapper.V_CALORIES, "vivo-cal", lowerBound, now)

        // ---- 睡眠 ----
        addSleep(byUpper, reader, out, problems, now)

        // ---- 血氧 ----
        addSpo2(byUpper, reader, out, problems, now)

        // ---- 体重 + 身高（同一张表，两个 spec） ----
        addBody(byUpper, reader, out, problems, now)

        // ---- 运动 ----
        addSport(byUpper, reader, out, problems, now)

        if (problems.isNotEmpty()) {
            Log.e(TAG, "源库结构对不上，放弃推送：$problems")
        } else {
            Log.i(TAG, "M4 方案：${out.size} 类，日窗口下界=$lowerBound")
        }
        return QueryPlanResult(out, problems.isEmpty(), problems.joinToString("；"))
    }

    // ------------------------------------------------------------------ M4 分支

    /** 日聚合表（距离、活动卡路里）与步数完全同构，只是 VALUE 语义不同。 */
    private fun addDaily(
        byUpper: Map<String, String>,
        reader: TableReader,
        out: MutableList<TableSpec>,
        problems: MutableList<String>,
        table: String,
        type: RecordType,
        valueKey: String,
        sourcePrefix: String,
        lowerBound: Long,
        now: Long,
    ) {
        val actual = byUpper[table]
        if (actual == null) {
            problems += "缺少 $table"
            return
        }
        val cols = bindColumns(reader, actual, "_ID", "VALUE")
        if (cols == null) {
            problems += "$actual 列签名不符（需要 _ID / VALUE）"
            return
        }
        val t = actual.safeIdent()
        val idCol = cols.getValue("_ID")
        val valueCol = cols.getValue("VALUE")
        out += TableSpec(
            type,
            "SELECT $idCol, $valueCol FROM `$t` " +
                "WHERE CAST($idCol AS INTEGER) >= $lowerBound " +
                "AND CAST($valueCol AS REAL) > 0 " +
                "ORDER BY CAST($idCol AS INTEGER) DESC LIMIT $MAX_SOURCE_ROWS",
        ) { row -> M4Mappers.dailyRecords(row, cols, type, valueKey, sourcePrefix, lowerBound, now) }
    }

    /**
     * 睡眠：一行一次睡眠，四个分段列表拼成 stages。
     *
     * 坏行（`ENTER_TIME<=0` 或 `EXIT_TIME<=ENTER_TIME`）在 SQL 里就排除掉 ——
     * 源库实测有 2 条这样的记录，不是我们该修的数据。
     */
    private fun addSleep(
        byUpper: Map<String, String>,
        reader: TableReader,
        out: MutableList<TableSpec>,
        problems: MutableList<String>,
        now: Long,
    ) {
        val actual = byUpper[T_SLEEP]
        if (actual == null) {
            problems += "缺少 $T_SLEEP"
            return
        }
        val needed = arrayOf(
            "UUID", "ENTER_TIME", "EXIT_TIME", "ZONE_OFFSET",
            "LIGHT_SLEEP_LIST", "DEEP_SLEEP_LIST", "REM_SLEEP_LIST", "AWAKE_SLEEP_LIST",
        )
        val cols = bindColumns(reader, actual, *needed)
        if (cols == null) {
            problems += "$actual 列签名不符（需要 ${needed.joinToString("/")}）"
            return
        }
        val t = actual.safeIdent()
        val enter = cols.getValue("ENTER_TIME")
        val exit = cols.getValue("EXIT_TIME")
        val lower = now - SLEEP_DAYS * DAY_MS
        val select = needed.joinToString(", ") { cols.getValue(it) }
        out += TableSpec(
            RecordType.SLEEP_SESSION,
            "SELECT $select FROM `$t` " +
                "WHERE CAST($enter AS INTEGER) > 0 " +
                "AND CAST($exit AS INTEGER) > CAST($enter AS INTEGER) " +
                "AND CAST($enter AS INTEGER) >= $lower " +
                "ORDER BY CAST($enter AS INTEGER) DESC LIMIT $MAX_ROWS_SLEEP",
        ) { row -> M4Mappers.sleepRecords(row, cols, now) }
    }

    /** 血氧：每行 POINT_LIST 里是若干个测量点，一点一条记录。 */
    private fun addSpo2(
        byUpper: Map<String, String>,
        reader: TableReader,
        out: MutableList<TableSpec>,
        problems: MutableList<String>,
        now: Long,
    ) {
        val actual = byUpper[T_SPO2]
        if (actual == null) {
            problems += "缺少 $T_SPO2"
            return
        }
        val cols = bindColumns(reader, actual, "_ID", "POINT_LIST")
        if (cols == null) {
            problems += "$actual 列签名不符（需要 _ID / POINT_LIST）"
            return
        }
        val t = actual.safeIdent()
        out += TableSpec(
            RecordType.BLOOD_OXYGEN,
            "SELECT ${cols.getValue("_ID")}, ${cols.getValue("POINT_LIST")} FROM `$t` " +
                "ORDER BY CAST(${cols.getValue("_ID")} AS INTEGER) DESC LIMIT $MAX_ROWS_SPO2",
        ) { row -> M4Mappers.spo2Records(row, cols, now) }
    }

    /** 体重与身高同表。HC 是两条独立记录类型，所以拆成两个 spec。 */
    private fun addBody(
        byUpper: Map<String, String>,
        reader: TableReader,
        out: MutableList<TableSpec>,
        problems: MutableList<String>,
        now: Long,
    ) {
        val actual = byUpper[T_WEIGHT]
        if (actual == null) {
            problems += "缺少 $T_WEIGHT"
            return
        }
        val cols = bindColumns(reader, actual, "_ID", "WEIGHT", "HEIGHT")
        if (cols == null) {
            problems += "$actual 列签名不符（需要 _ID / WEIGHT / HEIGHT）"
            return
        }
        val t = actual.safeIdent()
        val idCol = cols.getValue("_ID")
        val order = "ORDER BY CAST($idCol AS INTEGER) DESC LIMIT $MAX_ROWS_BODY"

        out += TableSpec(
            RecordType.WEIGHT,
            "SELECT $idCol, ${cols.getValue("WEIGHT")} FROM `$t` " +
                "WHERE CAST(${cols.getValue("WEIGHT")} AS REAL) > 0 $order",
        ) { row -> M4Mappers.weightRecords(row, cols, now) }

        out += TableSpec(
            RecordType.HEIGHT,
            "SELECT $idCol, ${cols.getValue("HEIGHT")} FROM `$t` " +
                "WHERE CAST(${cols.getValue("HEIGHT")} AS REAL) > 0 $order",
        ) { row -> M4Mappers.heightRecords(row, cols, now) }
    }

    /** 运动：一行一次运动。距离/卡路里不单独写，避免与日汇总重复计入。 */
    private fun addSport(
        byUpper: Map<String, String>,
        reader: TableReader,
        out: MutableList<TableSpec>,
        problems: MutableList<String>,
        now: Long,
    ) {
        val actual = byUpper[T_SPORT]
        if (actual == null) {
            problems += "缺少 $T_SPORT"
            return
        }
        val needed = arrayOf("RTC_START_TIME", "RTC_END_TIME", "TYPE", "EID")
        val cols = bindColumns(reader, actual, *needed)
        if (cols == null) {
            problems += "$actual 列签名不符（需要 ${needed.joinToString("/")}）"
            return
        }
        val t = actual.safeIdent()
        val start = cols.getValue("RTC_START_TIME")
        val end = cols.getValue("RTC_END_TIME")
        val select = needed.joinToString(", ") { cols.getValue(it) }
        out += TableSpec(
            RecordType.EXERCISE_SESSION,
            "SELECT $select FROM `$t` " +
                "WHERE CAST($start AS INTEGER) > 0 " +
                "AND CAST($end AS INTEGER) > CAST($start AS INTEGER) " +
                "ORDER BY CAST($start AS INTEGER) DESC LIMIT $MAX_ROWS_SPORT",
        ) { row -> M4Mappers.sportRecords(row, cols, now) }
    }

    /**
     * 把逻辑列名绑定到真实列名，缺任何一列就返回 null（由调用方记为结构不符）。
     * 逻辑名与真实名大小写无关，与 [TableReader.columns] 的来源一致。
     */
    private fun bindColumns(reader: TableReader, table: String, vararg logical: String): Map<String, String>? {
        val actual = reader.columns(table)
        val out = HashMap<String, String>(logical.size * 2)
        for (name in logical) {
            val real = col(actual, name) ?: return null
            out[name] = real
        }
        return out
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
