package com.logan.shieldmedia.plex.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.logan.shieldmedia.designsystem.Backdrop
import com.logan.shieldmedia.designsystem.ContentRow
import com.logan.shieldmedia.designsystem.FocusMemory
import com.logan.shieldmedia.designsystem.LandscapeCard
import com.logan.shieldmedia.designsystem.MessageState
import com.logan.shieldmedia.designsystem.Motion
import com.logan.shieldmedia.designsystem.PivotScroll
import com.logan.shieldmedia.designsystem.PosterCard
import com.logan.shieldmedia.designsystem.RestoreFocus
import com.logan.shieldmedia.designsystem.SecondaryButton
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.designsystem.SkeletonRow
import com.logan.shieldmedia.designsystem.SpecBadge
import com.logan.shieldmedia.designsystem.Spacing
import com.logan.shieldmedia.designsystem.focusMemory
import com.logan.shieldmedia.plex.PlexHub
import com.logan.shieldmedia.plex.PlexItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlexHomeViewModel(private val env: PlexEnv) : ViewModel() {
    private val _hubs = MutableStateFlow<Loadable<List<PlexHub>>>(Loadable.Loading)
    val hubs: StateFlow<Loadable<List<PlexHub>>> = _hubs
    val focus = FocusMemory()
    var focusedItem by mutableStateOf<PlexItem?>(null)

    init {
        refresh()
    }

    /**
     * Re-fetched whenever Home is shown again, so Continue Watching reflects what was just played.
     * The lineup is chosen here rather than following Plex's Home settings: Continue Watching,
     * then movies (released, added), shows (released, added), and Plex's seasonal row if one is on.
     * Premium (Blu-Ray) libraries keep to their own screen.
     */
    fun refresh() = viewModelScope.launch {
        val result = runCatching {
            val sections = env.library.sections(env.server)
            suspend fun hubsOf(type: String) = sections
                .filter { it.type == type && !(type == "movie" && isPremiumLibrary(it.title)) }
                .flatMap { s -> runCatching { env.library.sectionHubs(env.server, s.key) }.getOrDefault(emptyList()) }
            val movies = hubsOf("movie")
            val shows = hubsOf("show")
            fun pick(hubs: List<PlexHub>, context: String, title: String? = null) =
                hubs.firstOrNull { it.context == context && it.items.isNotEmpty() }?.let { h -> title?.let { h.copy(title = it) } ?: h }
            listOfNotNull(
                env.library.continueWatching(env.server)?.takeIf { it.items.isNotEmpty() },
                pick(movies, "hub.movie.recentlyreleased", "Recently Released Movies"),
                pick(movies, "hub.movie.recentlyadded", "Recently Added Movies"),
                pick(shows, "hub.tv.recentlyaired", "Recently Released Episodes"),
                pick(shows, "hub.tv.recentlyadded", "Recently Added TV"),
                pick(movies, "hub.movie.curated"), // seasonal (e.g. "Oh, The Horror!" in October)
            )
        }
        result.onSuccess { hubs ->
            _hubs.value = Loadable.Ready(hubs)
            // Show something in the hero and backdrop before the user has moved at all.
            if (focusedItem == null) focusedItem = hubs.firstOrNull()?.items?.firstOrNull()
        }
            .onFailure { if (_hubs.value !is Loadable.Ready) _hubs.value = Loadable.Failed(it.message ?: "Unknown error") }
    }
}

internal fun PlexHub.isContinueWatching() =
    hubIdentifier.contains("continue", ignoreCase = true) || hubIdentifier.contains("ondeck", ignoreCase = true)

private fun PlexHub.useLandscape() = isContinueWatching() || type == "episode" || type == "clip"

