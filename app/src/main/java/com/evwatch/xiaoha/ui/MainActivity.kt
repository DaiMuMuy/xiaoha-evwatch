package com.evwatch.xiaoha.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evwatch.xiaoha.data.model.WatchConfig
import com.evwatch.xiaoha.ble.BleScanner
import com.evwatch.xiaoha.ui.theme.EvWatchTheme
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var onPermissionResult: ((Map<String, Boolean>) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EvWatchTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        requestBlePermissions = { perms, cb ->
                            onPermissionResult = cb
                            requestPermissions(perms, 1001)
                        }
                    )
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val map = permissions.zip(grantResults.map { it == android.content.pm.PackageManager.PERMISSION_GRANTED })
                .toMap()
            onPermissionResult?.invoke(map)
            onPermissionResult = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = koinViewModel(),
    requestBlePermissions: (Array<String>, (Map<String, Boolean>) -> Unit) -> Unit = { _, _ -> }
) {
    var editing by remember { mutableStateOf(false) }
    var bleExpanded by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val bleState by viewModel.bleState.collectAsState()

    // 蓝牙状态变化写入通讯台（按 status+scanning 去重）
    val bleKey = "${bleState.scanning}|${bleState.status}"
    LaunchedEffect(bleKey) {
        if (bleState.status.isNotBlank() &&
            bleState.status != "扫描中" &&
            !bleState.status.startsWith("扫描中")
        ) {
            viewModel.addBleLog(bleState.status)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("小哈电巡", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    // 右上角：二级下拉菜单
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "更多",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        // —— 蓝牙分组标题 ——
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "本地蓝牙 " + if (bleState.scanning) "（扫描中）" else "",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            },
                            onClick = { /* 仅作分组标题 */ }
                        )
                        // 扫描 / 停止
                        if (bleState.scanning) {
                            DropdownMenuItem(
                                text = { Text("停止扫描", fontSize = 14.sp) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.stopBle()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("扫描附近电池", fontSize = 14.sp) },
                                onClick = {
                                    menuOpen = false
                                    val perms = BleScanner.requiredPermissions(context)
                                    if (perms.isEmpty()) {
                                        viewModel.startBle(context)
                                    } else {
                                        requestBlePermissions(perms.toTypedArray()) { granted ->
                                            if (granted.values.all { it }) viewModel.startBle(context)
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        }
                        // 异常引导：未开蓝牙 / 未开定位
                        if (bleState.bluetoothOff) {
                            DropdownMenuItem(
                                text = { Text("去开启蓝牙", fontSize = 14.sp) },
                                onClick = {
                                    menuOpen = false
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                                    }
                                }
                            )
                        }
                        if (bleState.needLocation) {
                            DropdownMenuItem(
                                text = { Text("去开启定位服务", fontSize = 14.sp) },
                                onClick = {
                                    menuOpen = false
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                    }
                                }
                            )
                        }
                        // 设备列表（点选切换）
                        if (bleState.devices.isNotEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "附近电池（${bleState.devices.size}）",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = { /* 分组标题 */ }
                            )
                            bleState.devices.take(8).forEach { d ->
                                val sel = d.id == bleState.selected
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "${d.id.takeLast(10)} · ${d.percent}% · ${d.rssi}dBm",
                                            fontSize = 13.sp,
                                            color = if (sel) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.selectBleDevice(d.id)
                                    },
                                    trailingIcon = {
                                        if (sel) Text("✓", color = MaterialTheme.colorScheme.primary)
                                    }
                                )
                            }
                        } else if (bleState.status.isNotBlank() && !bleState.scanning) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        bleState.status,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = { /* 状态提示 */ }
                            )
                        }
                        // 开始监控 / 停止监控（切换）
                        if (viewModel.running) {
                            DropdownMenuItem(
                                text = { Text("停止监控", fontSize = 14.sp) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.stopMonitoring()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("开始监控", fontSize = 14.sp) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.startMonitoring()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("修改配置", fontSize = 14.sp) },
                            onClick = {
                                menuOpen = false
                                editing = true
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (editing || !viewModel.config.isConfigured) {
                ConfigSection(
                    viewModel,
                    onSaved = { editing = false },
                    onExit = { editing = false }
                )
            } else {
                SnapshotCard(viewModel, bleState)

                // 单体电压网格（4 列，C01 + 电压）
                viewModel.snapshot?.let { s ->
                    if (s.cellVoltages.isNotEmpty()) {
                        CellVoltageGrid(s)
                    }
                }

                if (viewModel.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                viewModel.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }

            CommsConsole(viewModel)

            Spacer(Modifier.height(4.dp))
            Text(
                "进入 App 后点「开始监控」持续轮询；到点提醒电量里程碑、低电量与充电起停，并推送到钉钉/企业微信。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun CommsConsole(viewModel: MainViewModel) {
    val entries = viewModel.logEntries
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.size(8.dp)
                    ) {}
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "实时通信控制台",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                TextButton(onClick = { viewModel.clearLog() }) {
                    Text("清空", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            // 日志滚动区
            val listState = rememberLazyListState()
            if (entries.isEmpty()) {
                Text(
                    "暂无通信记录，点右上角菜单「开始监控」或「扫描附近电池」后这里会实时显示。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    items(entries.size) { i ->
                        val e = entries[i]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                "[${e.time}]",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                e.type.icon,
                                fontSize = 11.sp
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                e.text,
                                fontSize = 11.sp,
                                color = when (e.type) {
                                    LogType.ERROR -> MaterialTheme.colorScheme.error
                                    LogType.ALERT -> MaterialTheme.colorScheme.secondary
                                    LogType.CLOUD_OK -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SnapshotCard(viewModel: MainViewModel, bleState: BleScanner.BleState) {
    val snap = viewModel.snapshot
    val localPct = bleState.selectedPercent
    val cloudPct = snap?.soc
    val mainPct = localPct ?: cloudPct
    val mainSource = when {
        localPct != null -> "本地蓝牙"
        cloudPct != null -> "云端"
        else -> null
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${mainPct ?: "--"}%",
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    snap?.charging == true -> "充电中"
                    mainSource != null -> "数据来源：$mainSource"
                    else -> "暂无数据"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))

            // 圆角电量进度条
            if (mainPct != null) {
                LinearProgressIndicator(
                    progress = { mainPct / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.height(10.dp))
            }

            // 两个来源：分段药丸
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SourcePill("云端", cloudPct?.let { "$it%" }, snap != null, Modifier.weight(1f))
                SourcePill(
                    "本地蓝牙",
                    localPct?.let { "$it%" },
                    bleState.scanning || localPct != null,
                    Modifier.weight(1f)
                )
            }

            snap?.let { s ->
                Spacer(Modifier.height(12.dp))
                // 第一行：电压 / 电流 / 功率
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Metric("电压", s.voltage?.let { String.format(Locale.US, "%.1fV", it) } ?: "-")
                    VDivider()
                    Metric("电流", s.current?.let { String.format(Locale.US, "%.1fA", it) } ?: "-")
                    VDivider()
                    Metric("功率", s.power?.let { formatPower(it) } ?: "-")
                }
                Spacer(Modifier.height(10.dp))
                // 第二行：串数 / 压差 / MOS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Metric("串数", s.cellCount?.let { "${it}S" } ?: "-")
                    VDivider()
                    Metric(
                        "压差",
                        s.cellDiff?.let { String.format(Locale.US, "%.0fmV", it) } ?: "-",
                        s.cellDiff?.let { if (it > 100) MetricTone.Warn else MetricTone.Normal }
                            ?: MetricTone.Normal
                    )
                    VDivider()
                    Metric("MOS", s.mosTemp?.let { "${it.toInt()}℃" } ?: "-")
                }
                // 第三行：续航 / 总里程 / 额定容量
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Metric("续航", s.endurance ?: "-")
                    VDivider()
                    Metric("总里程", s.totalMileage ?: "-")
                    VDivider()
                    Metric("剩余容量", s.remainCapacity ?: "-")
                }
                // 单体最高/最低
                if (s.cellMax != null || s.cellMin != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "单体 最高 ${s.cellMax?.let { String.format(Locale.US, "%.0fmV", it) } ?: "-"}" +
                            "  ·  最低 ${s.cellMin?.let { String.format(Locale.US, "%.0fmV", it) } ?: "-"}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                // GPS 轨迹（地址或经纬度）+ 位置入口
                s.lastLocation?.let { loc ->
                    Spacer(Modifier.height(10.dp))
                    val gps = loc.address
                        ?: if (loc.lat != null && loc.lng != null) "${loc.lat}, ${loc.lng}" else null
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "📍 ${gps ?: "暂无位置"}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        if (loc.lat != null && loc.lng != null) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
                                runCatching {
                                    val uri = android.net.Uri.parse("geo:${loc.lat},${loc.lng}?q=${loc.lat},${loc.lng}")
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }
                            }) {
                                Text("查看位置", fontSize = 11.sp)
                            }
                        }
                    }
                    loc.time?.let {
                        Text("定位时间 $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = buildString {
                        append("电池 ${s.batteryNo}")
                        s.soh?.let { append("  ·  健康度 $it%") }
                        s.cycles?.let { append("  ·  循环 $it") }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                s.reportTime?.let {
                    Text("上报时间 $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
                bleState.selected.takeIf { localPct != null }?.let {
                    Text(
                        "本地电池 ${it.takeLast(10)}" +
                            (bleState.selectedAgeSec?.let { a -> "  ·  ${if (a <= 15) "实时" else "上次"} ${a}秒前" } ?: ""),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun SourcePill(label: String, value: String?, active: Boolean, modifier: Modifier = Modifier) {
    val container = if (active) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val onContainer = if (active) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = container,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 11.sp, color = onContainer)
            Spacer(Modifier.height(2.dp))
            Text(value ?: "—", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = onContainer)
        }
    }
}

@Composable
private fun BleCard(
    state: BleScanner.BleState,
    expanded: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenBt: () -> Unit,
    onOpenLoc: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            // 头部：标题（折叠开关在顶栏右上角）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "本地蓝牙电量",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (state.scanning) {
                    AssistChip(
                        onClick = {},
                        label = { Text("扫描中") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(10.dp))

                    state.status.takeIf { !state.scanning && state.selectedPercent == null }?.let {
                        Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    if (state.needPermission) {
                        Text("需要蓝牙扫描权限", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    if (state.bluetoothOff) {
                        FilledTonalButton(onClick = onOpenBt, modifier = Modifier.fillMaxWidth()) {
                            Text("去开启蓝牙")
                        }
                    }
                    if (state.needLocation) {
                        FilledTonalButton(onClick = onOpenLoc, modifier = Modifier.fillMaxWidth()) {
                            Text("去开启定位服务")
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.scanning) {
                            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) {
                                Text("停止扫描")
                            }
                        } else {
                            Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                                Text("扫描附近电池")
                            }
                        }
                    }

                    if (state.devices.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("附近电池", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        state.devices.take(8).forEach { d ->
                            val sel = d.id == state.selected
                            FilterChip(
                                selected = sel,
                                onClick = { onSelect(d.id) },
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text("${d.id.takeLast(10)}  ·  ${d.percent}%  ·  ${d.rssi}dBm")
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "本地读取：靠近电池即可扫到广播读电量，无需配对、不消耗云端额度。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun CellVoltageGrid(snapshot: com.evwatch.xiaoha.data.model.BatterySnapshot) {
    val cells = snapshot.cellVoltages
    val max = snapshot.cellMax
    val min = snapshot.cellMin
    val diff = snapshot.cellDiff

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${cells.size}串单体实时电压 (mV)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (max != null) {
                        Text(
                            "最高 ${String.format(Locale.US, "%.0f", max)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (min != null) {
                        Text(
                            "最低 ${String.format(Locale.US, "%.0f", min)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (diff != null) {
                        Text(
                            "压差 ${String.format(Locale.US, "%.0f", diff)}",
                            fontSize = 11.sp,
                            color = if (diff > 100) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 4 列网格
            cells.chunked(4).forEachIndexed { rowIdx, rowCells ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowCells.forEachIndexed { colIdx, v ->
                        val idx = rowIdx * 4 + colIdx
                        CellItem(
                            index = idx + 1,
                            voltage = v,
                            isMax = max != null && v >= max - 0.5,
                            isMin = min != null && v <= min + 0.5,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // 补齐空位，保持对齐
                    repeat(4 - rowCells.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                if (rowIdx < cells.chunked(4).size - 1) Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun CellItem(
    index: Int,
    voltage: Double,
    isMax: Boolean,
    isMin: Boolean,
    modifier: Modifier = Modifier
) {
    val dead = voltage <= 0.0
    val valueColor = when {
        dead -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        isMax -> MaterialTheme.colorScheme.secondary
        isMin -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val dotColor = when {
        isMax -> MaterialTheme.colorScheme.primary   // 最高：蓝(青)
        isMin -> MaterialTheme.colorScheme.error      // 最低：红
        else -> null
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "C" + index.toString().padStart(2, '0'),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                dotColor?.let {
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        color = it,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.size(6.dp)
                    ) {}
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                if (dead) "0" else String.format(Locale.US, "%.0f", voltage),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = valueColor
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = color,
            shape = RoundedCornerShape(3.dp),
            modifier = Modifier.size(9.dp)
        ) {}
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun Metric(label: String, value: String, tone: MetricTone = MetricTone.Normal) {
    val color = when (tone) {
        MetricTone.Normal -> MaterialTheme.colorScheme.onSurface
        MetricTone.Warn -> MaterialTheme.colorScheme.error
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private enum class MetricTone { Normal, Warn }

@Composable
private fun VDivider() {
    Divider(
        modifier = Modifier
            .height(36.dp)
            .width(1.dp)
    )
}

/** 功率格式化：大于 1000W 显示 kW */
private fun formatPower(w: Double): String = if (kotlin.math.abs(w) >= 1000)
    String.format(Locale.US, "%.2fkW", w / 1000)
else String.format(Locale.US, "%.0fW", w)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ConfigSection(viewModel: MainViewModel, onSaved: () -> Unit, onExit: () -> Unit) {
    val cfg = viewModel.config
    var batteryNo by remember(cfg) { mutableStateOf(cfg.batteryNo) }
    var token by remember(cfg) { mutableStateOf(cfg.token) }
    var ticket by remember(cfg) { mutableStateOf(cfg.ticket) }
    var ding by remember(cfg) { mutableStateOf(cfg.dingWebhook) }
    var dingSecret by remember(cfg) { mutableStateOf(cfg.dingSecret) }
    var wecom by remember(cfg) { mutableStateOf(cfg.wecomWebhook) }
    var poll by remember(cfg) { mutableStateOf(cfg.pollSeconds.toString()) }
    var low by remember(cfg) { mutableStateOf(cfg.lowPercent.toString()) }
    var milestones by remember(cfg) { mutableStateOf(cfg.milestones) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 顶部：退出配置
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle("配置")
            TextButton(onClick = onExit) {
                Text("退出 ✕", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionTitle("连接配置")
        Text(
            "Token / Ticket 来自小哈智电小程序抓包；电池编号留空则自动取账号下第一块电池。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )

        OutlinedTextField(
            batteryNo, { batteryNo = it },
            label = { Text("电池编号（可留空）") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            token, { token = it },
            label = { Text("Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            ticket, { ticket = it },
            label = { Text("Ticket") },
            singleLine = false, maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )

        SectionTitle("提醒设置")
        OutlinedTextField(
            poll, { poll = it.filter { c -> c.isDigit() } },
            label = { Text("轮询周期（秒）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            low, { low = it.filter { c -> c.isDigit() } },
            label = { Text("低电量阈值（%）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            milestones, { milestones = it },
            label = { Text("里程碑（逗号分隔）") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        SectionTitle("推送（可选）")
        OutlinedTextField(
            ding, { ding = it },
            label = { Text("钉钉机器人 Webhook") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            dingSecret, { dingSecret = it },
            label = { Text("钉钉加签密钥（可选）") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            wecom, { wecom = it },
            label = { Text("企业微信机器人 Webhook") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        viewModel.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        Button(
            onClick = {
                viewModel.onConfigChange(
                    WatchConfig(
                        batteryNo = batteryNo,
                        token = token,
                        ticket = ticket,
                        dingWebhook = ding,
                        dingSecret = dingSecret,
                        wecomWebhook = wecom,
                        pollSeconds = poll.toIntOrNull() ?: 60,
                        lowPercent = low.toIntOrNull() ?: 20,
                        milestones = milestones
                    )
                )
                viewModel.saveConfig()
                viewModel.refresh()
                onSaved()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存并测试") }
    }
}
