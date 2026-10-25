package com.logan.shieldmedia.iptv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.logan.shieldmedia.iptv.IptvVodRepository
import com.logan.shieldmedia.iptv.VodEpisode
import com.logan.shieldmedia.iptv.VodItem
import com.logan.shieldmedia.iptv.VodKind
import com.logan.shieldmedia.player.PlaybackConfig
import com.logan.shieldmedia.player.PlaybackReport
import com.logan.shieldmedia.player.PlaybackSession
import com.logan.shieldmedia.player.PlayerScreen
import com.logan.shieldmedia.player.SkipMarker
import com.logan.shieldmedia.player.UpNext

private class VodSession(
    private val repo: IptvVodRepository,
    private val request: VodPlayRequest,
    private val onNext: (VodPlayRequest) -> Unit,
) : PlaybackSession {
    private var next: VodEpisode? = null
    private var seriesTitle: String? = request.seriesTitle

    override val markers: List<SkipMarker> = emptyList()

    override fun report(state: PlaybackReport, positionMs: Long, durationMs: Long) {
        if (durationMs > 0) repo.saveProgress(request.kind, request.id, positionMs.coerceIn(0, durationMs), durationMs)
    }

    override suspend fun loadUpNext(): UpNext? {
        val seriesId = request.seriesId ?: return null
        val series = runCatching {
            repo.seriesDetails(VodItem(VodKind.SERIES, seriesId, request.seriesTitle.orEmpty(), null, null, null, null, "mp4"))
        }.getOrNull() ?: return null
        seriesTitle = series.details.title
        val all = series.allEpisodes
        val index = all.indexOfFirst { it.id == request.id }
        val episode = all.getOrNull(index + 1) ?: return null
        next = episode
        return UpNext("S${episode.season} · E${episode.number}  ·  ${episode.title}", series.details.title, episode.image ?: series.details.poster)
    }

    override fun playUpNext() {
        val ep = next ?: return
        onNext(VodPlayRequest("episode", ep.id, ep.extension, "S${ep.season} · E${ep.number}  ·  ${ep.title}", request.seriesId, seriesTitle, fromStart = true))
    }

    // Provider streams have no server-side conversion to fall back to.
    override suspend fun fallbackUri(positionMs: Long): String? = null
}

/** Plays an IPTV movie or episode, resuming where it was left and rolling into the next episode. */
@Composable
fun IptvVodPlayerRoute(repo: IptvVodRepository, initial: VodPlayRequest, config: PlaybackConfig, onExit: () -> Unit) {
    var request by remember { mutableStateOf(initial) }
    key(request.kind, request.id) {
        val url = when (request.kind) {
            "movie" -> repo.movieUrl(VodItem(VodKind.MOVIE, request.id, request.title, null, null, null, null, request.extension))
            else -> repo.episodeUrl(VodEpisode(request.id, 0, 0, request.title, null, null, null, request.extension))
        }
        if (url == null) {
            LaunchedEffect(Unit) { onExit() }
        } else {
            val saved = repo.progress(request.kind, request.id)
            val start = if (request.fromStart || saved == null || saved.finished) 0L else saved.positionMs
            val session = remember(request) { VodSession(repo, request) { request = it } }
            PlayerScreen(
                uri = url,
                title = listOfNotNull(request.seriesTitle, request.title).joinToString("  ·  "),
                config = config.copy(userAgent = repo.userAgent() ?: config.userAgent),
                onExit = onExit,
                startPositionMs = start,
                session = session,
            )
        }
    }
}