@Composable
fun PlexHomeScreen(env: PlexEnv, onOpen: (PlexItem) -> Unit, onPlay: PlexPlayHandler) {
    val vm = viewModel(key = "home-${env.key}") { PlexHomeViewModel(env) }
    val hubs by vm.hubs.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refresh() }

    Box(Modifier.fillMaxSize()) {
        Backdrop(env.backdrop(vm.focusedItem))
        when (val state = hubs) {
            Loadable.Loading -> Column(
                Modifier.fillMaxSize().padding(top = 300.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.RowGap),
            ) {
                SkeletonRow(landscape = true)
                SkeletonRow()
            }
            is Loadable.Failed -> MessageState(
                "Couldn't load your library",
                state.message,
                action = { SecondaryButton("Try again", onClick = { vm.refresh() }) },
            )
            is Loadable.Ready -> if (state.value.isEmpty()) {
                MessageState("Nothing here yet", "Add some movies or shows to your Plex server and they'll show up here.")
            } else {
                Column(Modifier.fillMaxSize()) {
                    Hero(env, vm.focusedItem, Modifier.padding(top = 24.dp).height(196.dp))
                    // Pivot below the row title so the focused row keeps its heading visible.
                    PivotScroll(pivot = 44.dp) {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 120.dp),
                            verticalArrangement = Arrangement.spacedBy(Spacing.RowGap - 14.dp),
                        ) {
                            itemsIndexed(state.value, key = { _, hub -> hub.hubIdentifier + hub.title }) { rowIndex, hub ->
                                HubRow(env, hub, vm, isFirstRow = rowIndex == 0, onOpen = onOpen, onPlay = onPlay)
                            }
                        }
                    }
                    RestoreFocus(vm.focus, ready = true)
                }
            }
        }
    }
}

@Composable
private fun HubRow(
    env: PlexEnv,
    hub: PlexHub,
    vm: PlexHomeViewModel,
    isFirstRow: Boolean,
    onOpen: (PlexItem) -> Unit,
    onPlay: PlexPlayHandler,
) {
    ContentRow(hub.title) {
        items(hub.items, key = { it.ratingKey }) { item ->
            val (title, subtitle) = item.cardText()
            val focusModifier = Modifier.focusMemory(
                vm.focus,
                key = hub.hubIdentifier + item.ratingKey,
                isDefault = isFirstRow && item == hub.items.first(),
            )
            if (hub.useLandscape()) {
                LandscapeCard(
                    title = title,
                    subtitle = subtitle,
                    imageUrl = env.landscape(item),
                    progress = item.progress,
                    focusModifier = focusModifier,
                    onFocused = { vm.focusedItem = item },
                    // Continue Watching: OK resumes straight away; long-press opens details.
                    onClick = { if (!env.play(item, fromStart = false, onPlay)) onOpen(item) },
                    onLongClick = { onOpen(item) },
                )
            } else {
                PosterCard(
                    title = title,
                    subtitle = subtitle,
                    imageUrl = env.poster(item),
                    progress = item.progress,
                    badge = item.unwatchedCount?.toString(),
                    focusModifier = focusModifier,
                    onFocused = { vm.focusedItem = item },
                    onClick = { onOpen(item) },
                )
            }
        }
    }
}

/** Title (or logo), facts and summary of whatever card has focus. */
@Composable
internal fun Hero(env: PlexEnv, item: PlexItem?, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = item,
        contentKey = { it?.ratingKey },
        transitionSpec = { fadeIn(tween(Motion.ENTER_MS, easing = Motion.EaseOut)) togetherWith fadeOut(tween(Motion.EXIT_MS)) },
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.ScreenH),
        label = "hero",
    ) { focused ->
        if (focused == null) return@AnimatedContent
        // wrapContentWidth: AnimatedContent passes its full width down as a minimum, which would
        // otherwise defeat the width cap and run text across the bright side of the artwork.
        Column(
            Modifier.wrapContentWidth(Alignment.Start).widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TitleOrLogo(env, focused, maxLogoHeight = 84.dp)
            if (focused.type == "episode") {
                Text(
                    listOfNotNull(focused.episodeLabel, focused.title).joinToString("  ·  "),
                    style = MaterialTheme.typography.titleMedium,
                    color = ShieldColors.OnSurface,
                )
            }
            FactsAndBadges(focused)
            focused.summary?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShieldColors.OnSurfaceMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun TitleOrLogo(env: PlexEnv, item: PlexItem, maxLogoHeight: androidx.compose.ui.unit.Dp) {
    val showItem = item.type == "episode" || item.type == "season"
    val logo = if (showItem) null else env.logo(item)
    val title = if (showItem) item.grandparentTitle ?: item.parentTitle ?: item.title else item.title
    if (logo != null) {
        AsyncImage(
            model = logo,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            modifier = Modifier.heightIn(max = maxLogoHeight).widthIn(max = 420.dp),
        )
    } else {
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            color = ShieldColors.OnSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun FactsAndBadges(item: PlexItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val facts = item.factsLine()
        if (facts.isNotBlank()) {
            Text(facts, style = MaterialTheme.typography.labelLarge, color = ShieldColors.OnSurface.copy(alpha = 0.85f))
        }
        item.specBadges().forEach { SpecBadge(it) }
    }
}
