package com.logan.shieldmedia.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.ScreenTitle
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.designsystem.Spacing
import com.logan.shieldmedia.plex.PlexSession
import com.logan.shieldmedia.plex.PlexState
import com.logan.shieldmedia.settings.PlayerSettings
import com.logan.shieldmedia.settings.SettingsRepository
import kotlinx.coroutines.launch

@Composable
fun PlaceholderScreen(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(title, subtitle)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    plexSession: PlexSession,
    onPlexSignIn: () -> Unit,
    onOpenIptvSources: () -> Unit,
    onOpenPlayerTest: () -> Unit,
) {
    val settings by repository.playerSettings.collectAsState(initial = PlayerSettings())
    val plexState by plexSession.state.collectAsState()
    val plexUser by produceState<String?>(null, plexState) {
        value = if (plexState is PlexState.Ready) plexSession.user()?.let { it.title ?: it.username } else null
    }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Settings")
        LazyColumn(
            Modifier.width(640.dp),
            contentPadding = PaddingValues(start = Spacing.ScreenH, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { SectionLabel("Plex") }
            when (val s = plexState) {
                is PlexState.Ready -> {
                    item {
                        ListItem(
                            selected = false,
                            onClick = { scope.launch { plexSession.discoverServers() } },
                            headlineContent = { Text("Server: ${s.server.name}") },
                            supportingContent = { Text(listOfNotNull(plexUser, "Select to switch servers").joinToString("  ·  ")) },
                        )
                    }
                    item {
                        ListItem(
                            selected = false,
                            onClick = { scope.launch { plexSession.signOut() } },
                            headlineContent = { Text("Sign out of Plex") },
                        )
                    }
                }
                PlexState.SignedOut -> item {
                    ListItem(
                        selected = false,
                        onClick = onPlexSignIn,
                        headlineContent = { Text("Sign in with Plex") },
                        supportingContent = { Text("Link this Shield to your Plex account") },
                    )
                }
                else -> item {
                    ListItem(
                        selected = false,
                        onClick = { scope.launch { plexSession.discoverServers() } },
                        headlineContent = { Text("Reconnect to Plex") },
                    )
                }
            }

            item { SectionLabel("Live TV") }
            item {
                ListItem(
                    selected = false,
                    onClick = onOpenIptvSources,
                    headlineContent = { Text("Live TV sources") },
                    supportingContent = { Text("Xtream Codes accounts and M3U playlists") },
                )
            }

            item { SectionLabel("Playback") }
            item {
                ListItem(
                    selected = true,
                    onClick = {},
                    headlineContent = { Text("Plex quality: Original") },
                    supportingContent = { Text("Always direct play the original file. Transcoding is only a last resort.") },
                )
            }
            item {
                SettingRow("Match display frame rate", settings.autoFrameRate) {
                    scope.launch { repository.setAutoFrameRate(it) }
                }
            }
            item {
                SettingRow("Audio passthrough (needs AV receiver)", settings.audioPassthrough) {
                    scope.launch { repository.setAudioPassthrough(it) }
                }
            }
            item {
                SettingRow("Show playback stats overlay", settings.showStatsOverlay) {
                    scope.launch { repository.setShowStatsOverlay(it) }
                }
            }

            item { SectionLabel("Developer") }
            item {
                ListItem(
                    selected = false,
                    onClick = onOpenPlayerTest,
                    headlineContent = { Text("Player test streams") },
                    supportingContent = { Text("Try codecs, subtitles and streaming formats") },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = ShieldColors.OnSurfaceFaint,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        selected = false,
        onClick = { onChange(!checked) },
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}
