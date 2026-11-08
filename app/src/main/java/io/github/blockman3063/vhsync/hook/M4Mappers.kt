package io.github.blockman3063.vhsync.hook

import android.util.Log
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import io.github.blockman3063.vhsync.core.RecordMapper
import io.github.blockman3063.vhsync.core.RecordType
import io.github.blockman3063.vhsync.core.SleepStage
import io.github.blockman3063.vhsync.core.SyncRecord
import org.json.JSONArray
import java.util.TimeZone

/**
 * M4：距离 / 卡路里 / 睡眠 / 血氧 / 体重 / 运动的行级转换。
 *
 * 与 [QueryPlan] 分工：这里不写 SQL，只把「已经查出来的一行」变成记录。
 * 每一项的单位与语义都来自 docs/05-M4-映射表.md 里的实测结论，
 * 没有实测依据的字段宁可不写。
 *
 * 全程只读源库，日志只写条数与跳过原因，不打印任何健康数据正文。
 */
internal object M4Mappers {

    private const val TAG = "VHSyncM4"

    private const val DAY_MS = 86_400_000L

    /** 睡眠阶段最短时长。短于此的分段丢弃：HC 对零长/负长阶段会直接拒收整条记录。 */
    private const val MIN_STAGE_MS = 60_000L

    /** 一次睡眠会话的合理上界，防止源库里有脏的时间戳把记录撑成几天。 */
    private const val MAX_SESSION_MS = 36 * 3600_000L

    private const val MAX_ABS_BPM = 250.0

    /** 一行里按逻辑名取真实列名对应的值。列名大小写在源库里并不统一。 */
    private class Row(private val row: Map<String, String?>, private val cols: Map<String, String>) {
        private fun real(logical: String): String? =
            cols[logical] ?: cols[logical.uppercase()] ?: cols[logical.lowercase()]

        fun str(logical: String): String? = real(logical)?.let { row[it] }
        fun long(logical: String): Long? = str(logical)?.toLongOrNull()
        fun double(logical: String): Double? = str(logical)?.toDoubleOrNull()
    }

    /** 该时刻的本地时区偏移（秒）。HC 用它把绝对时间折算成当地的「哪一天」。 */
    private fun localOffsetSeconds(ts: Long): Int =
        TimeZone.getDefault().getOffset(ts) / 1000

    // ---------------------------------------------------------------- 距离 / 卡路里

    /**
     * 日聚合类（距离、活动卡路里）：一行一天，产出 0..1 条。
     *
     * 与步数同构：`_id` 是本地 00:00 的绝对毫秒，`VALUE` 是当天累计。
     * 当天没过完时 `endTime` 取现在 —— Health Connect 拒收结束时间在未来的记录。
     */
    fun dailyRecords(
        row: Map<String, String?>,
        cols: Map<String, String>,
        type: RecordType,
        valueKey: String,
        sourcePrefix: String,
        lowerBound: Long,
        now: Long,
    ): List<SyncRecord> {
        val r = Row(row, cols)
        val day = r.long("_id") ?: return emptyList()
        val value = r.double("VALUE") ?: return emptyList()
        if (day <= 0L || day < lowerBound) return emptyList()
        if (value <= 0.0) return emptyList()

        val end = minOf(day + DAY_MS, now)
        if (end <= day) return emptyList()

        return listOf(
            SyncRecord(
                type = type.id,
                sourceKey = "$sourcePrefix-$day",
                startTime = day,
                endTime = end,
                values = mapOf(valueKey to value),
                zoneOffsetSeconds = localOffsetSeconds(day),
            )
        )
    }

    // ---------------------------------------------------------------- 睡眠

