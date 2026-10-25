package com.logan.shieldmedia.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class PlayerSettings(
    val autoFrameRate: Boolean = true,
    val audioPassthrough: Boolean = false,
    val showStatsOverlay: Boolean = false,
    val liveBuffer: LiveBuffer = LiveBuffer.Balanced,
    val uiSize: UiSize = UiSize.Standard,
)

/** Overall interface size. Everything (text, cards, menus) scales together. */
enum class UiSize(val label: String, val scale: Float) {
    Compact("Compact", 0.75f),
    Standard("Standard", 0.85f),
    Large("Large", 1.0f),
}

enum class LiveBuffer(val label: String, val millis: Int, val detail: String) {
    Fast("Fast", 1_000, "Quickest channel changes; may stutter on weaker streams"),
    Balanced("Balanced", 3_000, "Good for most providers"),
    Stable("Stable", 6_000, "Slower channel changes; rides out unreliable streams"),
}

class SettingsRepository(private val context: Context) {
    private object Keys {
        val AutoFrameRate = booleanPreferencesKey("auto_frame_rate")
        val AudioPassthrough = booleanPreferencesKey("audio_passthrough")
        val StatsOverlay = booleanPreferencesKey("stats_overlay")
        val LiveBufferKey = intPreferencesKey("live_buffer")
        val UiSizeKey = intPreferencesKey("ui_size")
    }

    val playerSettings: Flow<PlayerSettings> = context.dataStore.data.map { p ->
        PlayerSettings(
            autoFrameRate = p[Keys.AutoFrameRate] ?: true,
            audioPassthrough = p[Keys.AudioPassthrough] ?: false,
            showStatsOverlay = p[Keys.StatsOverlay] ?: false,
            liveBuffer = LiveBuffer.entries.getOrNull(p[Keys.LiveBufferKey] ?: -1) ?: LiveBuffer.Balanced,
            uiSize = UiSize.entries.getOrNull(p[Keys.UiSizeKey] ?: -1) ?: UiSize.Standard,
        )
    }

    suspend fun setAutoFrameRate(value: Boolean) {
        context.dataStore.edit { it[Keys.AutoFrameRate] = value }
    }

    suspend fun setAudioPassthrough(value: Boolean) {
        context.dataStore.edit { it[Keys.AudioPassthrough] = value }
    }

    suspend fun setUiSize(value: UiSize) {
        context.dataStore.edit { it[Keys.UiSizeKey] = value.ordinal }
    }

    suspend fun setLiveBuffer(value: LiveBuffer) {
        context.dataStore.edit { it[Keys.LiveBufferKey] = value.ordinal }
    }

    suspend fun setShowStatsOverlay(value: Boolean) {
        context.dataStore.edit { it[Keys.StatsOverlay] = value }
    }
}
