package com.logan.shieldmedia.iptv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.logan.shieldmedia.designsystem.ShieldChip
import com.logan.shieldmedia.designsystem.ShieldListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.PrimaryButton
import com.logan.shieldmedia.designsystem.ScreenTitle
import com.logan.shieldmedia.designsystem.SecondaryButton
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.designsystem.ShieldIcon
import com.logan.shieldmedia.designsystem.ShieldIcons
import com.logan.shieldmedia.designsystem.Spacing
import com.logan.shieldmedia.designsystem.TvTextField
import com.logan.shieldmedia.iptv.IptvRepository
import com.logan.shieldmedia.iptv.Playlist
import com.logan.shieldmedia.iptv.PlaylistType
import com.logan.shieldmedia.iptv.SyncState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun IptvSourcesScreen(repo: IptvRepository, onAdd: () -> Unit) {
    val playlists by repo.playlists.collectAsStateWithLifecycle()
    val active by repo.activePlaylistId.collectAsStateWithLifecycle()
    val sync by repo.syncState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Live TV sources", syncLine(sync) ?: "Xtream Codes accounts and M3U playlists")
        LazyColumn(
            Modifier.width(720.dp),
            contentPadding = PaddingValues(start = Spacing.ScreenH, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                ShieldListItem(
                    selected = false,
                    onClick = onAdd,
                    headlineContent = { Text("Add a source") },
                    supportingContent = { Text("Xtream Codes login or M3U playlist URL") },
                    modifier = Modifier.focusRequester(first),
                )
            }
            items(playlists, key = { it.id }) { p ->
                ShieldListItem(
                    selected = p.id == active,
                    onClick = { repo.setActive(p.id) },
                    onLongClick = { scope.launch { repo.sync(p.id, force = true) } },
                    headlineContent = { Text(p.name) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                if (p.type == PlaylistType.XTREAM) "Xtream Codes" else "M3U",
                                "${p.channelCount} channels",
                                p.lastSync.takeIf { it > 0 }?.let { "updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}" },
                            ).joinToString("  ·  "),
                        )
                    },
                    trailingContent = if (p.id == active) {
                        { ShieldIcon(ShieldIcons.Check, contentDescription = "In use", size = 20.dp) }
                    } else {
                        null
                    },
                )
            }
            if (playlists.isNotEmpty()) {
                item {
                    Text(
                        "Select a source to use it for Live TV. Long-press to refresh it now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ShieldColors.OnSurfaceFaint,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                    )
                }
                item {
                    Row(Modifier.padding(start = 8.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        playlists.firstOrNull { it.id == active }?.let { current ->
                            SecondaryButton("Refresh now", onClick = { scope.launch { repo.sync(current.id, force = true) } })
                            SecondaryButton("Remove \"${current.name}\"", onClick = { scope.launch { repo.deletePlaylist(current.id) } })
                        }
                    }
                }
            }
        }
    }
}

private fun syncLine(sync: SyncState): String? = when (sync) {
    SyncState.Idle -> null
    is SyncState.Running -> "${sync.playlist}: ${sync.step}…"
    is SyncState.Failed -> "${sync.playlist}: ${sync.message}"
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddIptvSourceScreen(repo: IptvRepository, onDone: () -> Unit) {
    var type by remember { mutableStateOf(PlaylistType.XTREAM) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val sync by repo.syncState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    fun save() {
        val cleanUrl = url.trim().let { if (it.startsWith("http")) it else "http://$it" }
        val playlist = Playlist(
            name = name.trim().ifEmpty { if (type == PlaylistType.XTREAM) "Xtream" else "Playlist" },
            type = type,
            url = cleanUrl,
            username = username.trim().takeIf { type == PlaylistType.XTREAM },
            password = password.takeIf { type == PlaylistType.XTREAM },
            epgUrl = epgUrl.trim().ifEmpty { null },
        )
        busy = true
        error = null
        scope.launch {
            repo.addPlaylist(playlist)
                .onSuccess { onDone() }
                .onFailure { error = it.message ?: "Couldn't add this source." }
            busy = false
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 64.dp),
    ) {
        item { ScreenTitle("Add a source", "Your provider's details are stored only on this device.") }
        item {
            Row(Modifier.padding(horizontal = Spacing.ScreenH, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShieldChip(
                    selected = type == PlaylistType.XTREAM,
                    onClick = { type = PlaylistType.XTREAM },
                    modifier = Modifier.focusRequester(first),
                ) { Text("Xtream Codes") }
                ShieldChip(selected = type == PlaylistType.M3U, onClick = { type = PlaylistType.M3U }) { Text("M3U playlist") }
            }
        }
        item {
            Column(
                Modifier.padding(horizontal = Spacing.ScreenH, vertical = 12.dp).width(620.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                TvTextField("Name", name, { name = it }, placeholder = "e.g. My provider")
                if (type == PlaylistType.XTREAM) {
                    TvTextField("Server", url, { url = it }, placeholder = "http://example.com:8080", keyboardType = KeyboardType.Uri)
                    TvTextField("Username", username, { username = it })
                    TvTextField("Password", password, { password = it }, password = true)
                } else {
                    TvTextField("Playlist URL", url, { url = it }, placeholder = "http://…/playlist.m3u", keyboardType = KeyboardType.Uri)
                }
                TvTextField(
                    "TV guide URL (optional)",
                    epgUrl,
                    { epgUrl = it },
                    placeholder = "Uses the source's own guide when left empty",
                    keyboardType = KeyboardType.Uri,
                )
                val status = error ?: (sync as? SyncState.Running)?.step?.takeIf { busy }
                if (status != null) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (error != null) ShieldColors.Accent else ShieldColors.OnSurfaceMuted,
                    )
                }
                // Moving down out of the form lands on the main action, never on Cancel.
                Row(
                    Modifier.focusProperties { onEnter = { addFocus.requestFocus() } }.focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PrimaryButton(
                        text = if (busy) "Connecting…" else "Add source",
                        onClick = { if (!busy && url.isNotBlank()) save() },
                        modifier = Modifier.focusRequester(addFocus),
                    )
                    SecondaryButton("Cancel", onClick = onDone)
                }
            }
        }
    }
}
