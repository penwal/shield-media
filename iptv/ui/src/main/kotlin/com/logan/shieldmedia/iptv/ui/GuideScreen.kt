package com.logan.shieldmedia.iptv.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.logan.shieldmedia.designsystem.MessageState
import com.logan.shieldmedia.designsystem.PrimaryButton
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.designsystem.ShieldIcon
import com.logan.shieldmedia.designsystem.ShieldIcons
import com.logan.shieldmedia.designsystem.SkeletonRow
import com.logan.shieldmedia.designsystem.Spacing
import com.logan.shieldmedia.iptv.Channel
import com.logan.shieldmedia.iptv.ChannelFilter
import com.logan.shieldmedia.iptv.IptvRepository
import com.logan.shieldmedia.iptv.Program
import com.logan.shieldmedia.player.OptionsPanel
import com.logan.shieldmedia.player.PanelRow
import kotlinx.coroutines.delay
import java.util.Date

private val RowHeight = 60.dp
private val ChannelColumn = 210.dp

/** Plays [channel] with the guide's current filter as the zapping list. */
typealias LivePlayHandler = (channel: Channel, filter: ChannelFilter) -> Unit

@Composable
fun LiveGuideScreen(repo: IptvRepository, onPlay: LivePlayHandler, onAddSource: () -> Unit) {
    val vm = viewModel(key = "guide") { GuideViewModel(repo) }
    val channels = vm.channels

    when {
        channels == null -> Column(Modifier.fillMaxSize().padding(top = 200.dp)) { SkeletonRow(landscape = true) }
        vm.playlistId == 0L -> MessageState(
            "Add your Live TV",
            "Sign in with an Xtream Codes account or add an M3U playlist to see channels and the TV guide here.",
            action = { PrimaryButton("Add a source", onClick = onAddSource) },
        )
        channels.isEmpty() -> MessageState(
            if (vm.filter == ChannelFilter.Favorites) "No favorites yet" else "No channels",
            if (vm.filter == ChannelFilter.Favorites) "Long-press OK on a channel in the guide to add it here." else "This source has no channels in \"${vm.filter.label}\".",
            action = { PrimaryButton("Show all channels", onClick = { vm.selectFilter(ChannelFilter.All) }) },
        )
        else -> Guide(vm, channels, onPlay)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Guide(vm: GuideViewModel, channels: List<Channel>, onPlay: LivePlayHandler) {
    val gridFocus = remember { FocusRequester() }
    val chipFocus = remember { FocusRequester() }
    var gridFocused by remember { mutableStateOf(false) }
    var groupPanel by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var longPressHandled by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (vm.windowStart + WINDOW_MS < System.currentTimeMillis()) vm.jumpToNow()
        withFrameNanos { }
        runCatching { gridFocus.requestFocus() }
    }
    // Clock tick for the now-line and "live" states.
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    // Keep the focused row two rows from the top; instant, because this follows a key held down.
    LaunchedEffect(vm.focusedRow) {
        listState.scrollToItem((vm.focusedRow - 2).coerceAtLeast(0))
    }
    LaunchedEffect(vm.focusedRow, vm.windowStart, channels) {
        vm.ensurePrograms((vm.focusedRow - 8)..(vm.focusedRow + 14))
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            GuideHeader(vm, now)
            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.ScreenH, end = 24.dp, top = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = false,
                    onClick = { groupPanel = true },
                    modifier = Modifier.width(ChannelColumn - 16.dp).focusRequester(chipFocus),
                ) {
                    Text(vm.filter.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(16.dp))
                TimeRuler(vm.windowStart, Modifier.weight(1f))
            }
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { gridFocused = it.isFocused }
                    .focusRequester(gridFocus)
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        val isCenter = event.key == Key.DirectionCenter || event.key == Key.Enter
                        if (isCenter) {
                            // Short press plays; holding toggles the channel as a favorite.
                            if (event.type == KeyEventType.KeyDown && native.repeatCount == 1) {
                                vm.focusedChannel()?.let(vm::toggleFavorite)
                                longPressHandled = true
                            }
                            if (event.type == KeyEventType.KeyUp) {
                                if (!longPressHandled) vm.focusedChannel()?.let { onPlay(it, vm.filter) }
                                longPressHandled = false
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> { vm.moveRow(1); true }
                            Key.DirectionUp -> {
                                if (vm.focusedRow == 0) runCatching { chipFocus.requestFocus() } else vm.moveRow(-1)
                                true
                            }
                            Key.DirectionRight -> { vm.moveTime(forward = true); true }
                            Key.DirectionLeft -> {
                                // At the start of the timeline, Left falls through to the menu.
                                val before = vm.focusedTime
                                vm.moveTime(forward = false)
                                before != vm.focusedTime
                            }
                            Key.ChannelUp, Key.PageUp -> { vm.moveRow(-6); true }
                            Key.ChannelDown, Key.PageDown -> { vm.moveRow(6); true }
                            else -> false
                        }
                    }
                    .focusable(),
            ) {
                val stripWidth = maxWidth - ChannelColumn - Spacing.ScreenH - 24.dp
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), userScrollEnabled = false) {
                    itemsIndexed(channels, key = { _, ch -> ch.id }) { index, channel ->
                        GuideRow(
                            vm = vm,
                            channel = channel,
                            rowFocused = gridFocused && index == vm.focusedRow,
                            stripWidth = stripWidth,
                            now = now,
                        )
                    }
                }
                NowLine(vm.windowStart, now, stripWidth)
            }
        }

        AnimatedVisibility(
            visible = groupPanel,
            enter = slideInHorizontally(tween(250)) { it / 3 } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(200)) { it / 3 } + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            val filters = listOf(ChannelFilter.All, ChannelFilter.Favorites) + vm.groups.map { ChannelFilter.Group(it) }
            OptionsPanel(
                title = "Channel groups",
                rows = filters.map { f ->
                    PanelRow.Option(f.label, null, f == vm.filter) {
                        vm.selectFilter(f)
                        groupPanel = false
                    }
                },
                emptyText = "No groups",
            )
        }
        if (groupPanel) {
            androidx.activity.compose.BackHandler { groupPanel = false }
        }
        LaunchedEffect(groupPanel) {
            if (!groupPanel) {
                withFrameNanos { }
                runCatching { gridFocus.requestFocus() }
            }
        }
    }
}

