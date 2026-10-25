package com.logan.shieldmedia.plex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.ContentRow
import com.logan.shieldmedia.designsystem.LandscapeCard
import com.logan.shieldmedia.designsystem.MessageState
import com.logan.shieldmedia.designsystem.PivotScroll
import com.logan.shieldmedia.designsystem.PosterCard
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.designsystem.Spacing
import com.logan.shieldmedia.plex.PlexHub
import com.logan.shieldmedia.plex.PlexItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PLAYABLE_TYPES = setOf("movie", "show", "episode", "season")

class PlexSearchViewModel(private val env: PlexEnv) : ViewModel() {
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<PlexHub>?>(null)
        private set
    var searching by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun onQueryChange(value: String) {
        query = value
        job?.cancel()
        if (value.isBlank()) {
            results = null
            return
        }
        job = viewModelScope.launch {
            delay(350) // wait for typing to pause
            searching = true
            results = runCatching { env.library.search(env.server, value.trim()) }
                .getOrDefault(emptyList())
                .filter { it.type in PLAYABLE_TYPES }
            searching = false
        }
    }
}

@Composable
fun PlexSearchScreen(env: PlexEnv, onOpen: (PlexItem) -> Unit) {
    val vm = viewModel(key = "search-${env.server.id}") { PlexSearchViewModel(env) }

    Column(Modifier.fillMaxSize()) {
        SearchField(vm.query, vm::onQueryChange)
        val results = vm.results
        when {
            results == null -> MessageState("Search your library", "Movies, shows and episodes on ${env.server.name}.")
            results.isEmpty() && !vm.searching -> MessageState("No results", "Nothing on ${env.server.name} matches “${vm.query}”.")
            else -> PivotScroll(pivot = 0.dp) {
                LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)) {
                    items(results, key = { it.hubIdentifier + it.title }) { hub ->
                        ContentRow(hub.title) {
                            items(hub.items, key = { it.ratingKey }) { item ->
                                val (title, subtitle) = item.cardText()
                                if (hub.type == "episode") {
                                    LandscapeCard(title, env.landscape(item), subtitle = subtitle, onClick = { onOpen(item) })
                                } else {
                                    PosterCard(title, env.poster(item), subtitle = subtitle, onClick = { onOpen(item) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A search field that behaves on a remote: D-pad focus highlights it, OK opens the on-screen
 * keyboard, and Down leaves it for the results.
 */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(focused) { if (!focused) keyboard?.hide() }

    Row(
        Modifier
            .padding(start = Spacing.ScreenH, end = Spacing.ScreenH, top = Spacing.ScreenV, bottom = 12.dp)
            .width(620.dp)
            .background(if (focused) ShieldColors.SurfaceBright else ShieldColors.SurfaceHigh, RoundedCornerShape(50))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) ShieldColors.FocusOutline else ShieldColors.Hairline,
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = ShieldColors.OnSurfaceMuted, modifier = Modifier.size(24.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("Search movies and shows", style = MaterialTheme.typography.titleMedium, color = ShieldColors.OnSurfaceFaint)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = ShieldColors.OnSurface),
                cursorBrush = SolidColor(ShieldColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type == KeyEventType.KeyUp &&
                                (event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
                                keyboard?.show()
                                true
                            }
                            // A single-line field would swallow Down; send it on to the results.
                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown ->
                                focusManager.moveFocus(FocusDirection.Down)
                            else -> false
                        }
                    },
            )
        }
    }
}
