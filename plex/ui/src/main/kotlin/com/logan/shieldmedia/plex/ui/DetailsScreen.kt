package com.logan.shieldmedia.plex.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.logan.shieldmedia.designsystem.Backdrop
import com.logan.shieldmedia.designsystem.ContentRow
import com.logan.shieldmedia.designsystem.FocusMemory
import com.logan.shieldmedia.designsystem.LandscapeCard
import com.logan.shieldmedia.designsystem.MessageState
import com.logan.shieldmedia.designsystem.PivotScroll
import com.logan.shieldmedia.designsystem.PosterCard
import com.logan.shieldmedia.designsystem.PrimaryButton
import com.logan.shieldmedia.designsystem.RestoreFocus
import com.logan.shieldmedia.designsystem.SecondaryButton
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.designsystem.SkeletonRow
import com.logan.shieldmedia.designsystem.Spacing
import com.logan.shieldmedia.designsystem.focusMemory
import com.logan.shieldmedia.plex.PlexItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlexDetailsViewModel(private val env: PlexEnv, private val ratingKey: String) : ViewModel() {
    private val _item = MutableStateFlow<Loadable<PlexItem>>(Loadable.Loading)
    val item: StateFlow<Loadable<PlexItem>> = _item

    var seasons by mutableStateOf<List<PlexItem>>(emptyList())
        private set
    var selectedSeason by mutableStateOf<PlexItem?>(null)
        private set
    var episodes by mutableStateOf<List<PlexItem>?>(null)
        private set
    /** For shows: the episode "Play" should start (first in-progress or unwatched). */
    var nextEpisode by mutableStateOf<PlexItem?>(null)
        private set
    val focus = FocusMemory()
    private var seasonJob: Job? = null

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        runCatching {
            val loaded = env.library.item(env.server, ratingKey) ?: error("This item is no longer on the server.")
            // A season opens as its show, with that season selected.
            if (loaded.type == "season" && loaded.parentRatingKey != null) {
                val show = env.library.item(env.server, loaded.parentRatingKey!!) ?: loaded
                show to loaded.ratingKey
            } else {
                loaded to null
            }
        }.onSuccess { (item, seasonKey) ->
            _item.value = Loadable.Ready(item)
            if (item.type == "show") loadSeasons(item, seasonKey)
        }.onFailure { _item.value = Loadable.Failed(it.message ?: "Unknown error") }
    }

    private suspend fun loadSeasons(show: PlexItem, preferredSeasonKey: String?) {
        val list = runCatching { env.library.children(env.server, show.ratingKey) }.getOrDefault(emptyList())
        seasons = list
        val firstUnfinished = list.firstOrNull { !it.isWatched } ?: list.firstOrNull() ?: return
        val unfinishedEpisodes = runCatching { env.library.children(env.server, firstUnfinished.ratingKey) }
            .getOrDefault(emptyList())
        nextEpisode = unfinishedEpisodes.firstOrNull { (it.viewOffset ?: 0) > 0 }
            ?: unfinishedEpisodes.firstOrNull { !it.isWatched }
            ?: unfinishedEpisodes.firstOrNull()

        val initial = list.firstOrNull { it.ratingKey == preferredSeasonKey } ?: firstUnfinished
        if (initial == firstUnfinished) {
            selectedSeason = initial
            episodes = unfinishedEpisodes
        } else {
            showSeason(initial, immediate = true)
        }
    }

    /** Focusing a season shows its episodes; debounced so scrolling across seasons stays smooth. */
    fun showSeason(season: PlexItem, immediate: Boolean = false) {
        if (season == selectedSeason) return
        selectedSeason = season
        seasonJob?.cancel()
        seasonJob = viewModelScope.launch {
            if (!immediate) delay(250)
            episodes = null
            episodes = runCatching { env.library.children(env.server, season.ratingKey) }.getOrDefault(emptyList())
        }
    }
}

@Composable
fun PlexDetailsScreen(
    env: PlexEnv,
    ratingKey: String,
    onOpen: (PlexItem) -> Unit,
    onPlay: PlexPlayHandler,
) {
    val vm = viewModel(key = "details-$ratingKey") { PlexDetailsViewModel(env, ratingKey) }
    val state by vm.item.collectAsStateWithLifecycle()
    // Coming back from the player: pick up the new resume point / watched state.
    androidx.compose.runtime.LaunchedEffect(Unit) { if (vm.item.value is Loadable.Ready) vm.load() }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            Loadable.Loading -> Box(Modifier.fillMaxSize())
            is Loadable.Failed -> MessageState("Couldn't open this", s.message, action = {
                SecondaryButton("Try again", onClick = { vm.load() })
            })
            is Loadable.Ready -> {
                val item = s.value
                Backdrop(env.backdrop(item))
                val listState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                PivotScroll(pivot = 44.dp, onlyWhenHidden = true) {
                    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 80.dp)) {
                        item(key = "hero") {
                            // Coming back up to the buttons shows the whole hero again, title included.
                            Box(Modifier.onFocusChanged { if (it.hasFocus) scope.launch { listState.animateScrollToItem(0) } }) {
                                DetailsHero(env, item, vm, onPlay)
                            }
                        }
                        if (item.type == "show" && vm.seasons.isNotEmpty()) {
                            item(key = "seasons") { SeasonsRow(env, vm) }
                            item(key = "episodes") { EpisodesRow(env, vm, onOpen, onPlay) }
                        }
                        if (item.roles.isNotEmpty()) {
                            item(key = "cast") { CastRow(env, item) }
                        }
                    }
                }
                RestoreFocus(vm.focus, ready = true)
            }
        }
    }
}

