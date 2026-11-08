package io.github.blockman3063.vhsync.hook

import android.util.Log
import io.github.blockman3063.vhsync.core.RecordType
import io.github.blockman3063.vhsync.hook.TableReader.Companion.safeIdent

/**
 * 源库变化检测：判断「这 9 类里有哪些变了」。
 *
 * 为什么需要它：M5 要在 vivo 健康运行时自动把新数据同步出去，而且不能每来一点
 * 数据就把 9 类几百条全读一遍重写一遍。所以先给每张源表算一个指纹，指纹变了才
 * 去查那张表。
 *
 * 指纹 = 行数 + 若干列的文本总长度。选这两个维度是因为：
 *   - 行数变 → 有新记录（新的一天、新一次运动、新的睡眠）
 *   - 总长度变 → 已有行被改写（今天的步数/卡路里在涨、心率点追加进 POINT_LIST）
 * 两者都不变但内容变了的概率极低；就算漏一次，下一次写入仍会触发，且人工点
 * 「立即同步」永远走全量。
 *
 * 指纹只用 SELECT，且单条 SQL 一次算完 —— 600 行的表跑一遍是毫秒级。
 * 不做任何写入，不打印任何数据正文。
 */
internal object ChangeDetector {

    private const val TAG = "VHSyncChange"

    /**
     * 一张源表 -> 它对应的 HC 类型 + 参与指纹的列。
     *
     * 列名按逻辑名写，实际取用时按大小写无关匹配（表结构运行时推导）。
     */
    private class Watch(
        val table: String,
        val types: List<RecordType>,
        val columns: List<String>,
    )

    private val WATCHES = listOf(
        Watch("DATE_STEP_COUNT_BEAN", listOf(RecordType.STEPS), listOf("_ID", "VALUE")),
        Watch("DATE_DISTANCE_BEAN", listOf(RecordType.DISTANCE), listOf("_ID", "VALUE")),
        Watch("DATE_CALORIE_BEAN", listOf(RecordType.ACTIVE_CALORIES), listOf("_ID", "VALUE")),
        Watch("WATCH_HEART_RATE", listOf(RecordType.HEART_RATE), listOf("_ID", "POINT_LIST")),
        Watch("WATCH_OXYGEN", listOf(RecordType.BLOOD_OXYGEN), listOf("_ID", "POINT_LIST")),
        Watch(
            "WATCH_SLEEP_BEAN", listOf(RecordType.SLEEP_SESSION),
            listOf(
                "ENTER_TIME", "EXIT_TIME",
                "LIGHT_SLEEP_LIST", "DEEP_SLEEP_LIST", "REM_SLEEP_LIST", "AWAKE_SLEEP_LIST",
            ),
        ),
        // 体重表同时承载身高，所以一张表对应两个类型
        Watch(
            "WEIGHT_BEAN", listOf(RecordType.WEIGHT, RecordType.HEIGHT),
            listOf("WEIGHT", "HEIGHT", "VERSION"),
        ),
        Watch(
            "SPORT_RECORD_BY_WATCH_BEAN", listOf(RecordType.EXERCISE_SESSION),
            listOf("TYPE", "RTC_START_TIME", "RTC_END_TIME", "CALORIE"),
        ),
    )

    /** 参与检测的表名清单（给启动时的结构报告用）。 */
    fun watchedTables(): List<String> = WATCHES.map { it.table }

    /**
     * 给所有可识别的表算一次指纹。
     *
     * 表不存在或列对不上时**跳过该表**（不报错），因为这类问题由 QueryPlan
     * 在真正查数时报出来；这里只负责「有没有变」，不该成为第二个失败点。
     */
    fun snapshot(reader: TableReader): Map<RecordType, String> {
        val byUpper = reader.tableNames().associateBy { it.uppercase() }
        val out = HashMap<RecordType, String>()
        for (w in WATCHES) {
            val actual = byUpper[w.table] ?: continue
            val have = reader.columns(actual)
            val real = w.columns.mapNotNull { logical ->
                have.firstOrNull { it.equals(logical, true) }
            }
            if (real.isEmpty()) continue
            // CAST AS TEXT 让数字与文本用同一把尺子量；不用 QUOTE 是为了避免
            // 把 POINT_LIST 那种几十 KB 的大文本再复制一遍
            val expr = real.joinToString(" || ") {
                "IFNULL(LENGTH(CAST(`${it.safeIdent()}` AS TEXT)), 0)"
            }
            val sql = "SELECT COUNT(*) AS n, IFNULL(TOTAL(LENGTH($expr)), 0) AS s " +
                "FROM `${actual.safeIdent()}`"
            val row = runCatching { reader.query(sql).firstOrNull() }.getOrNull() ?: continue
            val fp = "${row["n"]}:${row["s"]}"
            for (t in w.types) out[t] = fp
        }
        return out
    }

    /**
     * 与上次的指纹比对。
     *
     * @param prev null 表示还没有基线（进程刚起来、或首次同步），此时把
     *             [current] 里出现的类型全部视为「变了」，做一次全量对齐。
     */
    fun changed(prev: Map<RecordType, String>?, current: Map<RecordType, String>): Set<RecordType> {
        if (prev == null) return current.keys.toSet()
        val out = HashSet<RecordType>()
        for ((type, fp) in current) {
            if (prev[type] != fp) out += type
        }
        if (out.isNotEmpty()) {
            Log.i(TAG, "检测到变化的类型：${out.joinToString { it.label }}")
        }
        return out
    }
}
