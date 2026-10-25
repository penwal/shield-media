package com.logan.shieldmedia.plex.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.designsystem.SkeletonRow
import com.logan.shieldmedia.plex.PlexHomeUser
import com.logan.shieldmedia.plex.PlexSession
import kotlinx.coroutines.launch

/** "Who's watching?" — pick a Plex Home profile, with a PIN pad for protected ones. */
@Composable
fun PlexProfilesScreen(session: PlexSession, onDone: () -> Unit) {
    val users by produceState<List<PlexHomeUser>?>(null) { value = session.homeUsers() }
    val current by produceState<String?>(null) { value = session.currentProfileName() }
    var pinFor by remember { mutableStateOf<PlexHomeUser?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun switch(user: PlexHomeUser, pin: String?) {
        busy = true
        error = null
        scope.launch {
            val ok = runCatching { session.switchProfile(user, pin) }.getOrDefault(false)
            busy = false
            if (ok) onDone() else error = if (pin != null) "Wrong PIN" else "Couldn't switch profile"
        }
    }

    Box(Modifier.fillMaxSize().background(ShieldColors.Background), contentAlignment = Alignment.Center) {
        val target = pinFor
        when {
            users == null -> SkeletonRow()
            target != null -> PinPad(
                user = target,
                error = error,
                busy = busy,
                onSubmit = { switch(target, it) },
                onCancel = {
                    pinFor = null
                    error = null
                },
            )
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(48.dp)) {
                Text("Who's watching?", style = MaterialTheme.typography.displaySmall, color = ShieldColors.OnSurface)
                ProfileRow(
                    users = users.orEmpty(),
                    current = current,
                    onPick = { user -> if (user.protected) pinFor = user else switch(user, null) },
                )
                error?.let { Text(it, color = ShieldColors.Accent, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
    BackHandler(enabled = pinFor == null, onBack = onDone)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProfileRow(users: List<PlexHomeUser>, current: String?, onPick: (PlexHomeUser) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(users) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val focusTarget = users.firstOrNull { it.title == current } ?: users.firstOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        users.forEach { user ->
            Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Card(
                    onClick = { onPick(user) },
                    modifier = Modifier.size(132.dp).then(if (user == focusTarget) Modifier.focusRequester(first) else Modifier),
                    shape = CardDefaults.shape(CircleShape),
                    scale = CardDefaults.scale(focusedScale = 1.1f),
                    border = CardDefaults.border(
                        focusedBorder = Border(BorderStroke(3.dp, ShieldColors.FocusOutline), shape = CircleShape),
                    ),
                    colors = CardDefaults.colors(containerColor = ShieldColors.SurfaceBright),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(user.title.take(1).uppercase(), style = MaterialTheme.typography.displaySmall, color = ShieldColors.OnSurfaceMuted)
                        if (!user.thumb.isNullOrBlank()) {
                            AsyncImage(user.thumb, contentDescription = user.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                Row(
                    Modifier.padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(user.title, style = MaterialTheme.typography.titleMedium, color = ShieldColors.OnSurface, textAlign = TextAlign.Center)
                }
                Text(
                    when {
                        user.title == current -> "Current"
                        user.protected -> "PIN"
                        else -> " "
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (user.title == current) ShieldColors.Accent else ShieldColors.OnSurfaceFaint,
                )
            }
        }
    }
}

/** Four-digit PIN: number keys on the remote, or the on-screen pad. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PinPad(user: PlexHomeUser, error: String?, busy: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember(user) { mutableStateOf("") }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstKey.requestFocus() }
    }
    LaunchedEffect(error) { if (error != null) pin = "" }
    fun type(d: String) {
        if (busy || pin.length >= 4) return
        pin += d
        if (pin.length == 4) onSubmit(pin)
    }
    BackHandler(onBack = onCancel)

    Column(
        Modifier.onPreviewKeyEvent { e ->
            val digit = e.nativeKeyEvent.number.takeIf { it.isDigit() }
            if (e.type == KeyEventType.KeyDown && digit != null) {
                type(digit.toString()); true
            } else if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace) {
                pin = pin.dropLast(1); true
            } else {
                false
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Text("Enter PIN for ${user.title}", style = MaterialTheme.typography.headlineMedium, color = ShieldColors.OnSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier
                        .size(18.dp)
                        .then(
                            if (i < pin.length) Modifier.background(ShieldColors.OnSurface, CircleShape)
                            else Modifier.border(2.dp, ShieldColors.OnSurfaceFaint, CircleShape),
                        ),
                )
            }
        }
        Text(error ?: if (busy) "Checking…" else " ", color = if (error != null) ShieldColors.Accent else ShieldColors.OnSurfaceMuted)
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", ""))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEachIndexed { r, keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    keys.forEachIndexed { c, label ->
                        if (label.isEmpty()) {
                            Box(Modifier.size(84.dp, 64.dp))
                        } else {
                            Card(
                                onClick = { if (label == "⌫") pin = pin.dropLast(1) else type(label) },
                                modifier = Modifier.size(84.dp, 64.dp).then(if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier),
                                shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
                                scale = CardDefaults.scale(focusedScale = 1.06f),
                                colors = CardDefaults.colors(
                                    containerColor = ShieldColors.SurfaceHigh,
                                    focusedContainerColor = ShieldColors.OnSurface,
                                    contentColor = ShieldColors.OnSurface,
                                    focusedContentColor = ShieldColors.Background,
                                ),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(label, style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
