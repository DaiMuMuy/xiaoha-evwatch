package com.evwatch.xiaoha.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.evwatch.xiaoha.data.model.ApiException
import com.evwatch.xiaoha.data.model.BatterySnapshot
import com.evwatch.xiaoha.data.model.WatchConfig
import com.evwatch.xiaoha.data.network.HbApi
import com.evwatch.xiaoha.data.notify.Notifier
import com.evwatch.xiaoha.data.repository.ConfigRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 监控逻辑：定时拉快照 + 里程碑/低电量/充电起停提醒
 * 状态机与 evwatch 的 battery_watch.py 保持一致：
 *  - 充电开始/结束提醒
 *  - 电量到达里程碑（默认 80/90/100）提醒，同一次充电内只提醒一次
 *  - 低于阈值（默认 20%）提醒，同一次放电内只提醒一次
 */
class MainViewModel(
    private val appContext: Context,
    private val repository: ConfigRepository,
    private val api: HbApi
) : ViewModel() {

    var config by mutableStateOf(WatchConfig())
        private set
    var snapshot by mutableStateOf<BatterySnapshot?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var log by mutableStateOf("")
        private set
    var running by mutableStateOf(false)
        private set

    private var pollJob: Job? = null
    private val prefs = appContext.getSharedPreferences("evwatch_state", Context.MODE_PRIVATE)

    init {
        viewModelScope.launch {
            repository.configFlow.collect { c ->
                config = c
                if (c.isConfigured && snapshot == null) {
                    refresh()
                }
            }
        }
        Notifier.ensureChannel(appContext)
    }

    fun todayCharging(): Boolean = snapshot?.charging ?: false

    fun onConfigChange(newConfig: WatchConfig) {
        config = newConfig
    }

    fun saveConfig() {
        viewModelScope.launch {
            repository.save(config)
            error = null
            appendLog("配置已保存")
        }
    }

    fun refresh() {
        val cfg = config
        if (!cfg.isConfigured) {
            error = "请先填写 Token 和 Ticket"
            return
        }
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val snap = api.snapshot(cfg)
                snapshot = snap
                appendLog(formatSnapshot(snap))
                evaluateAlerts(snap)
            } catch (e: ApiException) {
                error = if (e.isLoginExpired) "登录信息已失效（code=103），请重新抓包更新 Token / Ticket"
                else "接口错误：${e.message}"
                appendLog(error!!)
            } catch (e: Exception) {
                error = "请求失败：${e.message}"
                appendLog(error!!)
            } finally {
                loading = false
            }
        }
    }

    fun startMonitoring() {
        if (pollJob?.isActive == true) return
        running = true
        pollJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                val wait = (config.pollSeconds.coerceIn(20, 3600)) * 1000L
                delay(wait)
            }
        }
    }

    fun stopMonitoring() {
        running = false
        pollJob?.cancel()
        pollJob = null
    }

    override fun onCleared() {
        stopMonitoring()
        super.onCleared()
    }

    // ---------------- 告警状态机 ----------------

    private fun evaluateAlerts(snap: BatterySnapshot) {
        val cfg = config
        val prevCharging = prefs.getBoolean("charging", false)
        val hasPrev = prefs.getBoolean("has_prev", false)
        val lastSoc = prefs.getInt("last_soc", -1)
        val soc = snap.soc

        val messages = mutableListOf<String>()

        if (hasPrev && prevCharging != snap.charging) {
            if (snap.charging) {
                messages += "【电池开始充电】电池 ${snap.batteryNo} 当前 ${soc ?: "-"}%"
                // 新的充电周期：清空里程碑记录
                prefs.edit().putString("fired_milestones", "").apply()
            } else {
                val mins = prefs.getLong("charge_start", 0L).let {
                    if (it > 0) ((System.currentTimeMillis() - it) / 60000L) else null
                }
                messages += if (lastSoc >= 0)
                    "【电池充电结束】电池 ${snap.batteryNo} 从 $lastSoc% 到 ${soc ?: "-"}%" +
                        (mins?.let { "，本次充电约 $it 分钟" } ?: "")
                else "【电池充电结束】电池 ${snap.batteryNo} 当前 ${soc ?: "-"}%"
                prefs.edit().putString("fired_low", "").apply()
            }
        }
        if (snap.charging) prefs.edit().putLong("charge_start", System.currentTimeMillis()).apply()

        if (soc != null) {
            // 里程碑
            val fired = prefs.getString("fired_milestones", "").orEmpty().split(',').filter { it.isNotEmpty() }
            val newly = cfg.milestoneList.filter { m -> soc >= m && fired.none { it == m.toString() } }
            if (newly.isNotEmpty() && snap.charging) {
                val m = newly.last()
                messages += "【电池电量提醒】电池 ${snap.batteryNo} 已充到 $soc%（达成 $m%）"
                prefs.edit()
                    .putString("fired_milestones", (fired + newly.map { it.toString() }).joinToString(","))
                    .apply()
            }
            // 低电量
            val lowFired = prefs.getBoolean("fired_low", false)
            if (!lowFired && !snap.charging && soc <= cfg.lowPercent) {
                messages += "【电池低电量】电池 ${snap.batteryNo} 电量 ${soc}%，低于 ${cfg.lowPercent}%"
                prefs.edit().putBoolean("fired_low", true).apply()
            } else if (lowFired && soc > cfg.lowPercent + 5) {
                prefs.edit().putBoolean("fired_low", false).apply()
            }
        }

        prefs.edit()
            .putBoolean("charging", snap.charging)
            .putBoolean("has_prev", true)
            .putInt("last_soc", soc ?: lastSoc)
            .apply()

        messages.forEach { msg ->
            appendLog("🔔 $msg")
            viewModelScope.launch {
                Notifier.push(
                    appContext, msg,
                    cfg.dingWebhook, cfg.dingSecret, cfg.wecomWebhook
                )
            }
        }
    }

    private fun formatSnapshot(s: BatterySnapshot): String = buildString {
        append("[${timeNow()}] ")
        append("${s.batteryNo} ")
        append("SOC ${s.soc ?: "-"}% ")
        append(if (s.charging) "充电中 " else "未充电 ")
        s.voltage?.let { append(String.format(Locale.US, "%.1fV ", it)) }
        s.current?.let { append(String.format(Locale.US, "%.1fA ", it)) }
        s.mosTemp?.let { append("MOS ${it.toInt()}℃ ") }
        s.reportTime?.let { append("上报 $it") }
    }

    private fun timeNow(): String =
        java.text.SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(java.util.Date())

    private fun appendLog(line: String) {
        log = (line + "\n" + log).lines().take(120).joinToString("\n")
    }
}
