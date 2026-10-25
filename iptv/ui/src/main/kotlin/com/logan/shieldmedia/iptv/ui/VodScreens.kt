package com.logan.shieldmedia.iptv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.logan.shieldmedia.designsystem.ShieldChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
import com.logan.shieldmedia.designsystem.SpecBadge
import com.logan.shieldmedia.designsystem.focusMemory
import com.logan.shieldmedia.iptv.SeriesDetails
import com.logan.shieldmedia.iptv.VodCategory
import com.logan.shieldmedia.iptv.VodDetails
import com.logan.shieldmedia.iptv.VodEpisode
import com.logan.shieldmedia.iptv.VodItem
import com.logan.shieldmedia.iptv.VodKind
import com.logan.shieldmedia.iptv.IptvVodRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Opens a title's page. */
typealias VodOpenHandler = (VodItem) -> Unit

/** Plays a movie or an episode. [seriesId] lets the player continue to the next episode. */
data class VodPlayRequest(
    val kind: String,
    val id: String,
    val extension: String,
    val title: String,
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val fromStart: Boolean = false,
)

typealias VodPlayHandler = (VodPlayRequest) -> Unit

class VodBrowseViewModel(private val repo: IptvVodRepository) : ViewModel() {
    var kind by mutableStateOf(VodKind.MOVIE)
        private set
    var categories by mutableStateOf<List<VodCategory>?>(null)
        private set
    var category by mutableStateOf<VodCategory?>(null)
        private set
    var items by mutableStateOf<List<VodItem>?>(null)
        private set
    var continueWatching by mutableStateOf<List<VodItem>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var version by mutableIntStateOf(0)
        private set
    val focus = FocusMemory()
    private var job: Job? = null

    init {
        load()
    }

    fun select(newKind: VodKind) {
        if (newKind == kind) return
        kind = newKind
        category = null
        load()
    }

    fun select(newCategory: VodCategory?) {
        category = newCategory
        loadItems()
    }

    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            error = null
            categories = null
            items = null
            runCatching { repo.categories(kind) }
                .onSuccess { categories = it }
                .onFailure { error = it.message }
            loadItems()
            refreshContinue()
        }
    }

    private fun loadItems() {
        viewModelScope.launch {
            items = null
            runCatching { repo.items(kind, category?.id) }
                .onSuccess { items = it }
                .onFailure { error = it.message }
        }
    }

    /** Movies with saved progress (series continue from their details page). */
    fun refreshContinue() {
        viewModelScope.launch {
            continueWatching = if (kind != VodKind.MOVIE) {
                emptyList()
            } else {
                runCatching { repo.items(VodKind.MOVIE, null) }.getOrDefault(emptyList())
                    .mapNotNull { item -> repo.progress("movie", item.id)?.takeIf { !it.finished && it.positionMs > 0 }?.let { item to it } }
                    .sortedByDescending { it.second.updatedAt }
                    .take(15)
                    .map { it.first }
            }
            version++
        }
    }

    fun progressOf(item: VodItem): Float? =
        if (item.kind == VodKind.MOVIE) repo.progress("movie", item.id)?.takeIf { !it.finished }?.fraction else null
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun IptvVodScreen(repo: IptvVodRepository, onOpen: VodOpenHandler, onPlay: VodPlayHandler) {
    if (!repo.isSupported()) {
        MessageState(
            "No movies or series",
            "Your current Live TV source doesn't include movies or series. Xtream Codes accounts that offer them will appear here.",
        )
        return
    }
    val vm = viewModel(key = "vod") { VodBrowseViewModel(repo) }
    LaunchedEffect(Unit) { vm.refreshContinue() }
    val items = vm.items

    LazyVerticalGrid(
        columns = GridCells.Adaptive(118.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.CardGap),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = Spacing.ScreenV), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        if (vm.kind == VodKind.MOVIE) "Movies" else "Series",
                        style = MaterialTheme.typography.headlineLarge,
                        color = ShieldColors.OnSurface,
                    )
                    items?.let {
                        Text("${it.size} titles", style = MaterialTheme.typography.bodyLarge, color = ShieldColors.OnSurfaceMuted, modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ShieldChip(selected = vm.kind == VodKind.MOVIE, onClick = { vm.select(VodKind.MOVIE) }) { Text("Movies") }
                    ShieldChip(selected = vm.kind == VodKind.SERIES, onClick = { vm.select(VodKind.SERIES) }) { Text("Series") }
                }
                vm.categories?.takeIf { it.isNotEmpty() }?.let { cats ->
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { ShieldChip(selected = vm.category == null, onClick = { vm.select(null) }) { Text("All") } }
                        items(cats, key = { it.id }) { c ->
                            ShieldChip(selected = vm.category == c, onClick = { vm.select(c) }) { Text(c.name) }
                        }
                    }
                }
            }
        }
        if (vm.continueWatching.isNotEmpty() && vm.category == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ContentRow("Continue Watching", Modifier.padding(top = 8.dp), inset = 0.dp) {
                    items(vm.continueWatching, key = { "cw" + it.id }) { item ->
                        PosterCard(
                            title = item.name,
                            subtitle = item.year,
                            imageUrl = item.poster,
                            progress = vm.progressOf(item),
                            onClick = { onPlay(VodPlayRequest("movie", item.id, item.extension, item.name)) },
                            onLongClick = { onOpen(item) },
                        )
                    }
                }
            }
        }
        when {
            vm.error != null && items == null -> item(span = { GridItemSpan(maxLineSpan) }) {
                MessageState("Couldn't load", vm.error, action = { SecondaryButton("Try again", onClick = { vm.load() }) })
            }
            items == null -> item(span = { GridItemSpan(maxLineSpan) }) { SkeletonRow(modifier = Modifier.padding(top = 8.dp)) }
            items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                val what = if (vm.kind == VodKind.MOVIE) "movies" else "series"
                Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (vm.category == null) "Your provider doesn't offer any $what" else "No $what in ${vm.category?.name}",
                        style = MaterialTheme.typography.titleLarge,
                        color = ShieldColors.OnSurface,
                    )
                    if (vm.category == null) {
                        Text(
                            "Many Xtream Codes accounts include movies and series; this one doesn't. Add another source in Settings → Live TV sources.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = ShieldColors.OnSurfaceMuted,
                            modifier = Modifier.widthIn(max = 620.dp),
                        )
                    }
                }
            }
            else -> itemsIndexed(items, key = { _, it -> it.kind.name + it.id }) { index, item ->
                PosterCard(
                    title = item.name,
                    subtitle = listOfNotNull(item.year, item.rating?.let { "★ $it" }).joinToString("  ·  ").ifEmpty { null },
                    imageUrl = item.poster,
                    progress = vm.progressOf(item),
                    modifier = Modifier.fillMaxWidth(),
                    focusModifier = Modifier.focusMemory(vm.focus, item.kind.name + item.id, isDefault = index == 0),
                    onClick = { onOpen(item) },
                )
            }
        }
    }
    RestoreFocus(vm.focus, ready = items != null)
}

