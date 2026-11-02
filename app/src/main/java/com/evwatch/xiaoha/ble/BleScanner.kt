package com.evwatch.xiaoha.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedHashMap
import java.util.regex.Pattern

/**
 * 本地蓝牙读电量（移植自 battery-gauge）
 *
 * 不连接、不配对，只扫描附近电池的 BLE 广播，解析设备名：
 *   HBB<编号>SOC<三位电量>  →  取本机电量百分比
 * 与云端轮询并存，作为「本地数据」来源。
 */
object BleScanner {

    data class Reading(
        val id: String,
        val percent: Int,
        val rssi: Int,
        val seenMs: Long
    )

    private val PATTERN: Pattern = Pattern.compile("^(HBB[0-9]+)SOC([0-9]{3})$")

    /** 解析设备名；不符合格式返回 null */
    fun parseName(name: String?): Reading? {
        if (name == null) return null
        val m = PATTERN.matcher(name)
        if (!m.matches()) return null
        val percent = m.group(2).toInt()
        if (percent > 100) return null
        return Reading(m.group(1), percent, 0, 0)
    }

    private val _state = MutableStateFlow(BleState())
    val state: StateFlow<BleState> = _state.asStateFlow()

    data class BleState(
        val scanning: Boolean = false,
        val needPermission: Boolean = false,
        val needLocation: Boolean = false,
        val bluetoothOff: Boolean = false,
        val status: String = "未开始",
        val devices: List<Reading> = emptyList(),
        val selected: String = "",
        val selectedPercent: Int? = null,
        val selectedAgeSec: Long? = null
    )

    private var scanner: BluetoothLeScanner? = null
    private val found = LinkedHashMap<String, Reading>()
    private var selectedId: String = ""
    private var lastSeenMs: Long = 0

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(type: Int, result: ScanResult) {
            val r = parseName(result.scanRecord?.deviceName) ?: return
            val now = System.currentTimeMillis()
            val reading = r.copy(rssi = result.rssi, seenMs = now)
            found[r.id] = reading
            if (selectedId.isEmpty()) selectedId = r.id
            if (selectedId == r.id) lastSeenMs = now
            emit()
        }

        override fun onScanFailed(error: Int) {
            _state.value = _state.value.copy(
                scanning = false,
                status = "扫描不可用（错误 $error），可重试"
            )
        }
    }

    private fun emit() {
        val s = _state.value
        val list = found.values.toList()
        val sel = if (selectedId.isNotEmpty()) found[selectedId] else null
        val age = if (sel != null && lastSeenMs > 0)
            (System.currentTimeMillis() - lastSeenMs) / 1000 else null
        _state.value = s.copy(
            devices = list,
            selected = selectedId,
            selectedPercent = sel?.percent,
            selectedAgeSec = age,
            status = if (s.scanning) "扫描中 · 已发现 ${list.size} 块" else s.status
        )
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        val needed = requiredPermissions(context)
        if (needed.isNotEmpty()) {
            _state.value = _state.value.copy(
                needPermission = true,
                status = "缺少扫描权限，请在设置中授予"
            )
            return
        }
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = mgr?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = _state.value.copy(
                bluetoothOff = true,
                status = "请先开启蓝牙"
            )
            return
        }
        if (Build.VERSION.SDK_INT < 31 &&
            !(context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager)
                .isLocationEnabled
        ) {
            _state.value = _state.value.copy(
                needLocation = true,
                status = "请开启定位服务（Android 10 扫描蓝牙的系统要求）"
            )
            return
        }
        scanner = adapter.bluetoothLeScanner ?: run {
            _state.value = _state.value.copy(status = "蓝牙尚未就绪")
            return
        }
        try {
            scanner?.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback
            )
            _state.value = _state.value.copy(
                scanning = true,
                bluetoothOff = false,
                needPermission = false,
                needLocation = false,
                status = "扫描中"
            )
            emit()
        } catch (e: RuntimeException) {
            _state.value = _state.value.copy(scanning = false, status = "无法开始扫描")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (_state.value.scanning && scanner != null) {
            try { scanner?.stopScan(callback) } catch (_: RuntimeException) {}
        }
        _state.value = _state.value.copy(scanning = false, status = "已停止")
    }

    fun select(id: String) {
        selectedId = id
        lastSeenMs = found[id]?.seenMs ?: 0
        emit()
    }

    /** Android 12+ 需要 BLUETOOTH_SCAN/CONNECT，否则需要定位 */
    fun requiredPermissions(context: Context): List<String> {
        val has = { p: String ->
            context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        }
        return if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                .filterNot { has(it) }
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION).filterNot { has(it) }
        }
    }

    fun reset() {
        found.clear()
        selectedId = ""
        lastSeenMs = 0
        _state.value = BleState()
    }
}
