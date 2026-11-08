package io.github.blockman3063.vhsync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.blockman3063.vhsync.core.HcClient
import io.github.blockman3063.vhsync.core.PreviewSnapshot
import io.github.blockman3063.vhsync.core.PreviewStore
import io.github.blockman3063.vhsync.core.RecordType
import io.github.blockman3063.vhsync.core.Settings
import io.github.blockman3063.vhsync.core.SyncEngine
import io.github.blockman3063.vhsync.core.SyncStatus
import io.github.blockman3063.vhsync.core.TriggerSender
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

private val DAY_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

private val CLOCK_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VHSyncScreen() {
    val ctx = LocalContext.current
    val status by SyncEngine.status.collectAsState()
    val logs by SyncEngine.logs.collectAsState()

    val hc = remember { HcClient(ctx) }
    val required = remember(hc) { hc.requiredPermissions }
    /**
     * 权限请求用**标准**的多权限契约，不用 Health Connect 客户端库的
     * PermissionController.createRequestPermissionResultContract()。
     *
     * 原因（反编译 connect-client 1.1.0 确认）：
     *   客户端库有两套契约 ——
     *     permission.HealthPermissionsRequestAppContract      -> 自定义 action，给 HC 独立 APK 用
     *     permission.platform.HealthPermissionsRequestModuleContract -> 直接复用
     *                                              ActivityResultContracts.RequestMultiplePermissions
     *   本机的 Health Connect 是系统模块，走的是后者，也就是标准权限请求。
     *   用前者的话，Intent 会被 ColorOS 的权限界面接走后立刻返回、什么都不做（实测）。
     */
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        SyncEngine.notePermission(result.isNotEmpty() && result.values.all { it })
    }

    /**
     * 弹出系统授权对话框，逐条勾选要授予的健康数据类型。
     *
     * 注意这个按钮是必需的：ColorOS 的应用详情页只在「至少已授权一项」时才显示
     * 健康权限那一行 —— 用户一旦把权限全部关掉，设置里就再也没有入口了。
     * 所以应用必须自己能重新发起授权。
     */
    fun openHcAuth() = permissionLauncher.launch(required.toTypedArray())

    var interval by remember { mutableIntStateOf(Settings.intervalMinutes(ctx)) }
    var window by remember { mutableIntStateOf(Settings.windowDays(ctx)) }
    var showLogs by remember { mutableStateOf(false) }
    var keepAlive by remember { mutableStateOf(Settings.keepAlive(ctx)) }

    // 进入页面时核对一次权限（用户可能在 Health Connect 里改过），
    // 并问一次读取端是否在线。都是事件驱动，只在界面打开时各发生一次。
    LaunchedEffect(Unit) {
        SyncEngine.notePermission(hc.isWriteGranted())
        SyncEngine.pingReader()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("VHSync") }) }) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 未授权时置顶提醒：没权限什么都写不进去 ──
            if (!status.hcPermissionGranted) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("还没有 Health Connect 写入权限", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "没有授权的话，数据读出来也写不进 Health Connect。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "提示：在 Health Connect 的「应用权限」里，本应用可能显示在" +
                                    "「非活跃应用」下。那是因为应用每次更新，Health Connect " +
                                    "都会撤销它的授权记录；点下面的按钮重新授权即可恢复正常。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = { openHcAuth() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("去授权") }
                        }
                    }
                }
            }

            item { StatusCard(status, onSync = { SyncEngine.requestBackfill() }) }
            item { PreviewCard(ctx) }
            item {
                IntervalCard(
                    interval = interval,
                    window = window,
                    keepAlive = keepAlive,
                    onInterval = { interval = it; Settings.setIntervalMinutes(ctx, it) },
                    onWindow = { window = it; Settings.setWindowDays(ctx, it) },
                    onKeepAlive = { keepAlive = it; Settings.setKeepAlive(ctx, it) },
                )
            }
            item { DataCard() }
            item { DiscardedCard() }
            item { LogCard(showLogs, logs) { showLogs = !showLogs } }
        }
    }
}

/** 状态 + 唯一的主动作。 */
@Composable
private fun StatusCard(status: SyncStatus, onSync: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("同步状态", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot("读取端", status.hookConnected)
                StatusDot("写入权限", status.hcPermissionGranted)
            }

            Text(
                "上次同步：${
                    status.lastSyncAtMillis
                        ?.let { TIME_FMT.format(Instant.ofEpochMilli(it)) }
                        ?: "还没同步过"
                }",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "累计写入 ${status.syncedCount} 条" +
                    if (status.failedCount > 0) "，失败 ${status.failedCount} 条" else "",
                style = MaterialTheme.typography.bodySmall,
            )

            if (!status.hookConnected) {
                Text(
                    "读取端未响应。请确认：LSPosed 里已启用本模块、作用域勾选了「vivo 健康」，" +
                        "然后打开一次 vivo 健康。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = onSync,
                enabled = !status.pendingBackfill,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (status.pendingBackfill) "正在同步…" else "立即同步")
            }
            if (status.pendingBackfill) {
                Text(
                    "已通知读取端，正在读取并写入，通常几秒内完成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 一个小小的状态点 + 文字。 */
@Composable
private fun StatusDot(label: String, ok: Boolean) {
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("●", color = color)
        Text("$label${if (ok) "正常" else "未就绪"}", style = MaterialTheme.typography.bodySmall)
    }
}

/** 同步间隔与时间窗口，两个都让用户直接选。 */
@Composable
private fun IntervalCard(
    interval: Int,
    window: Int,
    keepAlive: Boolean,
    onInterval: (Int) -> Unit,
    onWindow: (Int) -> Unit,
    onKeepAlive: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("同步设置", style = MaterialTheme.typography.titleMedium)

            Text("兜底检查间隔", style = MaterialTheme.typography.bodyMedium)
            ChipRow(
                options = Settings.INTERVAL_OPTIONS,
                selected = interval,
                onSelect = onInterval,
            )
            Text(
                "数据一变就会自动同步（vivo 健康写库后约 10 秒）。这个间隔只是兜底：" +
                    "万一漏掉写信号，也会每隔这么久比一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            Text("回填时间范围", style = MaterialTheme.typography.bodyMedium)
            ChipRow(
                options = Settings.WINDOW_OPTIONS,
                selected = window,
                onSelect = onWindow,
            )
            Text(
                "只读取这段时间内的数据，避免一次灌入几年的记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("保持后台运行", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (keepAlive) {
                            "常驻一个轻量进程（一条静音通知），vivo 健康一写库就能自动同步，" +
                                "不需要打开本应用。建议同时在系统设置里允许「自启动」、" +
                                "并把本应用加入电池优化白名单，否则系统可能把服务清掉。"
                        } else {
                            "已关闭：自动同步不会生效，只能在本应用里手动同步。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Switch(checked = keepAlive, onCheckedChange = onKeepAlive)
            }
        }
    }
}

