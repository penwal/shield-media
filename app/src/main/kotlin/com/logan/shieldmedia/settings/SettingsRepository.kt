package com.logan.shieldmedia.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class PlayerSettings(
    val autoFrameRate: Boolean = true,
    val audioPassthrough: Boolean = false,
    val showStatsOverlay: Boolean = false,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val AutoFrameRate = booleanPreferencesKey("auto_frame_rate")
        val AudioPassthrough = booleanPreferencesKey("audio_passthrough")
        val StatsOverlay = booleanPreferencesKey("stats_overlay")
    }

    val playerSettings: Flow<PlayerSettings> = context.dataStore.data.map { p ->
        PlayerSettings(
            autoFrameRate = p[Keys.AutoFrameRate] ?: true,
            audioPassthrough = p[Keys.AudioPassthrough] ?: false,
            showStatsOverlay = p[Keys.StatsOverlay] ?: false,
        )
    }

    suspend fun setAutoFrameRate(value: Boolean) {
        context.dataStore.edit { it[Keys.AutoFrameRate] = value }
    }

    suspend fun setAudioPassthrough(value: Boolean) {
        context.dataStore.edit { it[Keys.AudioPassthrough] = value }
    }

    suspend fun setShowStatsOverlay(value: Boolean) {
        context.dataStore.edit { it[Keys.StatsOverlay] = value }
    }
}
