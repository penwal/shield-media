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
import com.logan.shieldmedia.designsystem.ShieldListItem
import com.logan.shieldmedia.designsystem.fadingEdges
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.PinPad
import com.logan.shieldmedia.designsystem.ScreenTitle
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.designsystem.Spacing
import com.logan.shieldmedia.plex.PlexSession
import com.logan.shieldmedia.iptv.IptvRepository
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.logan.shieldmedia.plex.PlexState
import com.logan.shieldmedia.settings.LiveBuffer
import com.logan.shieldmedia.settings.UiSize
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
    onPlexProfiles: () -> Unit,
    onOpenIptvSources: () -> Unit,
    onOpenPlayerTest: () -> Unit,
    iptv: IptvRepository? = null,
) {
    val settings by repository.playerSettings.collectAsState(initial = PlayerSettings())
    val plexState by plexSession.state.collectAsState()
    val plexUser by produceState<String?>(null, plexState) {
        value = if (plexState is PlexState.Ready) plexSession.currentProfileName() ?: plexSession.user()?.let { it.title ?: it.username } else null
    }
    val scope = rememberCoroutineScope()

    var hasPin by remember { mutableStateOf(iptv?.hasParentalPin == true) }
    var askPin by remember { mutableStateOf(false) }
    var pinWasAsked by remember { mutableStateOf(false) }
    val parentalRow = remember { FocusRequester() }
    // Coming back from the PIN pad, put focus back on the row that opened it.
    LaunchedEffect(askPin) {
        if (askPin) pinWasAsked = true
        if (!askPin && pinWasAsked) {
            withFrameNanos { }
            runCatching { parentalRow.requestFocus() }
        }
    }
    var pinError by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        // While the PIN pad is up, the list underneath can't take focus.
        Column(Modifier.fillMaxSize().focusProperties { onEnter = { if (askPin) cancelFocusChange() } }.focusGroup()) {
            ScreenTitle("Settings")
            LazyColumn(
                Modifier.width(640.dp).fadingEdges(),
                contentPadding = PaddingValues(start = Spacing.ScreenH, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { SectionLabel("Plex") }
                when (val s = plexState) {
                    is PlexState.Ready -> {
                        item {
                            ShieldListItem(
                                selected = false,
                                onClick = { scope.launch { plexSession.discoverServers() } },
                                headlineContent = { Text("Server: ${s.server.name}") },
                                supportingContent = { Text(listOfNotNull(plexUser, "Select to switch servers").joinToString("  ·  ")) },
                            )
                        }
                        item {
                            ShieldListItem(
                                selected = false,
                                onClick = onPlexProfiles,
                                headlineContent = { Text("Switch profile") },
                                supportingContent = { Text(plexUser?.let { "Watching as $it" } ?: "Plex Home users") },
                            )
                        }
                        item {
                            ShieldListItem(
                                selected = false,
                                onClick = { scope.launch { plexSession.signOut() } },
                                headlineContent = { Text("Sign out of Plex") },
                            )
                        }
                    }
                    PlexState.SignedOut -> item {
                        ShieldListItem(
                            selected = false,
                            onClick = onPlexSignIn,
                            headlineContent = { Text("Sign in with Plex") },
                            supportingContent = { Text("Link this Shield to your Plex account") },
                        )
                    }
                    else -> item {
                        ShieldListItem(
                            selected = false,
                            onClick = { scope.launch { plexSession.discoverServers() } },
                            headlineContent = { Text("Reconnect to Plex") },
                        )
                    }
                }

                item { SectionLabel("Live TV") }
                item {
                    ShieldListItem(
                        selected = false,
                        onClick = onOpenIptvSources,
                        headlineContent = { Text("Live TV sources") },
                        supportingContent = { Text("Xtream Codes accounts and M3U playlists") },
                    )
                }
                item {
                    // Cycles Fast -> Balanced -> Stable.
                    ShieldListItem(
                        selected = false,
                        onClick = {
                            val next = LiveBuffer.entries[(settings.liveBuffer.ordinal + 1) % LiveBuffer.entries.size]
                            scope.launch { repository.setLiveBuffer(next) }
                        },
                        headlineContent = { Text("Live TV buffer: ${settings.liveBuffer.label}") },
                        supportingContent = { Text(settings.liveBuffer.detail) },
                    )
                }

                if (iptv != null) {
                    item {
                        var upIsNext by remember { mutableStateOf(iptv.upIsNextChannel) }
                        SettingRow("Up button goes to the next channel", upIsNext) {
                            iptv.upIsNextChannel = it
                            upIsNext = it
                        }
                    }
                    item {
                        ShieldListItem(
                            selected = false,
                            // Turning the lock off needs the PIN, or it wouldn't be much of a lock.
                            onClick = { if (hasPin) askPin = true },
                            modifier = Modifier.focusRequester(parentalRow),
                            headlineContent = { Text(if (hasPin) "Remove parental PIN" else "Parental lock") },
                            supportingContent = {
                                Text(
                                    if (hasPin) "Turns the lock off and unlocks every group." else
                                        "In the guide, open Channel groups and hold OK on a group to lock it.",
                                )
                            },
                        )
                    }
                }

                item { SectionLabel("Display") }
            item {
                // Cycles Standard -> Compact -> Large.
                ShieldListItem(
                    selected = false,
                    onClick = {
                        val order = listOf(UiSize.Standard, UiSize.Compact, UiSize.Large)
                        val next = order[(order.indexOf(settings.uiSize) + 1) % order.size]
                        scope.launch { repository.setUiSize(next) }
                    },
                    headlineContent = { Text("Interface size: ${settings.uiSize.label}") },
                    supportingContent = { Text("Makes text, posters and menus smaller or larger") },
                )
            }

            item { SectionLabel("Playback") }
                item {
                    ShieldListItem(
                        selected = false,
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
                    ShieldListItem(
                        selected = false,
                        onClick = onOpenPlayerTest,
                        headlineContent = { Text("Player test streams") },
                        supportingContent = { Text("Try codecs, subtitles and streaming formats") },
                    )
                }
            }
        }
        if (askPin && iptv != null) {
            Box(Modifier.fillMaxSize().background(ShieldColors.Background), contentAlignment = Alignment.Center) {
                PinPad(
                    title = "Enter the parental PIN to remove it",
                    error = pinError,
                    busy = false,
                    onSubmit = { pin ->
                        if (iptv.unlock(pin)) {
                            iptv.setParentalPin(null)
                            hasPin = false
                            askPin = false
                            pinError = null
                        } else {
                            pinError = "Wrong PIN"
                        }
                    },
                    onCancel = {
                        askPin = false
                        pinError = null
                    },
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
    ShieldListItem(
        selected = false,
        onClick = { onChange(!checked) },
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}
