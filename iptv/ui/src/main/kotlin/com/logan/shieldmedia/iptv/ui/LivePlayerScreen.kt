package com.logan.shieldmedia.iptv.ui

import android.text.format.DateFormat
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.logan.shieldmedia.designsystem.ShieldListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.designsystem.ShieldIcon
import com.logan.shieldmedia.designsystem.ShieldIcons
import com.logan.shieldmedia.iptv.Channel
import com.logan.shieldmedia.iptv.ChannelFilter
import com.logan.shieldmedia.iptv.IptvRepository
import com.logan.shieldmedia.iptv.Program
import com.logan.shieldmedia.player.AudioDelay
import com.logan.shieldmedia.player.OptionsPanel
import com.logan.shieldmedia.player.PanelRow
import com.logan.shieldmedia.player.PlaybackConfig
import com.logan.shieldmedia.player.PlayerFactory
import com.logan.shieldmedia.player.audioOptions
import com.logan.shieldmedia.player.selectTrack
import com.logan.shieldmedia.player.subtitleOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

private const val BANNER_MS = 4_000L
private const val NUMBER_ENTRY_MS = 1_400L
private const val MAX_RETRIES = 6

private enum class LiveOverlay { None, Channels, Options }

/**
 * Full-screen live TV. One player instance for the whole session so changing channel only swaps
 * the media item — the fastest zap the hardware allows.
 *
 * Remote: Up/Down or CH+/CH- change channel · OK opens the channel list · hold OK for audio and
 * subtitles · digits jump to a channel number · Left/Right show what's on.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LivePlayerScreen(
    repo: IptvRepository,
    playlistId: Long,
    filter: ChannelFilter,
    startChannelId: Long,
    config: PlaybackConfig,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val audioDelay = remember { AudioDelay() }
    var audioDelayMs by remember { mutableIntStateOf(0) }
    val player = remember { PlayerFactory.create(context, config.copy(autoFrameRate = false), live = true, audioDelay = audioDelay) }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var index by remember { mutableIntStateOf(-1) }
    var lastIndex by remember { mutableIntStateOf(-1) }
    val nowNext = remember { mutableStateMapOf<String, List<Program>>() }

    var buffering by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<String?>(null) }
    var retries by remember { mutableIntStateOf(0) }
    var videoAspect by remember { mutableFloatStateOf(16f / 9f) }
    var bannerUntil by remember { mutableLongStateOf(0L) }
    var bannerVisible by remember { mutableStateOf(true) }
    var numberEntry by remember { mutableStateOf("") }
    var overlay by remember { mutableStateOf(LiveOverlay.None) }
    var tracksVersion by remember { mutableIntStateOf(0) }
    var longPressHandled by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val rootFocus = remember { FocusRequester() }
    val upStep = remember { if (repo.upIsNextChannel) 1 else -1 }
    val scope = rememberCoroutineScope()
    var reconnectJob by remember { mutableStateOf<Job?>(null) }

    fun showBanner() {
        bannerUntil = System.currentTimeMillis() + BANNER_MS
        bannerVisible = true
    }

    /** Reopens the current channel's stream with a fresh connection, after [afterMs]. */
    fun reconnect(afterMs: Long) {
        val channel = channels.getOrNull(index) ?: return
        reconnectJob?.cancel()
        buffering = true
        reconnectJob = scope.launch {
            delay(afterMs)
            player.setMediaItem(MediaItem.fromUri(channel.streamUrl))
            player.prepare()
            player.playWhenReady = true
        }
    }

    /** Live streams hiccup: reconnect quietly, backing off a little each time, before giving up. */
    fun retryOrFail() {
        if (retries < MAX_RETRIES) {
            retries++
            reconnect(minOf(1_000L * retries, 4_000L))
        } else {
            failed = "This channel isn't available right now."
        }
    }

    fun tune(newIndex: Int) {
        if (channels.isEmpty()) return
        reconnectJob?.cancel()
        val wrapped = (newIndex + channels.size) % channels.size
        if (wrapped != index) lastIndex = index
        index = wrapped
        failed = null
        retries = 0
        buffering = true
        val channel = channels[wrapped]
        player.setMediaItem(MediaItem.fromUri(channel.streamUrl))
        player.prepare()
        player.playWhenReady = true
        showBanner()
    }

    LaunchedEffect(playlistId, filter) {
        val list = repo.channels(playlistId, filter)
        channels = list
        val start = list.indexOfFirst { it.id == startChannelId }.coerceAtLeast(0)
        tune(start)
        val ids = list.mapNotNull { it.epgId }
        val t = System.currentTimeMillis()
        nowNext.putAll(repo.programs(playlistId, ids, t, t + 6 * 60 * 60_000L))
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_READY) retries = 0
                // A live stream never really ends; the server closed the connection. Without this the
                // picture would sit frozen on the last frame.
                if (state == Player.STATE_ENDED) retryOrFail()
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) videoAspect = size.width * size.pixelWidthHeightRatio / size.height
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                tracksVersion++
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                } else {
                    retryOrFail()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(bannerUntil) {
        delay((bannerUntil - System.currentTimeMillis()).coerceAtLeast(0))
        if (System.currentTimeMillis() >= bannerUntil) bannerVisible = false
    }
    LaunchedEffect(numberEntry) {
        if (numberEntry.isEmpty()) return@LaunchedEffect
        delay(NUMBER_ENTRY_MS)
        val target = numberEntry.toIntOrNull()
        numberEntry = ""
        val i = channels.indexOfFirst { it.number == target }
        if (i >= 0) tune(i)
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    LaunchedEffect(overlay) {
        if (overlay == LiveOverlay.None) {
            withFrameNanos { }
            runCatching { rootFocus.requestFocus() }
        }
    }

    BackHandler {
        if (overlay != LiveOverlay.None) overlay = LiveOverlay.None else onExit()
    }

    val current = channels.getOrNull(index)
    val programs = current?.epgId?.let { nowNext[it] }.orEmpty()
    val onNow = programs.firstOrNull { it.isAiring(now) }
    val upNext = programs.firstOrNull { it.start >= (onNow?.stop ?: now) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (overlay != LiveOverlay.None) return@onPreviewKeyEvent false
                val isCenter = event.key == Key.DirectionCenter || event.key == Key.Enter
                if (isCenter) {
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 1) {
                        overlay = LiveOverlay.Options
                        longPressHandled = true
                    }
                    if (event.type == KeyEventType.KeyUp) {
                        if (!longPressHandled) overlay = LiveOverlay.Channels
                        longPressHandled = false
                    }
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = event.nativeKeyEvent.number.takeIf { it.isDigit() }
                when {
                    digit != null -> {
                        if (numberEntry.length < 5) numberEntry += digit
                        true
                    }
                    event.key == Key.DirectionUp -> { tune(index + upStep); true }
                    event.key == Key.DirectionDown -> { tune(index - upStep); true }
                    // Dedicated channel keys always mean what they say.
                    event.key == Key.ChannelUp -> { tune(index + 1); true }
                    event.key == Key.ChannelDown -> { tune(index - 1); true }
                    event.key == Key.LastChannel -> { if (lastIndex >= 0) tune(lastIndex); true }
                    event.key == Key.DirectionLeft || event.key == Key.DirectionRight || event.key == Key.Info -> {
                        showBanner()
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx -> SurfaceView(ctx).apply { keepScreenOn = true; player.setVideoSurfaceView(this) } },
                modifier = Modifier.aspectRatio(videoAspect),
            )
        }

        if (buffering && failed == null && current != null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(current.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(160.dp, 90.dp))
                Spinner(Modifier.padding(top = 20.dp))
            }
        }
        failed?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                color = ShieldColors.OnSurfaceMuted,
                modifier = Modifier.align(Alignment.Center).background(ShieldColors.Surface, RoundedCornerShape(12.dp)).padding(24.dp),
            )
        }

        AnimatedVisibility(
            visible = bannerVisible && current != null && overlay == LiveOverlay.None,
            enter = slideInVertically(tween(220)) { it / 4 } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(160)) { it / 4 } + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (current != null) InfoBanner(current, onNow, upNext, now)
        }

        if (numberEntry.isNotEmpty()) {
            Text(
                numberEntry,
                fontSize = 56.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(40.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = overlay == LiveOverlay.Channels,
            enter = slideInHorizontally(tween(250)) { -it / 3 } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(200)) { -it / 3 } + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            MiniGuide(
                channels = channels,
                currentIndex = index,
                nowNext = nowNext,
                now = now,
                filterLabel = filter.label,
                onPick = {
                    tune(it)
                    overlay = LiveOverlay.None
                },
            )
        }

        AnimatedVisibility(
            visible = overlay == LiveOverlay.Options,
            enter = slideInHorizontally(tween(250)) { it / 3 } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(200)) { it / 3 } + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            val audio = remember(tracksVersion) { player.audioOptions() }
            val subs = remember(tracksVersion) { player.subtitleOptions() }
            OptionsPanel(
                title = "Audio & subtitles",
                rows = buildList {
                    add(PanelRow.Header("Audio"))
                    audio.forEach { o ->
                        add(PanelRow.Option(o.title, o.detail, o.selected) { player.selectTrack(C.TRACK_TYPE_AUDIO, o); overlay = LiveOverlay.None })
                    }
                    add(PanelRow.Header("Audio delay"))
                    AudioDelay.STEPS.forEach { ms ->
                        add(
                            PanelRow.Option(if (ms == 0) "Off" else "%+d ms".format(ms), null, audioDelayMs == ms) {
                                audioDelayMs = ms
                                audioDelay.millis = ms
                            },
                        )
                    }
                    add(PanelRow.Header("Subtitles"))
                    subs.forEach { o ->
                        add(PanelRow.Option(o.title, o.detail, o.selected) { player.selectTrack(C.TRACK_TYPE_TEXT, o); overlay = LiveOverlay.None })
                    }
                },
                emptyText = "No tracks",
            )
        }
    }
}

