package com.evwatch.xiaoha.data.model

/** 连接配置 */
data class WatchConfig(
    val batteryNo: String = "",
    val token: String = "",
    val ticket: String = "",
    val dingWebhook: String = "",
    val dingSecret: String = "",
    val wecomWebhook: String = "",
    val pollSeconds: Int = 60,
    val lowPercent: Int = 20,
    val milestones: String = "80,90,100"
) {
    val milestoneList: List<Int>
        get() = milestones.split(',', ' ', '，')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..100 }
            .sorted()
            .ifEmpty { listOf(80, 90, 100) }

    val isConfigured: Boolean get() = token.isNotBlank() && ticket.isNotBlank()
}

/** 一次电池快照 */
data class BatterySnapshot(
    val batteryNo: String = "",
    val soc: Int? = null,
    val charging: Boolean = false,
    val chargeStatus: Int? = null,
    val voltage: Double? = null,          // 真实总电压（来自 status）
    val ratedVoltage: Double? = null,    // 额定电压（来自 detail.volt）
    val current: Double? = null,
    val envTemp: Double? = null,
    val mosTemp: Double? = null,
    val soh: Int? = null,
    val cycles: String? = null,
    val remainCapacity: String? = null,  // 带单位字符串 "34.20Ah"
    val endurance: String? = null,       // 预估续航 "72km"
    val totalMileage: String? = null,    // 总里程 "4318km"
    val reportTime: String? = null,
    // 串数（单体个数 S）、功率(W)、压差(mV)、单体最高/最低(mV)、单体电压列表
    val cellCount: Int? = null,
    val power: Double? = null,
    val cellDiff: Double? = null,
    val cellMax: Double? = null,
    val cellMin: Double? = null,
    val cellVoltages: List<Double> = emptyList(),
    // GPS 轨迹（最近一点）
    val lastLocation: GpsPoint? = null,
    val ts: Long = System.currentTimeMillis()
)

/** GPS 轨迹点 */
data class GpsPoint(
    val lng: Double? = null,
    val lat: Double? = null,
    val address: String? = null,
    val time: String? = null
)

/** 接口异常 */
class ApiException(val code: Int, val msg: String?) :
    Exception("code=$code msg=${msg ?: ""}") {
    val isLoginExpired: Boolean get() = code == 103
}
