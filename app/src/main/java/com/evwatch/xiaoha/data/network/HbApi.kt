package com.evwatch.xiaoha.data.network

import android.util.Log
import com.evwatch.xiaoha.data.crypto.HbCrypto
import com.evwatch.xiaoha.data.model.ApiException
import com.evwatch.xiaoha.data.model.BatterySnapshot
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

        val chargeStatus = if (detail.has("chargeStatus")) detail.optInt("chargeStatus")
        else if (status.has("chargeStatus")) status.optInt("chargeStatus") else null

        BatterySnapshot(
            batteryNo = batteryNo,
            soc = when {
                detail.has("batteryLife") -> detail.optInt("batteryLife")
                else -> null
            },
            charging = isCharging(chargeStatus, status),
            chargeStatus = chargeStatus,
            voltage = status.optDoubleOrNull("voltage") ?: detail.optDoubleOrNull("volt"),
            current = status.optDoubleOrNull("current"),
            envTemp = status.optDoubleOrNull("envTemperature"),
            mosTemp = status.optDoubleOrNull("mosTemperature"),
            soh = detail.optIntOrNull("soh"),
            cycles = detail.optString("chargeCount").takeIf { it.isNotBlank() },
            remainCapacity = detail.optDoubleOrNull("remainCapacity"),
            reportTime = detail.optString("batteryLifeTimestamp")
                .ifBlank { detail.optString("reportTime") }
                .takeIf { it.isNotBlank() }
        )
    }

    private fun isCharging(chargeStatus: Int?, status: JSONObject): Boolean {
        if (chargeStatus != null) return chargeStatus == 1
        val cur = status.optDoubleOrNull("current")
        return cur != null && cur > 0.1
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return runCatching { getDouble(key) }.getOrNull()
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return runCatching { getInt(key) }.getOrNull()
    }
}