/** 一行可换行的选项小胶囊。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

/**
 * 读取端预览卡：只读地看一眼源库的条数与时间范围，确认注入链路是否贯通。
 * 这一步本身不触发同步、不写 Health Connect；写入由上面的「立即同步」负责。
 */
@Composable
private fun PreviewCard(ctx: android.content.Context) {
    val snap by PreviewStore.snapshot.collectAsState()
    var requested by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("读取端预览", style = MaterialTheme.typography.titleMedium)
            Text(
                "从 vivo 健康库里只读地取一次步数和心率的原始条数与时间范围，" +
                    "用来确认读取链是否贯通。这一步不写入 Health Connect。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Button(
                onClick = {
                    requested = true
                    TriggerSender.preview(ctx)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("读取预览") }
            Text(
                "需要 vivo 健康正在运行（读取端跟着它加载）。点了没反应的话，" +
                    "先打开一次 vivo 健康再回来点。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            val s = snap
            if (s == null) {
                if (requested) {
                    Text("已发出请求，等待读取端回话…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                HorizontalDivider()
                PreviewRow("采集时间", TIME_FMT.format(Instant.ofEpochMilli(s.at)))
                PreviewRow("回话进程", s.process.ifBlank { "—" })
                HorizontalDivider()
                PreviewRow("步数", "${s.stepsRows} 天 / 合计 ${s.stepsSum} 步")
                PreviewRow("步数范围", rangeOf(s.stepsFirst, s.stepsLast))
                HorizontalDivider()
                PreviewRow("心率", "${s.hrRows} 天")
                PreviewRow("心率范围", rangeOf(s.hrFirst, s.hrLast))
                PreviewRow(
                    "最近一天",
                    if (s.hrPointsLastDay <= 0) "—"
                    else "${s.hrPointsLastDay} 个点（${clock(s.hrLastDayFirst)}–${clock(s.hrLastDayLast)}）",
                )
                if (s.note.isNotBlank()) {
                    Text(
                        s.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** 时间戳区间显示：同一天只显示一个日期。 */
private fun rangeOf(a: Long, b: Long): String {
    if (a <= 0L || b <= 0L) return "—"
    val da = DAY_FMT.format(Instant.ofEpochMilli(a))
    val db = DAY_FMT.format(Instant.ofEpochMilli(b))
    return if (da == db) da else "$da → $db"
}

private fun clock(ms: Long): String =
    if (ms <= 0L) "—" else CLOCK_FMT.format(Instant.ofEpochMilli(ms))

/** 会同步哪些数据；以及需要时清除本应用写入的数据。 */
@Composable
private fun DataCard() {
    // 只列当前里程碑真正会写的类型，不列能力清单，避免界面说同步几十类、实际只写两类。
    val synced = RecordType.active
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本阶段同步的数据（共 ${synced.size} 类）", style = MaterialTheme.typography.titleMedium)
            Text(
                synced.joinToString("、") { it.label },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "其余类型会在后续里程碑逐类实测对账后再接入；Health Connect 无对应类型的数据见下方清单。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                "数据来源：vivo 健康数据库。读取在 vivo 健康进程内完成，只做查询，不修改它的任何数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            OutlinedButton(
                onClick = { SyncEngine.clearAll() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("清除本应用已写入的数据") }
            Text(
                "只删除 VHSync 自己写进 Health Connect 的记录，不动其他应用的数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Health Connect 没有对应记录类型的数据，明确列出来，不静默丢弃。 */
@Composable
private fun DiscardedCard() {
    val discarded = RecordType.discarded
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("不同步的数据（共 ${discarded.size} 类）", style = MaterialTheme.typography.titleMedium)
            Text(
                "下面这些在 Health Connect 里没有对应的记录类型，因此不写入：" +
                    discarded.joinToString("、") { it.label },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 折叠的日志：平时不占地方，出问题时才展开。 */
@Composable
private fun LogCard(expanded: Boolean, logs: List<String>, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("运行日志", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onToggle) { Text(if (expanded) "收起" else "展开") }
            }
            if (expanded) {
                if (logs.isEmpty()) {
                    Text("暂无日志", style = MaterialTheme.typography.bodySmall)
                } else {
                    logs.takeLast(40).reversed().forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            } else {
                Text(logs.lastOrNull() ?: "暂无日志", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}