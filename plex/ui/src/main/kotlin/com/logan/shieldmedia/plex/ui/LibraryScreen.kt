package com.logan.shieldmedia.plex.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.FocusMemory
import com.logan.shieldmedia.designsystem.MessageState
import com.logan.shieldmedia.designsystem.Motion
import com.logan.shieldmedia.designsystem.PosterCard
import com.logan.shieldmedia.designsystem.RestoreFocus
import com.logan.shieldmedia.designsystem.ShieldChip
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.designsystem.SkeletonRow
import com.logan.shieldmedia.designsystem.Spacing
import com.logan.shieldmedia.designsystem.focusMemory
import com.logan.shieldmedia.plex.LibrarySection
import com.logan.shieldmedia.plex.PlexItem
import com.logan.shieldmedia.player.OptionsPanel
import com.logan.shieldmedia.player.PanelRow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 120

/** Ways to order a library, as Plex sort keys. */
enum class LibrarySort(val label: String, val key: String) {
    Title("Title", "titleSort"),
    Added("Date added", "addedAt:desc"),
    Released("Release date", "originallyAvailableAt:desc"),
    Rating("Rating", "audienceRating:desc"),
    LastWatched("Recently watched", "lastViewedAt:desc"),
}

private enum class LibraryPanel { Sort, Genre }

class PlexLibraryViewModel(private val env: PlexEnv, private val sectionKey: String) : ViewModel() {
    var items by mutableStateOf<List<PlexItem>?>(null)
        private set
    var total by mutableStateOf<Int?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var genres by mutableStateOf<List<LibrarySection>>(emptyList())
        private set

    var sort by mutableStateOf(LibrarySort.Title)
        private set
    var genre by mutableStateOf<LibrarySection?>(null)
        private set
    var unwatched by mutableStateOf(false)
        private set

    val focus = FocusMemory()
    private var loading: Job? = null

    init {
        reload()
        viewModelScope.launch {
            genres = runCatching { env.library.genres(env.server, sectionKey) }.getOrDefault(emptyList())
        }
    }

    fun chooseSort(value: LibrarySort) {
        if (value == sort) return
        sort = value
        reload()
    }

    fun chooseGenre(value: LibrarySection?) {
        if (value == genre) return
        genre = value
        reload()
    }

    fun toggleUnwatched() {
        unwatched = !unwatched
        reload()
    }

    private fun reload() {
        loading?.cancel()
        loading = null
        items = null
        total = null
        error = null
        focus.forget()
        loadMore()
    }

    /** Called as focus nears the end of what's loaded; big libraries arrive a page at a time. */
    fun loadMore() {
        val loaded = items.orEmpty()
        if (loading?.isActive == true) return
        if (total != null && loaded.size >= total!!) return
        loading = viewModelScope.launch {
            runCatching {
                env.library.sectionItems(env.server, sectionKey, loaded.size, PAGE_SIZE, sort.key, genre?.key, unwatched)
            }.onSuccess { page ->
                items = loaded + page.metadata
                total = page.totalSize ?: (loaded.size + page.metadata.size)
            }.onFailure { if (items == null) error = it.message ?: "Unknown error" }
        }
    }
}