/** The focused programme in detail, like the top of TiviMate's guide. */
@Composable
private fun GuideHeader(vm: GuideViewModel, now: Long) {
    val channel = vm.focusedChannel()
    val program = vm.focusedProgram()?.takeIf { it.title.isNotEmpty() }
    val context = LocalContext.current
    val timeFormat = remember { DateFormat.getTimeFormat(context) }

    Row(
        Modifier.fillMaxWidth().height(150.dp).padding(start = Spacing.ScreenH, end = 32.dp, top = 28.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                program?.title ?: channel?.name.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                color = ShieldColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (program != null) {
                val live = program.isAiring(now)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (live) {
                        Text(
                            "LIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = ShieldColors.OnAccent,
                            modifier = Modifier.background(ShieldColors.Accent, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    Text(
                        listOfNotNull(
                            "${timeFormat.format(Date(program.start))} – ${timeFormat.format(Date(program.stop))}",
                            "${(program.stop - program.start) / 60_000} min",
                            channel?.name,
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelLarge,
                        color = ShieldColors.OnSurfaceMuted,
                    )
                }
                program.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ShieldColors.OnSurfaceMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (channel != null) {
                Text("No guide information", style = MaterialTheme.typography.bodyMedium, color = ShieldColors.OnSurfaceFaint)
            }
        }
        Text(
            timeFormat.format(Date(now)),
            style = MaterialTheme.typography.titleLarge,
            color = ShieldColors.OnSurface,
            modifier = Modifier.padding(start = 24.dp),
        )
    }
}

@Composable
private fun TimeRuler(windowStart: Long, modifier: Modifier) {
    val context = LocalContext.current
    val timeFormat = remember { DateFormat.getTimeFormat(context) }
    BoxWithConstraints(modifier.height(28.dp)) {
        val slotWidth = maxWidth / (WINDOW_MS / SLOT_MS).toInt()
        (0 until (WINDOW_MS / SLOT_MS).toInt()).forEach { i ->
            Text(
                timeFormat.format(Date(windowStart + i * SLOT_MS)),
                style = MaterialTheme.typography.labelLarge,
                color = ShieldColors.OnSurfaceMuted,
                modifier = Modifier.offset(x = slotWidth * i).padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun GuideRow(vm: GuideViewModel, channel: Channel, rowFocused: Boolean, stripWidth: Dp, now: Long) {
    val windowEnd = vm.windowStart + WINDOW_MS
    val dpPerMs = stripWidth.value / WINDOW_MS

    Row(Modifier.height(RowHeight).padding(start = Spacing.ScreenH, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        // Channel cell
        Row(
            Modifier
                .width(ChannelColumn)
                .fillMaxHeight()
                .padding(vertical = 3.dp)
                .background(if (rowFocused) ShieldColors.SurfaceBright else ShieldColors.Surface, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                channel.number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = ShieldColors.OnSurfaceFaint,
                modifier = Modifier.width(30.dp),
            )
            Box(Modifier.size(width = 52.dp, height = 34.dp), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                channel.name,
                style = MaterialTheme.typography.labelLarge,
                color = ShieldColors.OnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (channel.favorite) {
                ShieldIcon(ShieldIcons.Heart, contentDescription = "Favorite", size = 14.dp, tint = ShieldColors.Accent)
            }
        }
        Spacer(Modifier.width(16.dp))
        // Programme strip
        Box(Modifier.width(stripWidth).fillMaxHeight().clipToBounds()) {
            val focusedProgram = if (rowFocused) vm.focusedProgram() else null
            vm.blocks(channel).forEach { program ->
                if (program.stop <= vm.windowStart || program.start >= windowEnd) return@forEach
                val start = maxOf(program.start, vm.windowStart)
                val end = minOf(program.stop, windowEnd)
                val x = ((start - vm.windowStart) * dpPerMs).dp
                val w = ((end - start) * dpPerMs).dp - 4.dp
                if (w <= 0.dp) return@forEach
                ProgramCell(
                    program = program,
                    focused = program == focusedProgram,
                    live = program.isAiring(now),
                    modifier = Modifier.offset(x = x).width(w).fillMaxHeight().padding(vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun ProgramCell(program: Program, focused: Boolean, live: Boolean, modifier: Modifier) {
    val background = when {
        focused -> ShieldColors.OnSurface
        live -> ShieldColors.SurfaceBright
        else -> ShieldColors.SurfaceHigh
    }
    Column(
        modifier
            .background(background, RoundedCornerShape(8.dp))
            .then(if (live && !focused) Modifier.border(1.dp, ShieldColors.Hairline, RoundedCornerShape(8.dp)) else Modifier)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            program.title.ifEmpty { "No information" },
            style = MaterialTheme.typography.titleSmall,
            color = when {
                focused -> ShieldColors.Background
                program.title.isEmpty() -> ShieldColors.OnSurfaceFaint
                else -> ShieldColors.OnSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NowLine(windowStart: Long, now: Long, stripWidth: Dp) {
    if (now < windowStart || now > windowStart + WINDOW_MS) return
    val x = Spacing.ScreenH + ChannelColumn + 16.dp + ((now - windowStart) * (stripWidth.value / WINDOW_MS)).dp
    Box(
        Modifier
            .offset(x = x)
            .width(2.dp)
            .fillMaxHeight()
            .background(ShieldColors.Accent.copy(alpha = 0.9f)),
    )
    Box(
        Modifier
            .offset(x = x - 3.dp)
            .size(8.dp)
            .background(ShieldColors.Accent, RoundedCornerShape(50)),
    )
}