    /**
     * 一次睡眠（`WATCH_SLEEP_BEAN` 一行）-> 一条带 stages 的 SleepSessionRecord。
     *
     * 关键实测结论（见 docs/05 §4）：
     *   - `*_SLEEP_TIME` 存的是**分段条数**，不是时长，不能当分钟用；
     *   - 四个 `*_SLEEP_LIST` 是 JSON 数组 `[{customerId, enterTime, exitTime}]`，绝对毫秒；
     *   - 四类分段对整段睡眠是**无缝无重叠完整切分**（三晚实测 100% 覆盖、0 重叠、0 间隙）。
     *
     * 即便如此仍做一遍裁剪：HC 对 stages 有硬校验（有序、不重叠、落在会话内），
     * 一条不合规的记录会让整批 insert 抛异常，不值得赌数据永远干净。
     */
    fun sleepRecords(
        row: Map<String, String?>,
        cols: Map<String, String>,
        now: Long,
    ): List<SyncRecord> {
        val r = Row(row, cols)
        val start = r.long("ENTER_TIME") ?: return emptyList()
        val end = r.long("EXIT_TIME") ?: return emptyList()

        // 源库里有 2 条坏行（ENTER_TIME<=0 或 EXIT<=ENTER），直接跳过不写
        if (start <= 0L || end <= start) return emptyList()
        val span = end - start
        if (span > MAX_SESSION_MS) return emptyList()
        if (start > now) return emptyList()

        val zoneSeconds = (r.long("ZONE_OFFSET") ?: 0L).toInt() * 60

        val stages = ArrayList<SleepStage>()
        stages += parseStages(r.str("LIGHT_SLEEP_LIST"), SleepSessionRecord.STAGE_TYPE_LIGHT)
        stages += parseStages(r.str("DEEP_SLEEP_LIST"), SleepSessionRecord.STAGE_TYPE_DEEP)
        stages += parseStages(r.str("REM_SLEEP_LIST"), SleepSessionRecord.STAGE_TYPE_REM)
        stages += parseStages(r.str("AWAKE_SLEEP_LIST"), SleepSessionRecord.STAGE_TYPE_AWAKE)
        if (stages.isEmpty()) return emptyList()

        val clean = normalize(stages, start, end)
        if (clean.isEmpty()) return emptyList()

        return listOf(
            SyncRecord(
                type = RecordType.SLEEP_SESSION.id,
                // 用入睡时刻作为 sourceKey：设备侧 UUID 在换表/重置后会重建，
                // 而「同一晚的入睡时刻」在业务上稳定。实测两者一一对应。
                sourceKey = "vivo-sleep-$start",
                startTime = start,
                endTime = end,
                zoneOffsetSeconds = zoneSeconds,
                stages = clean,
            )
        )
    }

