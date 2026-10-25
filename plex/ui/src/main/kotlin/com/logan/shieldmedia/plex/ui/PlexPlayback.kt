package com.logan.shieldmedia.plex.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.logan.shieldmedia.designsystem.MessageState
import com.logan.shieldmedia.plex.PlexItem
import com.logan.shieldmedia.player.PlaybackConfig
import com.logan.shieldmedia.player.PlaybackReport
import com.logan.shieldmedia.player.PlaybackSession
import com.logan.shieldmedia.player.PlayerScreen
import com.logan.shieldmedia.player.SkipMarker
import com.logan.shieldmedia.player.UpNext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/** Reports run here so the final "stopped" still reaches the server after the player closes. */
private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal class PlexPlaybackSession(
    private val env: PlexEnv,
    private val item: PlexItem,
    private val onPlayNext: (PlexItem) -> Unit,
) : PlaybackSession {
    private var next: PlexItem? = null
    private val sessionId = UUID.randomUUID().toString()

    override val markers: List<SkipMarker> = item.markers.mapNotNull { m ->
        when (m.type) {
            "intro" -> SkipMarker("Skip Intro", m.startTimeOffset, m.endTimeOffset, isCredits = false)
            "credits" -> SkipMarker("Skip Credits", m.startTimeOffset, m.endTimeOffset, isCredits = true)
            else -> null
        }
    }.sortedBy { it.startMs }

    override fun report(state: PlaybackReport, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        // Plex rejects (HTTP 400) a position beyond the duration, e.g. after seeking past the end.
        val time = positionMs.coerceIn(0, durationMs)
        val plexState = when (state) {
            PlaybackReport.Playing -> "playing"
            PlaybackReport.Paused -> "paused"
            PlaybackReport.Stopped -> "stopped"
        }
        reportScope.launch {
            runCatching { env.library.timeline(env.server, item, plexState, time, durationMs) }
                .onFailure { Log.w("PlexPlayback", "Timeline report failed", it) }
        }
    }

    override suspend fun loadUpNext(): UpNext? {
        if (item.type != "episode") return null
        val episode = runCatching { env.library.nextEpisode(env.server, item) }.getOrNull() ?: return null
        next = episode
        return UpNext(
            title = listOfNotNull(episode.episodeLabel, episode.title).joinToString("  ·  "),
            subtitle = episode.grandparentTitle,
            imageUrl = env.landscape(episode),
        )
    }

    override fun playUpNext() {
        next?.let(onPlayNext)
    }

    override suspend fun fallbackUri(positionMs: Long): String? {
        Log.i("PlexPlayback", "Direct play failed for ${item.ratingKey}; asking Plex to convert from ${positionMs}ms")
        return env.library.transcodeUrl(env.server, item, positionMs, sessionId)
    }
}

/**
 * Plays a Plex item by rating key: loads it with its intro/credits markers, direct-plays the
 * original file, keeps the server informed, and chains straight into the next episode.
 */
@Composable
fun PlexPlayerRoute(
    env: PlexEnv,
    ratingKey: String,
    fromStart: Boolean,
    config: PlaybackConfig,
    onExit: () -> Unit,
    theater: Boolean = false,
) {
    var currentKey by remember { mutableStateOf(ratingKey) }
    var startFromBeginning by remember { mutableStateOf(fromStart) }

    key(currentKey) {
        val item by produceState<Result<PlexItem>?>(null, currentKey) {
            value = runCatching { env.library.item(env.server, currentKey) ?: error("This item is no longer on the server.") }
        }
        val loaded = item
        when {
            loaded == null -> Box(Modifier.fillMaxSize().background(Color.Black))
            loaded.isFailure -> MessageState("Can't play this", loaded.exceptionOrNull()?.message)
            else -> {
                val plexItem = loaded.getOrThrow()
                val part = plexItem.media.firstOrNull()?.parts?.firstOrNull()
                if (part == null) {
                    MessageState("Can't play this", "Plex has no playable file for this item.")
                } else {
                    val session = remember(plexItem) {
                        PlexPlaybackSession(env, plexItem) { nextItem ->
                            startFromBeginning = (nextItem.viewOffset ?: 0) == 0L
                            currentKey = nextItem.ratingKey
                        }
                    }
                    PlayerScreen(
                        uri = env.library.directPlayUrl(env.server, part),
                        title = plexItem.playerTitle(),
                        config = config,
                        onExit = onExit,
                        startPositionMs = if (startFromBeginning) 0L else plexItem.viewOffset ?: 0L,
                        sourceLabel = "Direct Play · Original",
                        session = session,
                        theater = theater,
                    )
                }
            }
        }
    }
}
