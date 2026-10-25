package com.logan.shieldmedia.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.ContentRow
import com.logan.shieldmedia.designsystem.MediaCard
import com.logan.shieldmedia.designsystem.ScreenTitle
import com.logan.shieldmedia.settings.PlayerSettings
import com.logan.shieldmedia.settings.SettingsRepository
import kotlinx.coroutines.launch

private val sampleTitles = listOf(
    "Movie One", "Movie Two", "Movie Three", "Movie Four", "Movie Five",
    "Movie Six", "Movie Seven", "Movie Eight",
)

@Composable
fun HomeScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Home", "Placeholder content until Plex is connected (milestone 3)")
        ContentRow("Continue Watching", sampleTitles) { MediaCard(title = it) }
        ContentRow("Recently Added", sampleTitles.reversed(), Modifier.padding(top = 16.dp)) {
            MediaCard(title = it)
        }
    }
}

@Composable
fun PlaceholderScreen(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(title, subtitle)
    }
}

@Composable
fun SettingsScreen(repository: SettingsRepository) {
    val settings by repository.playerSettings.collectAsState(initial = PlayerSettings())
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Settings", "Player")
        Column(
            Modifier.padding(horizontal = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingRow("Match display frame rate", settings.autoFrameRate) {
                scope.launch { repository.setAutoFrameRate(it) }
            }
            SettingRow("Audio passthrough (needs AV receiver)", settings.audioPassthrough) {
                scope.launch { repository.setAudioPassthrough(it) }
            }
            SettingRow("Show playback stats overlay", settings.showStatsOverlay) {
                scope.launch { repository.setShowStatsOverlay(it) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        selected = false,
        onClick = { onChange(!checked) },
        headlineContent = { Text(label, style = MaterialTheme.typography.titleMedium) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.fillMaxWidth(0.6f),
    )
}