    /** 一个阶段的 JSON 列表 -> SleepStage 列表。格式不认识就返回空，不猜测。 */
    private fun parseStages(raw: String?, stage: Int): List<SleepStage> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrElse {
            Log.w(TAG, "睡眠分段不是合法 JSON，跳过该列表（不猜测格式）")
            return emptyList()
        }
        val out = ArrayList<SleepStage>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = o.optLong("enterTime", 0L)
            val e = o.optLong("exitTime", 0L)
            if (s <= 0L || e <= s) continue
            out += SleepStage(s, e, stage)
        }
        return out
    }

    /**
     * 裁剪到会话区间并消除重叠，保证满足 Health Connect 的 stages 校验：
     * 按时间升序、互不重叠、完全落在 [sessionStart, sessionEnd] 内。
     */
    private fun normalize(stages: List<SleepStage>, sessionStart: Long, sessionEnd: Long): List<SleepStage> {
        val sorted = stages.sortedBy { it.start }
        val out = ArrayList<SleepStage>(sorted.size)
        var cursor = sessionStart
        for (g in sorted) {
            val s = maxOf(g.start, cursor, sessionStart)
            val e = minOf(g.end, sessionEnd)
            if (e - s < MIN_STAGE_MS) continue
            out += SleepStage(s, e, g.stage)
            cursor = e
        }
        return out
    }

    // ---------------------------------------------------------------- 血氧

    /**
     * `WATCH_OXYGEN` 的一行 -> 0..N 条 OxygenSaturationRecord。
     *
     * 实测：全表 8 行，每行 `POINT_LIST` 里只有 1 个点，字段是
     * `{timestamp, value(0-100 整数), highAltitude}`。所以实际是「每次测量一条」，
     * 不是心率那种「一天一列点」。
     */
    fun spo2Records(
        row: Map<String, String?>,
        cols: Map<String, String>,
        now: Long,
    ): List<SyncRecord> {
        val r = Row(row, cols)
        val raw = r.str("POINT_LIST") ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrElse {
            Log.w(TAG, "血氧 POINT_LIST 不是合法 JSON，跳过该行")
            return emptyList()
        }

        val out = ArrayList<SyncRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ts = o.optLong("timestamp", 0L)
            val v = o.optDouble("value", 0.0)
            // 0 与 >100 都不是有效血氧读数；hc 也会拒收
            if (ts <= 0L || ts > now || v <= 0.0 || v > 100.0) continue
            out += SyncRecord(
                type = RecordType.BLOOD_OXYGEN.id,
                sourceKey = "vivo-spo2-$ts",
                startTime = ts,
                endTime = ts,
                values = mapOf(RecordMapper.V_PERCENT to v),
                zoneOffsetSeconds = localOffsetSeconds(ts),
            )
        }
        return out
    }

    // ---------------------------------------------------------------- 体重 / 身高

    /** `WEIGHT_BEAN` 一行 -> 一条 WeightRecord。体脂实测为 0（未测），不写体脂。 */
    fun weightRecords(row: Map<String, String?>, cols: Map<String, String>, now: Long): List<SyncRecord> {
        val r = Row(row, cols)
        val ts = r.long("_id") ?: return emptyList()
        val kg = r.double("WEIGHT") ?: return emptyList()
        if (ts <= 0L || ts > now) return emptyList()
        // 20~400kg 之外的数值几乎肯定是脏数据或单位错乱，不往 HC 写
        if (kg < 20.0 || kg > 400.0) {
            Log.w(TAG, "体重数值超出合理区间，跳过（不打印数值）")
            return emptyList()
        }
        return listOf(
            SyncRecord(
                type = RecordType.WEIGHT.id,
                sourceKey = "vivo-weight-$ts",
                startTime = ts,
                endTime = ts,
                values = mapOf(RecordMapper.V_MASS_KG to kg),
                zoneOffsetSeconds = localOffsetSeconds(ts),
            )
        )
    }

    /** `WEIGHT_BEAN.HEIGHT`（厘米）-> 一条 HeightRecord（米）。缺值或离谱就跳过。 */
    fun heightRecords(row: Map<String, String?>, cols: Map<String, String>, now: Long): List<SyncRecord> {
        val r = Row(row, cols)
        val ts = r.long("_id") ?: return emptyList()
        val cm = r.double("HEIGHT") ?: return emptyList()
        if (ts <= 0L || ts > now) return emptyList()
        if (cm < 50.0 || cm > 250.0) return emptyList()
        return listOf(
            SyncRecord(
                type = RecordType.HEIGHT.id,
                sourceKey = "vivo-height-$ts",
                startTime = ts,
                endTime = ts,
                values = mapOf(RecordMapper.V_HEIGHT_M to cm / 100.0),
                zoneOffsetSeconds = localOffsetSeconds(ts),
            )
        )
    }

    // ---------------------------------------------------------------- 运动

    /** 运动类型：vivo 的 `TYPE` -> HC exerciseType + 展示名。 */
    private data class SportKind(val hcType: Int, val title: String)

    /**
     * 映射表**逐条对账得出**，不是猜的：把 App 运动记录列表 47 条全量采集，
     * 用「显示名 + 时间」与库里 `TYPE + RTC_START_TIME` 按分钟对齐，47/47 命中，
     * 同一 TYPE 反复出现时名称始终一致，零冲突（见 docs/05 §7.1）。
     *
     * 右边的常量取自 connect-client:1.1.0-api.jar，不是凭文档记忆。
     */
    private val SPORT_TYPES: Map<Int, SportKind> = mapOf(
        2 to SportKind(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, "户外跑步"),
        4 to SportKind(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, "户外骑行"),
        5 to SportKind(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, "泳池游泳"),
        // 用户 2026-11-08 拍板：自由训练按力量训练写（手表上主要用于器械/力量）
        10 to SportKind(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, "自由训练"),
        12 to SportKind(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, "户外步行"),
        13 to SportKind(ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS, "健身课程"),
        20 to SportKind(ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, "划船机"),
        28 to SportKind(ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON, "羽毛球"),
        30 to SportKind(ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS, "乒乓球"),
        108 to SportKind(ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS, "仰卧起坐"),
        // 操场跑步是跑道，不是跑步机 —— 不能写成 RUNNING_TREADMILL
        114 to SportKind(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, "操场跑步"),
    )

    /** 未枚举到的类型降级为「其他训练」，并打一次编码值方便以后补表。 */
    private val FALLBACK_KIND = SportKind(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, "运动")

    private val unknownTypesLogged = HashSet<Int>()

    /**
     * `SPORT_RECORD_BY_WATCH_BEAN` 一行 -> 一条 ExerciseSessionRecord。
     *
     * 只承载「什么运动、什么时候、多久」。**不**额外写 Distance / ActiveCalories：
     * 日汇总表（`DATE_DISTANCE_BEAN` / `DATE_CALORIE_BEAN`）已含这部分运动量，
     * 再写一遍就双重计入。
     */
    fun sportRecords(row: Map<String, String?>, cols: Map<String, String>, now: Long): List<SyncRecord> {
        val r = Row(row, cols)
        val start = r.long("RTC_START_TIME") ?: return emptyList()
        val end = r.long("RTC_END_TIME") ?: return emptyList()
        val rawType = r.long("TYPE")?.toInt()
        val eid = r.str("EID")?.takeIf { it.isNotBlank() }

        if (start <= 0L || end <= start || start > now) return emptyList()
        if (eid == null) {
            Log.w(TAG, "运动记录缺 EID，跳过（_id 是本地自增，重建库会变，不能当幂等键）")
            return emptyList()
        }

        val kind = if (rawType == null) FALLBACK_KIND else SPORT_TYPES[rawType] ?: run {
            if (unknownTypesLogged.add(rawType)) {
                Log.w(TAG, "未收录的运动 TYPE=$rawType，降级为「其他训练」（补表后可修）")
            }
            FALLBACK_KIND
        }

        return listOf(
            SyncRecord(
                type = RecordType.EXERCISE_SESSION.id,
                sourceKey = "vivo-sport-$eid",
                startTime = start,
                endTime = end,
                metadata = mapOf(
                    RecordMapper.M_EXERCISE_TYPE to kind.hcType.toString(),
                    RecordMapper.M_TITLE to kind.title,
                ),
                zoneOffsetSeconds = localOffsetSeconds(start),
            )
        )
    }
}