@Composable
private fun InfoBanner(channel: Channel, onNow: Program?, upNext: Program?, now: Long) {
    val context = LocalContext.current
    val timeFormat = remember { DateFormat.getTimeFormat(context) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
            .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = 40.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(
            Modifier.size(width = 150.dp, height = 84.dp).background(ShieldColors.Surface.copy(alpha = 0.8f), RoundedCornerShape(10.dp)).padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(channel.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${channel.number}", style = MaterialTheme.typography.titleLarge, color = ShieldColors.Accent)
                Text(channel.name, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (channel.favorite) ShieldIcon(ShieldIcons.Heart, contentDescription = "Favorite", size = 18.dp, tint = ShieldColors.Accent)
            }
            if (onNow != null) {
                Text(onNow.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(timeFormat.format(Date(onNow.start)), style = MaterialTheme.typography.labelLarge, color = ShieldColors.OnSurfaceMuted)
                    Box(Modifier.weight(1f).height(4.dp).background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(onNow.progress(now)).height(4.dp).background(ShieldColors.Accent, RoundedCornerShape(2.dp)))
                    }
                    Text(timeFormat.format(Date(onNow.stop)), style = MaterialTheme.typography.labelLarge, color = ShieldColors.OnSurfaceMuted)
                }
            } else {
                Text("No guide information", style = MaterialTheme.typography.titleMedium, color = ShieldColors.OnSurfaceMuted)
            }
            upNext?.let {
                Text(
                    "Next  ${timeFormat.format(Date(it.start))}  ${it.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShieldColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Channel list over the picture: what's on everywhere, without leaving the channel. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MiniGuide(
    channels: List<Channel>,
    currentIndex: Int,
    nowNext: Map<String, List<Program>>,
    now: Long,
    filterLabel: String,
    onPick: (Int) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))
    val currentFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { currentFocus.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxHeight()
            .width(520.dp)
            .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.75f))))
            .padding(top = 36.dp, start = 24.dp, end = 16.dp)
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        Text(filterLabel, style = MaterialTheme.typography.titleLarge, color = ShieldColors.OnSurface, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp))
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(channels, key = { _, ch -> ch.id }) { i, ch ->
                val onNow = ch.epgId?.let { nowNext[it] }?.firstOrNull { it.isAiring(now) }
                ShieldListItem(
                    selected = i == currentIndex,
                    onClick = { onPick(i) },
                    leadingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${ch.number}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(34.dp))
                            AsyncImage(ch.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(48.dp, 30.dp))
                        }
                    },
                    headlineContent = { Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(onNow?.title ?: "No guide information", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    modifier = if (i == currentIndex) Modifier.focusRequester(currentFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun Spinner(modifier: Modifier = Modifier) {
    val angle by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "angle",
    )
    Canvas(modifier.size(44.dp)) {
        drawArc(Color.White.copy(alpha = 0.85f), angle, 270f, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}