@Composable
fun PlexLibraryScreen(env: PlexEnv, sectionKey: String, title: String, onOpen: (PlexItem) -> Unit) {
    val vm = viewModel(key = "library-$sectionKey-${env.server.id}") { PlexLibraryViewModel(env, sectionKey) }
    var panel by remember { mutableStateOf<LibraryPanel?>(null) }
    val sortFocus = remember { FocusRequester() }
    val genreFocus = remember { FocusRequester() }
    var returnTo by remember { mutableStateOf<FocusRequester?>(null) }
    val items = vm.items

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.ScreenH, end = Spacing.ScreenH, top = Spacing.ScreenV),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineLarge, color = ShieldColors.OnSurface)
                vm.total?.let {
                    Text(
                        if (it == 1) "1 title" else "$it titles",
                        style = MaterialTheme.typography.bodyLarge,
                        color = ShieldColors.OnSurfaceMuted,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            // Filter bar: what's active reads at a glance ("Date added", "Action"), OK changes it.
            Row(
                Modifier.padding(start = Spacing.ScreenH, end = Spacing.ScreenH, top = 14.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ShieldChip(
                    selected = vm.sort != LibrarySort.Title,
                    onClick = {
                        returnTo = sortFocus
                        panel = LibraryPanel.Sort
                    },
                    modifier = Modifier.focusRequester(sortFocus),
                ) { Text("Sort: ${vm.sort.label}") }
                if (vm.genres.isNotEmpty()) {
                    ShieldChip(
                        selected = vm.genre != null,
                        onClick = {
                            returnTo = genreFocus
                            panel = LibraryPanel.Genre
                        },
                        modifier = Modifier.focusRequester(genreFocus),
                    ) { Text(vm.genre?.title ?: "All genres") }
                }
                ShieldChip(selected = vm.unwatched, onClick = { vm.toggleUnwatched() }) { Text("Unwatched") }
            }

            when {
                vm.error != null -> MessageState("Couldn't load this library", vm.error)
                items == null -> SkeletonRow(modifier = Modifier.padding(top = 24.dp))
                items.isEmpty() -> MessageState(
                    "Nothing matches",
                    "Try another genre or turn off Unwatched.",
                )
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(118.dp),
                        contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, top = 16.dp, bottom = 80.dp),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.CardGap),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        itemsIndexed(items, key = { _, item -> item.ratingKey }) { index, item ->
                            PosterCard(
                                title = item.title,
                                subtitle = subtitleFor(item, vm.sort),
                                imageUrl = env.poster(item),
                                progress = item.progress,
                                badge = item.unwatchedCount?.toString(),
                                modifier = Modifier.fillMaxWidth(),
                                focusModifier = Modifier.focusMemory(vm.focus, item.ratingKey, isDefault = index == 0),
                                onFocused = { if (index > items.size - 40) vm.loadMore() },
                                onClick = { onOpen(item) },
                            )
                        }
                    }
                    RestoreFocus(vm.focus, ready = panel == null)
                }
            }
        }

        AnimatedVisibility(
            visible = panel != null,
            enter = slideInHorizontally(tween(250)) { it / 3 } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(200)) { it / 3 } + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            when (panel ?: LibraryPanel.Sort) {
                LibraryPanel.Sort -> OptionsPanel(
                    title = "Sort by",
                    rows = LibrarySort.entries.map { s ->
                        PanelRow.Option(s.label, null, s == vm.sort) {
                            vm.chooseSort(s)
                            panel = null
                        }
                    },
                    emptyText = "",
                )
                LibraryPanel.Genre -> OptionsPanel(
                    title = "Genre",
                    rows = listOf(
                        PanelRow.Option("All genres", null, vm.genre == null) {
                            vm.chooseGenre(null)
                            panel = null
                        },
                    ) + vm.genres.map { g ->
                        PanelRow.Option(g.title, null, g.key == vm.genre?.key) {
                            vm.chooseGenre(g)
                            panel = null
                        }
                    },
                    emptyText = "No genres",
                )
            }
        }
    }

    if (panel != null) BackHandler { panel = null }
    // After the panel closes (and its exit animation ends), put focus back on the chip that opened it.
    LaunchedEffect(panel) {
        if (panel == null) {
            val target = returnTo ?: return@LaunchedEffect
            delay(Motion.EXIT_MS + 120L)
            runCatching { target.requestFocus() }
            returnTo = null
        }
    }
}

/** The caption under each poster reflects the active sort, so the order makes sense. */
private fun subtitleFor(item: PlexItem, sort: LibrarySort): String? = when (sort) {
    LibrarySort.Rating -> item.audienceRating?.let { "★ %.1f".format(it) } ?: item.year?.toString()
    LibrarySort.Released -> item.originallyAvailableAt ?: item.year?.toString()
    else -> item.year?.toString()
}