@Composable
private fun DetailsHero(env: PlexEnv, item: PlexItem, vm: PlexDetailsViewModel, onPlay: PlexPlayHandler) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(440.dp)
            .padding(horizontal = Spacing.ScreenH),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
    ) {
        TitleOrLogo(env, item, maxLogoHeight = 130.dp)
        if (item.type == "episode") {
            Text(
                listOfNotNull(item.episodeLabel, item.title).joinToString("  ·  "),
                style = MaterialTheme.typography.titleLarge,
                color = ShieldColors.OnSurface,
            )
        }
        FactsAndBadges(item)
        item.tagline?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = ShieldColors.OnSurface.copy(alpha = 0.9f))
        }
        item.summary?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = ShieldColors.OnSurfaceMuted,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 640.dp),
            )
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val playable = if (item.type == "show") vm.nextEpisode else item
            val resumeAt = playable?.viewOffset?.takeIf { it > 0 }
            val primaryLabel = buildString {
                append(if (resumeAt != null) "Resume" else "Play")
                if (item.type == "show") playable?.episodeLabel?.let { append("  $it") }
                if (resumeAt != null && item.type != "show") append("  ${formatTimestamp(resumeAt)}")
            }
            PrimaryButton(
                text = primaryLabel,
                icon = Icons.Filled.PlayArrow,
                onClick = { playable?.let { env.play(it, fromStart = false, onPlay) } },
                modifier = Modifier.focusMemory(vm.focus, "play", isDefault = true),
            )
            if (resumeAt != null) {
                SecondaryButton(
                    text = "From the start",
                    icon = Icons.Filled.Replay,
                    onClick = { env.play(playable, fromStart = true, onPlay) },
                    modifier = Modifier.focusMemory(vm.focus, "restart"),
                )
            }
        }
        val credits = listOfNotNull(
            item.genres.take(3).joinToString(", ") { it.tag }.ifBlank { null },
            item.directors.firstOrNull()?.let { "Directed by ${it.tag}" },
        ).joinToString("   ·   ")
        if (credits.isNotBlank()) {
            Text(credits, style = MaterialTheme.typography.bodySmall, color = ShieldColors.OnSurfaceFaint)
        }
    }
}

@Composable
private fun SeasonsRow(env: PlexEnv, vm: PlexDetailsViewModel) {
    ContentRow("Seasons", Modifier.padding(top = 28.dp)) {
        items(vm.seasons, key = { it.ratingKey }) { season ->
            val selected = season == vm.selectedSeason
            PosterCard(
                title = season.title,
                subtitle = if (selected) "Showing" else season.leafCount?.let { "$it episodes" },
                imageUrl = env.poster(season),
                badge = season.unwatchedCount?.toString(),
                width = 112.dp,
                focusModifier = Modifier.focusMemory(vm.focus, "season-${season.ratingKey}"),
                onFocused = { vm.showSeason(season) },
                onClick = { vm.showSeason(season, immediate = true) },
            )
        }
    }
}

@Composable
private fun EpisodesRow(env: PlexEnv, vm: PlexDetailsViewModel, onOpen: (PlexItem) -> Unit, onPlay: PlexPlayHandler) {
    val episodes = vm.episodes
    if (episodes == null) {
        SkeletonRow(landscape = true, modifier = Modifier.padding(top = 12.dp))
        return
    }
    ContentRow(vm.selectedSeason?.title ?: "Episodes", Modifier.padding(top = 12.dp)) {
        items(episodes, key = { it.ratingKey }) { episode ->
            LandscapeCard(
                title = listOfNotNull(episode.index?.let { "$it." }, episode.title).joinToString(" "),
                subtitle = listOfNotNull(
                    episode.duration?.let(::formatDuration),
                    "Watched".takeIf { episode.isWatched },
                ).joinToString(" · "),
                imageUrl = env.landscape(episode),
                progress = episode.progress,
                width = 280.dp,
                focusModifier = Modifier.focusMemory(vm.focus, "episode-${episode.ratingKey}"),
                // OK plays (resuming if started); long-press opens the episode's own page.
                onClick = { if (!env.play(episode, fromStart = false, onPlay)) onOpen(episode) },
                onLongClick = { onOpen(episode) },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CastRow(env: PlexEnv, item: PlexItem) {
    ContentRow("Cast", Modifier.padding(top = 12.dp)) {
        items(item.roles.take(20), key = { it.tag + it.role }) { role ->
            Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Card(
                    onClick = {},
                    modifier = Modifier.size(96.dp),
                    shape = CardDefaults.shape(CircleShape),
                    scale = CardDefaults.scale(focusedScale = 1.08f),
                    border = CardDefaults.border(
                        focusedBorder = Border(BorderStroke(2.dp, ShieldColors.FocusOutline), shape = CircleShape),
                    ),
                    colors = CardDefaults.colors(containerColor = ShieldColors.SurfaceHigh),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            role.tag.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
                            style = MaterialTheme.typography.titleLarge,
                            color = ShieldColors.OnSurfaceFaint,
                        )
                        AsyncImage(
                            model = env.person(role.thumb),
                            contentDescription = role.tag,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Text(
                    role.tag,
                    style = MaterialTheme.typography.titleSmall,
                    color = ShieldColors.OnSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp),
                )
                role.role?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = ShieldColors.OnSurfaceMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
