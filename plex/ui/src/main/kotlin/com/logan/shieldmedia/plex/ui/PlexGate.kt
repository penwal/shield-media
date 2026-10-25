package com.logan.shieldmedia.plex.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.logan.shieldmedia.designsystem.ShieldListItem
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.LocalContentFocusGate
import com.logan.shieldmedia.designsystem.MessageState
import com.logan.shieldmedia.designsystem.PrimaryButton
import com.logan.shieldmedia.designsystem.ScreenTitle
import com.logan.shieldmedia.designsystem.SkeletonRow
import com.logan.shieldmedia.designsystem.Spacing
import com.logan.shieldmedia.plex.PlexLibrary
import com.logan.shieldmedia.plex.PlexSession
import com.logan.shieldmedia.plex.PlexState
import kotlinx.coroutines.launch

/**
 * Shows Plex content once signed in and connected; otherwise the right next step
 * (sign in, pick a server, or what went wrong).
 */
@Composable
fun PlexGate(
    session: PlexSession,
    library: PlexLibrary,
    onSignIn: () -> Unit,
    content: @Composable (PlexEnv) -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    when (val s = state) {
        PlexState.Loading -> Column(Modifier.fillMaxSize().padding(top = 160.dp), verticalArrangement = Arrangement.spacedBy(Spacing.RowGap)) {
            SkeletonRow(landscape = true)
            SkeletonRow()
        }
        PlexState.SignedOut -> FocusedAction {
            MessageState(
                title = "Connect your Plex server",
                body = "Sign in once and your movies and shows will appear here.",
                action = { PrimaryButton("Sign in with Plex", onClick = onSignIn, modifier = Modifier.focusRequester(it)) },
            )
        }
        is PlexState.Error -> FocusedAction {
            MessageState(
                title = "Can't connect to Plex",
                body = s.message,
                action = {
                    PrimaryButton(
                        "Try again",
                        onClick = { scope.launch { session.discoverServers() } },
                        modifier = Modifier.focusRequester(it),
                    )
                },
            )
        }
        is PlexState.ChooseServer -> ServerPicker(s) { resource -> scope.launch { session.connect(resource) } }
        is PlexState.Ready -> {
            val env = remember(s.server) { PlexEnv(library, s.server) }
            content(env)
        }
    }
}

/** Gives the single action on a message screen focus, so OK just works. */
@Composable
private fun FocusedAction(content: @Composable (FocusRequester) -> Unit) {
    val requester = remember { FocusRequester() }
    val gate = LocalContentFocusGate.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (gate()) runCatching { requester.requestFocus() }
    }
    content(requester)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServerPicker(state: PlexState.ChooseServer, onPick: (com.logan.shieldmedia.plex.PlexResource) -> Unit) {
    FocusedAction { first ->
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("Choose a server", "Your account can see more than one Plex Media Server.")
            LazyColumn(
                modifier = Modifier.padding(horizontal = Spacing.ScreenH).width(560.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.servers, key = { it.clientIdentifier }) { server ->
                    ShieldListItem(
                        selected = false,
                        onClick = { onPick(server) },
                        headlineContent = { Text(server.name) },
                        supportingContent = { Text(if (server.owned) "Your server" else "Shared with you") },
                        modifier = if (server == state.servers.first()) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
        }
    }
}
