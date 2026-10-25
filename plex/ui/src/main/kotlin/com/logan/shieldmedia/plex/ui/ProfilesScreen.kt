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
import com.logan.shieldmedia.designsystem.PinPad
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
                title = "Enter PIN for ${target.title}",
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
