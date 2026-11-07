package io.github.blockman3063.vhsync.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * M2 调试用：读取端报回来的原始数据概况。
 *
 * 这一阶段**不写 Health Connect**，只回答两个问题：
 * 「能从 vivo 健康库里读出多少条」「时间范围到哪里」。
 *
 * 数据只留在本进程内存里，不落盘、不上传。
 */
data class PreviewSnapshot(
    /** 采集时刻（本机毫秒）。 */
    val at: Long,
    /** DATE_STEP_COUNT_BEAN：一行 = 一天。 */
    val stepsRows: Int,
    val stepsFirst: Long,
    val stepsLast: Long,
    val stepsSum: Long,
    /** WATCH_HEART_RATE：一行 = 一天，点明细在 POINT_LIST 里。 */
    val hrRows: Int,
    val hrFirst: Long,
    val hrLast: Long,
    /** 最近一天的心率点数与首末时间；解析不了时为 -1 / 0。 */
    val hrPointsLastDay: Int,
    val hrLastDayFirst: Long,
    val hrLastDayLast: Long,
    /** 读取端所在进程名，用来确认是哪一份注入在回话。 */
    val process: String,
    /** 读表过程中遇到的问题，正常为空。 */
    val note: String = "",
) {
    val stepsDays: String
        get() = if (stepsRows <= 0) "—" else "$stepsRows 天"
}

object PreviewStore {

    private val _snapshot = MutableStateFlow<PreviewSnapshot?>(null)
    val snapshot: StateFlow<PreviewSnapshot?> = _snapshot.asStateFlow()

    fun accept(s: PreviewSnapshot) {
        _snapshot.value = s
    }

    fun clear() {
        _snapshot.value = null
    }
}

/**
 * 跨进程传输的字段名。两端共用，避免一边改名另一边读不到。
 */
object PreviewKeys {
    const val AT = "at"
    const val PROCESS = "process"
    const val NOTE = "note"

    const val STEPS_ROWS = "steps_rows"
    const val STEPS_FIRST = "steps_first"
    const val STEPS_LAST = "steps_last"
    const val STEPS_SUM = "steps_sum"

    const val HR_ROWS = "hr_rows"
    const val HR_FIRST = "hr_first"
    const val HR_LAST = "hr_last"
    const val HR_POINTS = "hr_points"
    const val HR_POINTS_FIRST = "hr_points_first"
    const val HR_POINTS_LAST = "hr_points_last"

    /** provider / Bridge 的 method 名。 */
    const val METHOD = "preview"
}
