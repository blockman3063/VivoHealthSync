package io.github.blockman3063.vhsync.hook

import android.os.Bundle
import android.util.Log
import io.github.blockman3063.vhsync.core.PreviewKeys
import org.json.JSONArray

/**
 * M2 读取端：只读地看一眼 vivo 健康库里的步数与心率，产出**概况**。
 *
 * 这里不建映射、不写 Health Connect，只回答「能读出多少条、时间范围到哪」，
 * 用来证明「注入 → 取库 → 查表 → 跨进程回传」这条链路真的通了。
 *
 * 两张表的语义来自探库结论（不是猜的）：
 *   DATE_STEP_COUNT_BEAN —— _id = 当天 00:00 毫秒，VALUE = 全天步数，621 行
 *   WATCH_HEART_RATE     —— _id = 当天 00:00 毫秒，POINT_LIST = [{"timestamp":ms,"value":bpm},...]，601 行
 */
internal object PreviewReader {

    private const val TAG = "VHSyncPreview"

    private const val T_STEPS = "DATE_STEP_COUNT_BEAN"
    private const val T_HR = "WATCH_HEART_RATE"

    /** 只解析最近一天的点明细：一条就有十几万字符，全解析没有意义。 */
    private const val HR_SAMPLE_ROWS = 1

    fun collect(reader: TableReader): Bundle {
        val tables = reader.tableNames().map { it.uppercase() }.toSet()
        val b = Bundle()
        b.putLong(PreviewKeys.AT, System.currentTimeMillis())
        b.putString(PreviewKeys.PROCESS, processName())

        var note = ""

        // ---- 步数 ----
        if (T_STEPS in tables) {
            val row = reader.query(
                "SELECT COUNT(*) AS n, MIN(_id) AS a, MAX(_id) AS c, " +
                    "IFNULL(SUM(VALUE), 0) AS s FROM $T_STEPS"
            ).firstOrNull()
            b.putInt(PreviewKeys.STEPS_ROWS, row?.get("n")?.toIntOrNull() ?: 0)
            b.putLong(PreviewKeys.STEPS_FIRST, row?.get("a")?.toLongOrNull() ?: 0L)
            b.putLong(PreviewKeys.STEPS_LAST, row?.get("c")?.toLongOrNull() ?: 0L)
            b.putLong(PreviewKeys.STEPS_SUM, row?.get("s")?.toLongOrNull() ?: 0L)
        } else {
            note += "没有 $T_STEPS；"
            Log.w(TAG, "库里找不到 $T_STEPS")
        }

        // ---- 心率（行级） ----
        if (T_HR in tables) {
            val row = reader.query(
                "SELECT COUNT(*) AS n, MIN(_id) AS a, MAX(_id) AS c FROM $T_HR"
            ).firstOrNull()
            val rows = row?.get("n")?.toIntOrNull() ?: 0
            b.putInt(PreviewKeys.HR_ROWS, rows)
            b.putLong(PreviewKeys.HR_FIRST, row?.get("a")?.toLongOrNull() ?: 0L)
            b.putLong(PreviewKeys.HR_LAST, row?.get("c")?.toLongOrNull() ?: 0L)

            // ---- 心率（点级，只取最近一天） ----
            val sample = reader.query(
                "SELECT _id, POINT_LIST FROM $T_HR ORDER BY _id DESC LIMIT $HR_SAMPLE_ROWS"
            )
            var points = 0
            var first = 0L
            var last = 0L
            for (r in sample) {
                val list = r["POINT_LIST"] ?: continue
                val parsed = runCatching { JSONArray(list) }.getOrNull() ?: continue
                points += parsed.length()
                for (i in 0 until parsed.length()) {
                    val ts = parsed.optJSONObject(i)?.optLong("timestamp", 0L) ?: 0L
                    if (ts <= 0L) continue
                    if (first == 0L || ts < first) first = ts
                    if (ts > last) last = ts
                }
            }
            b.putInt(PreviewKeys.HR_POINTS, points)
            b.putLong(PreviewKeys.HR_POINTS_FIRST, first)
            b.putLong(PreviewKeys.HR_POINTS_LAST, last)
        } else {
            note += "没有 $T_HR；"
            Log.w(TAG, "库里找不到 $T_HR")
        }

        b.putString(PreviewKeys.NOTE, note)
        Log.i(TAG, "预览采集完成：步数 ${b.getInt(PreviewKeys.STEPS_ROWS)} 天，" +
            "心率 ${b.getInt(PreviewKeys.HR_ROWS)} 天 / 最近一天 ${b.getInt(PreviewKeys.HR_POINTS)} 点")
        return b
    }

    /** 当前进程名。只有主进程才该回话，子进程（deviceConn/permanent/:widget）一律跳过。 */
    fun processName(): String =
        runCatching { android.app.Application.getProcessName() }.getOrDefault("")
}
