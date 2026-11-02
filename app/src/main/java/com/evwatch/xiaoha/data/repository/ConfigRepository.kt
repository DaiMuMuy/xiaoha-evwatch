package com.evwatch.xiaoha.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.evwatch.xiaoha.data.model.WatchConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "evwatch_config")

class ConfigRepository(private val context: Context) {

    private val batteryNoKey = stringPreferencesKey("battery_no")
    private val tokenKey = stringPreferencesKey("token")
    private val ticketKey = stringPreferencesKey("ticket")
    private val dingWebhookKey = stringPreferencesKey("ding_webhook")
    private val dingSecretKey = stringPreferencesKey("ding_secret")
    private val wecomKey = stringPreferencesKey("wecom_webhook")
    private val pollKey = intPreferencesKey("poll_seconds")
    private val lowKey = intPreferencesKey("low_percent")
    private val milestonesKey = stringPreferencesKey("milestones")

    val configFlow: Flow<WatchConfig> = context.dataStore.data.map { p ->
        WatchConfig(
            batteryNo = p[batteryNoKey] ?: "",
            token = p[tokenKey] ?: "",
            ticket = p[ticketKey] ?: "",
            dingWebhook = p[dingWebhookKey] ?: "",
            dingSecret = p[dingSecretKey] ?: "",
            wecomWebhook = p[wecomKey] ?: "",
            pollSeconds = p[pollKey] ?: DEFAULT_POLL,
            lowPercent = p[lowKey] ?: DEFAULT_LOW,
            milestones = p[milestonesKey] ?: DEFAULT_MILESTONES
        )
    }

    suspend fun save(config: WatchConfig) {
        context.dataStore.edit { p ->
            p[batteryNoKey] = config.batteryNo.trim()
            p[tokenKey] = config.token.trim()
            p[ticketKey] = config.ticket.trim()
            p[dingWebhookKey] = config.dingWebhook.trim()
            p[dingSecretKey] = config.dingSecret.trim()
            p[wecomKey] = config.wecomWebhook.trim()
            p[pollKey] = config.pollSeconds.coerceIn(MIN_POLL, MAX_POLL)
            p[lowKey] = config.lowPercent.coerceIn(1, 99)
            p[milestonesKey] = config.milestones
        }
    }

    companion object {
        const val DEFAULT_POLL = 60
        const val MIN_POLL = 20
        const val MAX_POLL = 3600
        const val DEFAULT_LOW = 20
        const val DEFAULT_MILESTONES = "80,90,100"
    }
}
