package com.evwatch.xiaoha.data.network

import android.util.Log
import com.evwatch.xiaoha.data.crypto.HbCrypto
import com.evwatch.xiaoha.data.model.ApiException
import com.evwatch.xiaoha.data.model.BatterySnapshot
import com.evwatch.xiaoha.data.model.GpsPoint
import com.evwatch.xiaoha.data.model.WatchConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.random.Random

/**
 * 小哈智电接口客户端
 *
 * POST https://switchpowerapi.hellobike.com/api?{action}
 * header: chaos / x-chaos-env / systemCode / timestamp / nonce / signature
 * body:   base64(AES(json))
 */
class HbApi {

    private val host = "https://switchpowerapi.hellobike.com"
    private val ua =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 26_6_1 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.79(0x18004f30) " +
            "NetType/WIFI Language/zh_CN"
    private val appId = "wx9d7f582059211357"

    private val systemCode = "LB64"
    private val version = "2.1.1"
    private val appVersion = "8.0.79"
    private val cityCode = "0755"
    private val chaosEnv = "pro-1.1.3-beta.1"

    private fun basePayload(action: String): JSONObject = JSONObject().apply {
        put("systemCode", systemCode)
        put("version", version)
        put("appVersion", appVersion)
        put("action", action)
        put("cityCode", cityCode)
    }

    private fun auth(payload: JSONObject, config: WatchConfig) {
        payload.put("token", config.token)
        payload.put("ticket", config.ticket)
        payload.put("chaosChangeBattery", true)
    }

