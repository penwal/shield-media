package com.logan.shieldmedia.plex.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.logan.shieldmedia.designsystem.ShieldChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.FocusMemory
import com.logan.shieldmedia.designsystem.MessageState
import com.logan.shieldmedia.designsystem.PosterCard
import com.logan.shieldmedia.designsystem.RestoreFocus
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.designsystem.SkeletonRow
import com.logan.shieldmedia.designsystem.Spacing
import com.logan.shieldmedia.designsystem.focusMemory
import com.logan.shieldmedia.plex.LibrarySection
import com.logan.shieldmedia.plex.PlexItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 120
private const val COLUMNS = 6

class PlexLibraryViewModel(private val env: PlexEnv, private val sectionType: String) : ViewModel() {
    private val _sections = MutableStateFlow<Loadable<List<LibrarySection>>>(Loadable.Loading)
    val sections: StateFlow<Loadable<List<LibrarySection>>> = _sections

    var selected by mutableStateOf<LibrarySection?>(null)
        private set
    var items by mutableStateOf<List<PlexItem>>(emptyList())
        private set
    var total by mutableStateOf<Int?>(null)
        private set
    val focus = FocusMemory()
    private var loading: Job? = null

    init {
        viewModelScope.launch {
            runCatching { env.library.sections(env.server).filter { it.type == sectionType } }
                .onSuccess { list ->
                    _sections.value = Loadable.Ready(list)
                    list.firstOrNull()?.let(::select)
                }
                .onFailure { _sections.value = Loadable.Failed(it.message ?: "Unknown error") }
        }
    }

    fun select(section: LibrarySection) {
        if (section == selected) return
        selected = section
        items = emptyList()
        total = null
        loading?.cancel()
        loading = null
        loadMore()
    }

    /** Called as focus nears the end of what's loaded; big libraries arrive a page at a time. */
    fun loadMore() {
        val section = selected ?: return
        if (loading?.isActive == true) return
        if (total != null && items.size >= total!!) return
        loading = viewModelScope.launch {
            runCatching { env.library.sectionItems(env.server, section.key, items.size, PAGE_SIZE) }
                .onSuccess { page ->
                    items = items + page.metadata
                    total = page.totalSize ?: items.size
                }
        }
    }
}

@Composable
fun PlexLibraryScreen(env: PlexEnv, sectionType: String, title: String, onOpen: (PlexItem) -> Unit) {
    val vm = viewModel(key = "library-$sectionType-${env.server.id}") { PlexLibraryViewModel(env, sectionType) }
    val sections by vm.sections.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = Spacing.ScreenH, end = Spacing.ScreenH, top = Spacing.ScreenV, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = ShieldColors.OnSurface)
            vm.total?.let {
                Text(
                    "$it titles",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ShieldColors.OnSurfaceMuted,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }

        when (val state = sections) {
            Loadable.Loading -> SkeletonRow(modifier = Modifier.padding(top = 24.dp))
            is Loadable.Failed -> MessageState(
                "Couldn't load this library",
                state.message,
            )
            is Loadable.Ready -> {
                if (state.value.isEmpty()) {
                    MessageState("No ${title.lowercase()} libraries", "This Plex server doesn't have a ${title.lowercase()} library yet.")
                    return@Column
                }
                if (state.value.size > 1) SectionChips(state.value, vm.selected, vm::select)
                if (vm.items.isEmpty()) {
                    SkeletonRow(modifier = Modifier.padding(top = 16.dp))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(COLUMNS),
                        contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, top = 16.dp, bottom = 80.dp),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.CardGap),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        itemsIndexed(vm.items, key = { _, item -> item.ratingKey }) { index, item ->
                            PosterCard(
                                title = item.title,
                                subtitle = item.year?.toString(),
                                imageUrl = env.poster(item),
                                progress = item.progress,
                                badge = item.unwatchedCount?.toString(),
                                modifier = Modifier.fillMaxWidth(),
                                focusModifier = Modifier.focusMemory(vm.focus, item.ratingKey, isDefault = index == 0),
                                onFocused = { if (index > vm.items.size - COLUMNS * 4) vm.loadMore() },
                                onClick = { onOpen(item) },
                            )
                        }
                    }
                    RestoreFocus(vm.focus, ready = true)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionChips(sections: List<LibrarySection>, selected: LibrarySection?, onSelect: (LibrarySection) -> Unit) {
    Row(
        Modifier.padding(horizontal = Spacing.ScreenH, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        sections.forEach { section ->
            ShieldChip(
                selected = section == selected,
                onClick = { onSelect(section) },
            ) { Text(section.title) }
        }
    }
}
