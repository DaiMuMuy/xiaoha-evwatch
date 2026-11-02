package com.evwatch.xiaoha.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evwatch.xiaoha.data.model.WatchConfig
import com.evwatch.xiaoha.ui.theme.EvWatchTheme
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EvWatchTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = koinViewModel()) {
    var editing by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    Scaffold(topBar = {
        TopAppBar(title = { Text("小哈电巡") })
    }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (editing || !viewModel.config.isConfigured) {
                ConfigSection(viewModel, onSaved = { editing = false })
            } else {
                SnapshotCard(viewModel)
                if (viewModel.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                viewModel.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.refresh() },
                        modifier = Modifier.weight(1f)
                    ) { Text("刷新") }
                    if (viewModel.running) {
                        OutlinedButton(
                            onClick = { viewModel.stopMonitoring() },
                            modifier = Modifier.weight(1f)
                        ) { Text("停止监控") }
                    } else {
                        Button(
                            onClick = { viewModel.startMonitoring() },
                            modifier = Modifier.weight(1f)
                        ) { Text("开始监控") }
                    }
                }
                OutlinedButton(
                    onClick = { editing = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("修改配置") }
            }

            if (viewModel.log.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        viewModel.log,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "监控说明：进入 App 后点「开始监控」才会持续轮询；到点自动提醒电量里程碑、低电量和充电起停，并推送到已配置的钉钉/企业微信机器人。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun SnapshotCard(viewModel: MainViewModel) {
    val snap = viewModel.snapshot
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${snap?.soc ?: "--"}%",
                fontSize = 60.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = if (snap?.charging == true) "充电中" else "未充电",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(10.dp))
            snap?.let { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Metric("电压", s.voltage?.let { String.format(Locale.US, "%.1fV", it) } ?: "-")
                    Metric("电流", s.current?.let { String.format(Locale.US, "%.1fA", it) } ?: "-")
                    Metric("MOS", s.mosTemp?.let { "${it.toInt()}℃" } ?: "-")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = buildString {
                        append("电池 ${s.batteryNo}")
                        s.soh?.let { append(" · 健康度 $it%") }
                        s.cycles?.let { append(" · 循环 $it") }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                s.reportTime?.let {
                    Text(
                        "上报时间 $it",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun ConfigSection(viewModel: MainViewModel, onSaved: () -> Unit) {
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

    Text("连接配置", fontSize = 18.sp, style = MaterialTheme.typography.titleMedium)
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

    Text("提醒设置", fontSize = 16.sp)
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

    Text("推送（可选）", fontSize = 16.sp)
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