    /** 发一条加密签名请求，返回解密后的 JSON */
    private fun post(action: String, payload: JSONObject): JSONObject {
        val plain = payload.toString()
        val cipher = HbCrypto.encrypt(plain)
        val ts = System.currentTimeMillis().toString()
        val nonce = (Random.nextInt(100000, 1000000)).toString()
        val signature = HbCrypto.sign(plain, ts, nonce)

        val conn = (URL("$host/api?$action").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("chaos", "true")
            setRequestProperty("x-chaos-env", chaosEnv)
            setRequestProperty("systemCode", systemCode)
            setRequestProperty("timestamp", ts)
            setRequestProperty("nonce", nonce)
            setRequestProperty("signature", signature)
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Referer", "https://servicewechat.com/$appId/12/page-frame.html")
        }

        conn.outputStream.use { it.write(cipher.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        var bytes = stream.use { readAll(it) }
        if (conn.contentEncoding?.contains("gzip", true) == true) {
            bytes = GZIPInputStream(bytes.inputStream()).use { readAll(it) }
        }
        conn.disconnect()
        if (code !in 200..299) throw Exception("HTTP $code")

        val text = String(bytes, Charsets.UTF_8).trim()
        val json = if (text.startsWith("{")) JSONObject(text) else JSONObject(HbCrypto.decrypt(text))
        val resCode = json.optInt("code", -1)
        if (resCode != 0) throw ApiException(resCode, json.optString("msg"))
        return json
    }

    private fun readAll(i: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = i.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 电池列表（用于没填电池编号时自动取第一块） */
    suspend fun batteryList(config: WatchConfig): List<String> = withContext(Dispatchers.IO) {
        val p = basePayload("swp.li.user.battery.list")
        auth(p, config)
        val data = post("swp.li.user.battery.list", p).opt("data") as? JSONArray ?: JSONArray()
        (0 until data.length()).mapNotNull { i ->
            data.optJSONObject(i)?.optString("batteryNo")?.takeIf { it.isNotBlank() }
        }
    }

    /** 实时电气参数 */
    private fun batteryStatus(config: WatchConfig, batteryNo: String): JSONObject {
        val action = "swp.li.user.battery.status"
        val p = basePayload(action)
        p.put("batteryNo", batteryNo)
        auth(p, config)
        return post(action, p).opt("data") as? JSONObject ?: JSONObject()
    }

    /** 电池详情（电量、健康度等） */
    private fun batteryDetail(config: WatchConfig, batteryNo: String): JSONObject {
        val action = "swp.li.user.battery.detail"
        val p = basePayload(action)
        p.put("batteryNo", batteryNo)
        auth(p, config)
        return post(action, p).opt("data") as? JSONObject ?: JSONObject()
    }

    /** 取一次快照：先取实时状态，再取详情 */
    suspend fun snapshot(config: WatchConfig): BatterySnapshot = withContext(Dispatchers.IO) {
        var batteryNo = config.batteryNo.trim()
        if (batteryNo.isEmpty()) {
            batteryNo = batteryList(config).firstOrNull() ?: throw ApiException(401001, "用户未绑定电池")
        }

        val status = runCatching { batteryStatus(config, batteryNo) }.getOrElse {
            if (it is ApiException) throw it else JSONObject()
        }
        val detail = runCatching { batteryDetail(config, batteryNo) }.getOrElse {
            if (it is ApiException) throw it else JSONObject()
        }

        // chargeStatus：detail 里是数字，last 里是字符串；统一用字符串判定
        val chargeStatusRaw = when {
            detail.has("chargeStatus") -> detail.opt("chargeStatus")?.toString()
            status.has("chargeStatus") -> status.opt("chargeStatus")?.toString()
            else -> null
        }
        val chargeStatusCode = chargeStatusRaw?.toIntOrNull()
        val charging = when (chargeStatusRaw) {
            "1" -> true
            "-1", "0" -> false
            else -> (status.optDoubleOrNull("current") ?: 0.0) > 0.1
        }

        // 电压：status.voltage 是真实总电压，detail.volt 是额定值
        val voltage = status.optDoubleOrNull("voltage")
        val ratedVoltage = detail.optDoubleOrNull("volt")
        val current = status.optDoubleOrNull("current")

        // 单体电压列表 → 串数 / 压差（cellMaxVoltage 带 "/V" 单位）
        val cells = parseCellVoltages(status)
        val cellMax = cells.maxOrNull() ?: parseVoltageWithUnit(status.optString("cellMaxVoltage"))
        val cellMin = cells.minOrNull() ?: parseVoltageWithUnit(status.optString("cellMinVoltage"))
        val cellCount = cells.size.takeIf { it > 0 }
            ?: detail.optIntOrNull("cellCount")?.takeIf { it > 0 }
        val cellDiff = if (cellMax != null && cellMin != null) cellMax - cellMin else null
        val power = if (voltage != null && current != null) voltage * current else null

        // 续航 / 总里程（字符串带单位）
        val endurance = detail.optString("endurance").takeIf { it.isNotBlank() }
        val totalMileage = detail.optString("totalMileages").takeIf { it.isNotBlank() }

        // GPS 轨迹（最近一点）
        val lastLocation = runCatching { batteryTrack(config, batteryNo) }.getOrNull()

        BatterySnapshot(
            batteryNo = batteryNo,
            soc = when {
                detail.has("batteryLife") -> detail.optInt("batteryLife")
                else -> null
            },
            charging = charging,
            chargeStatus = chargeStatusCode,
            voltage = voltage,
            ratedVoltage = ratedVoltage,
            current = current,
            envTemp = status.optDoubleOrNull("envTemperature"),
            mosTemp = status.optDoubleOrNull("mosTemperature"),
            soh = detail.optIntOrNull("soh"),
            cycles = detail.optString("chargeCount").takeIf { it.isNotBlank() },
            remainCapacity = detail.optString("remainCapacity").takeIf { it.isNotBlank() },
            endurance = endurance,
            totalMileage = totalMileage,
            reportTime = detail.optString("batteryLifeTimestamp")
                .ifBlank { detail.optString("reportTime") }
                .takeIf { it.isNotBlank() },
            cellCount = cellCount,
            power = power,
            cellDiff = cellDiff,
            cellMax = cellMax,
            cellMin = cellMin,
            cellVoltages = cells,
            lastLocation = lastLocation
        )
    }

    /** GPS 轨迹：取最近一个轨迹点 */
    private fun batteryTrack(config: WatchConfig, batteryNo: String): GpsPoint? {
        val action = "swp.li.user.battery.track"
        val p = basePayload(action)
        p.put("batteryNo", batteryNo)
        auth(p, config)
        val data = post(action, p).opt("data") as? JSONObject ?: return null
        val tracks = data.optJSONArray("tracks") ?: return null
        if (tracks.length() == 0) return null
        val t = tracks.optJSONObject(tracks.length() - 1) ?: return null
        return GpsPoint(
            lng = t.optDoubleOrNull("lng"),
            lat = t.optDoubleOrNull("lat"),
            address = t.optString("address").takeIf { it.isNotBlank() },
            time = t.optString("locateTime").takeIf { it.isNotBlank() }
        )
    }

    /** 解析单体电压列表并统一成 mV */
    private fun parseCellVoltages(status: JSONObject): List<Double> {
        val arr = status.optJSONArray("cellVoltageList") ?: return emptyList()
        val out = mutableListOf<Double>()
        for (i in 0 until arr.length()) {
            val v = arr.optDouble(i, Double.NaN)
            if (!v.isNaN() && v > 0) out.add(toMv(v))
        }
        return out
    }

    /** 电压字符串可能带单位，如 "3.298/V"；统一取数值并转 mV */
    private fun parseVoltageWithUnit(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val num = s.split("/").first().trim().toDoubleOrNull() ?: return null
        return toMv(num)
    }

    /** 单位可能是 V(如 3.45) 或 mV(如 3450)，统一转 mV */
    private fun toMv(v: Double): Double = if (v < 100) v * 1000 else v

    private fun String.toIntOrNull(): Int? =
        runCatching { toInt() }.getOrNull()

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return runCatching { getDouble(key) }.getOrNull()
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return runCatching { getInt(key) }.getOrNull()
    }
}