class VodDetailsViewModel(private val repo: IptvVodRepository, val item: VodItem) : ViewModel() {
    var movie by mutableStateOf<VodDetails?>(null)
        private set
    var series by mutableStateOf<SeriesDetails?>(null)
        private set
    var season by mutableIntStateOf(1)
    var error by mutableStateOf<String?>(null)
        private set
    var version by mutableIntStateOf(0)
        private set
    val focus = FocusMemory()

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        runCatching {
            if (item.kind == VodKind.MOVIE) {
                movie = repo.movieDetails(item)
            } else {
                val s = repo.seriesDetails(item)
                series = s
                season = nextEpisode(s)?.season ?: s.seasons.keys.minOrNull() ?: 1
            }
        }.onFailure { error = it.message ?: "Couldn't load this title" }
        version++
    }

    fun refresh() {
        version++
    }

    fun progress(kind: String, id: String) = repo.progress(kind, id)

    /** First episode that's in progress, else the first unwatched one. */
    fun nextEpisode(s: SeriesDetails = series!!): VodEpisode? {
        val all = s.allEpisodes
        return all.firstOrNull { repo.progress("episode", it.id)?.let { p -> !p.finished && p.positionMs > 0 } == true }
            ?: all.firstOrNull { repo.progress("episode", it.id)?.finished != true }
            ?: all.firstOrNull()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun IptvVodDetailsScreen(repo: IptvVodRepository, item: VodItem, onPlay: VodPlayHandler) {
    val vm = viewModel(key = "vod-${item.kind}-${item.id}") { VodDetailsViewModel(repo, item) }
    LaunchedEffect(Unit) { vm.refresh() }
    val details = vm.movie ?: vm.series?.details
    // Progress lives in preferences, not Compose state; reading the version re-renders after playback.
    val refresh = vm.version

    Box(Modifier.fillMaxSize()) {
        Backdrop(details?.backdrop ?: details?.poster ?: item.poster)
        when {
            vm.error != null -> MessageState("Couldn't open this", vm.error)
            details == null -> Box(Modifier.fillMaxSize())
            else -> PivotScroll(pivot = 44.dp, onlyWhenHidden = true) {
                val listState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 80.dp)) {
                    item {
                        // Back up at the buttons, show the whole hero again (title included).
                        Box(Modifier.onFocusChanged { if (it.hasFocus) scope.launch { listState.animateScrollToItem(0) } }) {
                            VodHero(vm, details, onPlay)
                        }
                    }
                    vm.series?.let { s ->
                        if (s.seasons.size > 1) {
                            item {
                                Row(
                                    Modifier.padding(start = Spacing.ScreenH, top = 28.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    s.seasons.keys.sorted().forEach { n ->
                                        ShieldChip(
                                            selected = vm.season == n,
                                            onClick = { vm.season = n },
                                            modifier = Modifier.focusMemory(vm.focus, "season-$n"),
                                        ) { Text("Season $n") }
                                    }
                                }
                            }
                        }
                        item {
                            ContentRow(if (s.seasons.size > 1) "Episodes" else "Season ${vm.season}", Modifier.padding(top = 16.dp)) {
                                items(s.seasons[vm.season].orEmpty(), key = { it.id }) { ep ->
                                    val p = vm.progress("episode", ep.id)
                                    LandscapeCard(
                                        title = "${ep.number}. ${ep.title}",
                                        subtitle = listOfNotNull(
                                            ep.durationSecs?.takeIf { it > 0 }?.let { "${it / 60} min" },
                                            "Watched".takeIf { p?.finished == true },
                                        ).joinToString(" · ").ifEmpty { null },
                                        imageUrl = ep.image ?: details.poster,
                                        progress = p?.takeIf { !it.finished }?.fraction,
                                        width = 280.dp,
                                        focusModifier = Modifier.focusMemory(vm.focus, "ep-${ep.id}"),
                                        onClick = {
                                            onPlay(VodPlayRequest("episode", ep.id, ep.extension, "S${ep.season} · E${ep.number}  ·  ${ep.title}", item.id, details.title))
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    RestoreFocus(vm.focus, ready = details != null)
}

@Composable
private fun VodHero(vm: VodDetailsViewModel, d: VodDetails, onPlay: VodPlayHandler) {
    val item = vm.item
    val episode = vm.series?.let { vm.nextEpisode(it) }
    val progress = if (item.kind == VodKind.MOVIE) vm.progress("movie", item.id) else episode?.let { vm.progress("episode", it.id) }
    val resume = progress?.takeIf { !it.finished && it.positionMs > 30_000 }

    fun request(fromStart: Boolean): VodPlayRequest? = if (item.kind == VodKind.MOVIE) {
        VodPlayRequest("movie", item.id, item.extension, d.title, fromStart = fromStart)
    } else {
        episode?.let { VodPlayRequest("episode", it.id, it.extension, "S${it.season} · E${it.number}  ·  ${it.title}", item.id, d.title, fromStart) }
    }

    Column(
        Modifier.fillMaxWidth().height(440.dp).padding(horizontal = Spacing.ScreenH),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
    ) {
        Text(d.title, style = MaterialTheme.typography.displaySmall, color = ShieldColors.OnSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val facts = listOfNotNull(
                d.year,
                d.durationSecs?.takeIf { it > 0 }?.let { s -> if (s >= 3600) "${s / 3600}h ${s % 3600 / 60}m" else "${s / 60}m" },
                vm.series?.let { s -> if (s.seasons.size == 1) "1 season" else "${s.seasons.size} seasons" },
                d.rating?.let { "★ $it" },
            ).joinToString("  ·  ")
            if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.labelLarge, color = ShieldColors.OnSurface.copy(alpha = 0.85f))
            d.genre?.split(',', '/')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { SpecBadge(it) }
        }
        d.plot?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = ShieldColors.OnSurfaceMuted, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 640.dp))
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val label = buildString {
                append(if (resume != null) "Resume" else "Play")
                episode?.let { append("  S${it.season} · E${it.number}") }
            }
            PrimaryButton(
                label,
                icon = null,
                onClick = { request(fromStart = false)?.let(onPlay) },
                modifier = Modifier.focusMemory(vm.focus, "play", isDefault = true),
            )
            if (resume != null) {
                SecondaryButton("From the start", onClick = { request(fromStart = true)?.let(onPlay) })
            }
        }
        listOfNotNull(d.cast?.let { "With $it" }, d.director?.let { "Directed by $it" }).joinToString("   ·   ").takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = ShieldColors.OnSurfaceFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
