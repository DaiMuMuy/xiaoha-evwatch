package com.evwatch.xiaoha.data.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.evwatch.xiaoha.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 通知：本地通知 + 钉钉/企业微信机器人推送
 * 推送逻辑与 evwatch 的 notify.py 对齐：
 *  - 钉钉正文必须含关键词「电池」，否则自动补前缀
 *  - 钉钉支持加签（timestamp + HmacSHA256）
 */
object Notifier {

    private const val CHANNEL_ID = "battery_alert"
    private const val KEYWORD = "电池"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "电池提醒",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = "电量、充电状态提醒" }
                )
            }
        }
    }

    fun local(context: Context, title: String, text: String) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notif)
        }
    }

    /** 钉钉优先，其次企业微信 */
    suspend fun push(
        context: Context,
        text: String,
        dingWebhook: String,
        dingSecret: String,
        wecomWebhook: String
    ): Boolean = withContext(Dispatchers.IO) {
        var ok = false
        if (dingWebhook.isNotBlank()) {
            ok = runCatching { dingtalk(text, dingWebhook, dingSecret) }.getOrDefault(false) || ok
        }
        if (wecomWebhook.isNotBlank()) {
            ok = runCatching { wecom(text, wecomWebhook) }.getOrDefault(false) || ok
        }
        if (!ok) local(context, "小哈电巡", text)
        ok
    }

    private fun dingtalk(text: String, webhook: String, secret: String): Boolean {
        var content = text
        if (!content.contains(KEYWORD)) content = "【$KEYWORD】$content"
        val url = if (secret.isBlank()) webhook else signedUrl(webhook, secret)
        val body = JSONObject().apply {
            put("msgtype", "text")
            put("text", JSONObject().put("content", content))
        }
        val res = postJson(url, body.toString())
        val json = JSONObject(res)
        return json.optInt("errcode", -1) == 0
    }

    private fun wecom(text: String, webhook: String): Boolean {
        val body = JSONObject().apply {
            put("msgtype", "text")
            put("text", JSONObject().put("content", text))
        }
        val res = postJson(webhook, body.toString())
        return JSONObject(res).optInt("errcode", -1) == 0
    }

    private fun signedUrl(webhook: String, secret: String): String {
        val ts = System.currentTimeMillis().toString()
        val toSign = "$ts\n$secret"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val raw = mac.doFinal(toSign.toByteArray())
        val sign = URLEncoder.encode(
            String(Base64.getEncoder().encode(raw), Charsets.UTF_8), "UTF-8"
        )
        val sep = if (webhook.contains("?")) "&" else "?"
        return "$webhook${sep}timestamp=$ts&sign=$sign"
    }

    private fun postJson(url: String, body: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 10000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val out = stream.use {
            val bos = ByteArrayOutputStream()
            it.copyTo(bos)
            bos.toByteArray()
        }
        conn.disconnect()
        if (code !in 200..299) throw Exception("HTTP $code")
        return String(out, Charsets.UTF_8)
    }
}
