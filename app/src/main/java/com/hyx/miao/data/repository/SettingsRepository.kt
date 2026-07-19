package com.hyx.miao.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hyx.miao.data.remote.api.UserApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "miao_settings")

data class AppSettings(
    val modelProvider: String = "anthropic",
    val modelId: String = "claude-haiku-4-5-20251001",
    val temperature: Float = 0.8f,
    val maxTokens: Int = 4096,
    val contextTurns: Int = 20,
    val streaming: Boolean = true,
    val darkMode: String = "system",
    val fontSize: String = "medium",
    val sendOnEnter: Boolean = false,
    val hapticFeedback: Boolean = true,
    val themeColor: String = "purple",
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val userApi: UserApi,
) {
    private val store = context.dataStore
    private val mutex = Mutex()

    val settings: Flow<AppSettings> = store.data.map(::fromPreferences)

    suspend fun syncFromCloud(): Result<AppSettings> = runCatching {
        mutex.withLock {
            val response = userApi.getSettings()
            val remote = response.settings
            if (remote.isNotEmpty()) {
                val merged = fromCloud(remote, settings.first())
                store.edit { write(it, merged) }
                merged
            } else {
                val local = settings.first()
                userApi.updateSettings(mapOf("settings" to local.toCloudMap()))
                local
            }
        }
    }

    suspend fun update(block: suspend (AppSettings) -> AppSettings): Result<AppSettings> = runCatching {
        mutex.withLock {
            val updated = block(settings.first())
            store.edit { write(it, updated) }
            userApi.updateSettings(mapOf("settings" to updated.toCloudMap()))
            updated
        }
    }

    private fun fromPreferences(prefs: Preferences) = AppSettings(
        modelProvider = prefs[Keys.MODEL_PROVIDER] ?: "anthropic",
        modelId = prefs[Keys.MODEL_ID] ?: "claude-haiku-4-5-20251001",
        temperature = prefs[Keys.TEMPERATURE] ?: 0.8f,
        maxTokens = prefs[Keys.MAX_TOKENS] ?: 4096,
        contextTurns = prefs[Keys.CONTEXT_TURNS] ?: 20,
        streaming = prefs[Keys.STREAMING] ?: true,
        darkMode = prefs[Keys.DARK_MODE] ?: "system",
        fontSize = prefs[Keys.FONT_SIZE] ?: "medium",
        sendOnEnter = prefs[Keys.SEND_ON_ENTER] ?: false,
        hapticFeedback = prefs[Keys.HAPTIC_FEEDBACK] ?: true,
        themeColor = prefs[Keys.THEME_COLOR] ?: "purple",
    )

    private fun fromCloud(map: Map<String, Any?>, fallback: AppSettings) = AppSettings(
        modelProvider = map.string("modelProvider") ?: fallback.modelProvider,
        modelId = map.string("modelId") ?: fallback.modelId,
        temperature = map.number("temperature")?.toFloat()?.coerceIn(0f, 2f) ?: fallback.temperature,
        maxTokens = map.number("maxTokens")?.toInt()?.coerceIn(256, 8192) ?: fallback.maxTokens,
        contextTurns = map.number("contextTurns")?.toInt()?.coerceIn(1, 50) ?: fallback.contextTurns,
        streaming = map.boolean("streaming") ?: fallback.streaming,
        darkMode = map.string("darkMode")?.takeIf { it in setOf("system", "light", "dark") } ?: fallback.darkMode,
        fontSize = map.string("fontSize")?.takeIf { it in setOf("small", "medium", "large") } ?: fallback.fontSize,
        sendOnEnter = map.boolean("sendOnEnter") ?: fallback.sendOnEnter,
        hapticFeedback = map.boolean("hapticFeedback") ?: fallback.hapticFeedback,
        themeColor = map.string("themeColor")?.takeIf { it in setOf("purple", "pink", "blue", "green", "orange", "red") } ?: fallback.themeColor,
    )

    private fun write(prefs: androidx.datastore.preferences.core.MutablePreferences, value: AppSettings) {
        prefs[Keys.MODEL_PROVIDER] = value.modelProvider
        prefs[Keys.MODEL_ID] = value.modelId
        prefs[Keys.TEMPERATURE] = value.temperature
        prefs[Keys.MAX_TOKENS] = value.maxTokens
        prefs[Keys.CONTEXT_TURNS] = value.contextTurns
        prefs[Keys.STREAMING] = value.streaming
        prefs[Keys.DARK_MODE] = value.darkMode
        prefs[Keys.FONT_SIZE] = value.fontSize
        prefs[Keys.SEND_ON_ENTER] = value.sendOnEnter
        prefs[Keys.HAPTIC_FEEDBACK] = value.hapticFeedback
        prefs[Keys.THEME_COLOR] = value.themeColor
    }

    private fun AppSettings.toCloudMap(): Map<String, Any> = mapOf(
        "modelProvider" to modelProvider,
        "modelId" to modelId,
        "temperature" to temperature,
        "maxTokens" to maxTokens,
        "contextTurns" to contextTurns,
        "streaming" to streaming,
        "darkMode" to darkMode,
        "fontSize" to fontSize,
        "sendOnEnter" to sendOnEnter,
        "hapticFeedback" to hapticFeedback,
        "themeColor" to themeColor,
    )

    private fun Map<String, Any?>.string(key: String) = this[key] as? String
    private fun Map<String, Any?>.number(key: String) = this[key] as? Number
    private fun Map<String, Any?>.boolean(key: String) = this[key] as? Boolean

    private object Keys {
        val MODEL_PROVIDER = stringPreferencesKey("model_provider")
        val MODEL_ID = stringPreferencesKey("model_id")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val CONTEXT_TURNS = intPreferencesKey("context_turns")
        val STREAMING = booleanPreferencesKey("streaming")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val FONT_SIZE = stringPreferencesKey("font_size")
        val SEND_ON_ENTER = booleanPreferencesKey("send_on_enter")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val THEME_COLOR = stringPreferencesKey("theme_color")
    }
}
