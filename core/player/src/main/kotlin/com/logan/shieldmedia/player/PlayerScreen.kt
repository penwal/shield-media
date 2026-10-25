package com.logan.shieldmedia.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.ShieldColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class OsdFocus { PlayPause, SeekBar }
private enum class TrackPanel { Audio, Subtitles }

private const val OSD_TIMEOUT_MS = 5_000L

@Composable
fun PlayerScreen(
    uri: String,
    title: String,
    config: PlaybackConfig,
    onExit: () -> Unit,
    startPositionMs: Long = 0L,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val player = remember { PlayerFactory.create(context, config) }
    val stats = remember { StatsTracker() }
    val frameRateMatcher = remember(activity) { FrameRateMatcher(activity) }
    val scope = rememberCoroutineScope()

    var isPlaying by remember { mutableStateOf(false) }
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var videoAspect by remember { mutableFloatStateOf(16f / 9f) }
    var error by remember { mutableStateOf<PlaybackException?>(null) }
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var tracksVersion by remember { mutableIntStateOf(0) }
    var frameRateHandled by remember { mutableStateOf(!config.autoFrameRate) }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }

    var osdVisible by remember { mutableStateOf(true) }
    var osdFocus by remember { mutableStateOf(OsdFocus.PlayPause) }
    var lastInteraction by remember { mutableLongStateOf(0L) }
    var panel by remember { mutableStateOf<TrackPanel?>(null) }
    var statsVisible by remember { mutableStateOf(config.showStats) }
    var statsSnapshot by remember { mutableStateOf<PlaybackStats?>(null) }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val subtitlesFocus = remember { FocusRequester() }
    // When a track panel closes, focus goes back to the button that opened it.
    var returnFocus by remember { mutableStateOf<FocusRequester?>(null) }

    fun showOsd(focus: OsdFocus) {
        if (!osdVisible) osdFocus = focus
        osdVisible = true
        lastInteraction = System.nanoTime()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    videoAspect = size.width * size.pixelWidthHeightRatio / size.height
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                error = e
            }

            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
                val fps = tracks.selectedVideoFormat()?.frameRate ?: return
                if (frameRateHandled || fps <= 0f) return
                frameRateHandled = true
                if (!frameRateMatcher.needsSwitch(fps)) return
                scope.launch {
                    val resume = player.playWhenReady
                    player.playWhenReady = false
                    frameRateMatcher.switchAndAwait(fps)
                    player.playWhenReady = resume
                }
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(stats)
        player.setMediaItem(MediaItem.fromUri(uri), startPositionMs)
        player.prepare()
        player.playWhenReady = true
        onDispose {
            player.removeListener(listener)
            player.release()
            frameRateMatcher.restore()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            buffered = player.bufferedPosition
            delay(500)
        }
    }

    LaunchedEffect(statsVisible) {
        while (statsVisible) {
            statsSnapshot = stats.snapshot(player, frameRateMatcher.currentModeLabel())
            delay(1_000)
        }
    }

    LaunchedEffect(osdVisible, lastInteraction, isPlaying, panel) {
        if (osdVisible && isPlaying && panel == null) {
            delay(OSD_TIMEOUT_MS)
            osdVisible = false
        }
    }

    LaunchedEffect(osdVisible, panel) {
        if (panel != null) return@LaunchedEffect
        withFrameNanos { } // wait for the OSD to be composed before moving focus into it
        val target = when {
            !osdVisible -> rootFocus
            returnFocus != null -> returnFocus!!
            osdFocus == OsdFocus.SeekBar -> seekFocus
            else -> playFocus
        }
        returnFocus = null
        runCatching { target.requestFocus() }
    }

    BackHandler {
        when {
            panel != null -> panel = null
            osdVisible -> osdVisible = false
            else -> onExit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                lastInteraction = System.nanoTime()
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.MediaPlayPause -> {
                        player.togglePlayPause(); showOsd(OsdFocus.PlayPause); true
                    }
                    Key.MediaFastForward -> {
                        player.seekForward(); showOsd(OsdFocus.SeekBar); true
                    }
                    Key.MediaRewind -> {
                        player.seekBack(); showOsd(OsdFocus.SeekBar); true
                    }
                    else -> if (osdVisible || panel != null) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                player.togglePlayPause(); showOsd(OsdFocus.PlayPause); true
                            }
                            Key.DirectionLeft -> {
                                player.seekBack(); showOsd(OsdFocus.SeekBar); true
                            }
                            Key.DirectionRight -> {
                                player.seekForward(); showOsd(OsdFocus.SeekBar); true
                            }
                            Key.DirectionUp, Key.DirectionDown -> {
                                showOsd(OsdFocus.PlayPause); true
                            }
                            else -> false
                        }
                    }
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    // SurfaceView (not TextureView) is required for HDR/Dolby Vision output and lowest power.
                    SurfaceView(ctx).apply {
                        keepScreenOn = true
                        player.setVideoSurfaceView(this)
                    }
                },
                modifier = Modifier.aspectRatio(videoAspect),
            )
        }

        AndroidView(
            factory = { ctx -> SubtitleView(ctx).apply { applyTvStyle() } },
            update = {
                it.setCues(cues)
                // Lift subtitles clear of the controls while the OSD is up.
                it.setBottomPaddingFraction(if (osdVisible) 0.28f else 0.06f)
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (playbackState == Player.STATE_BUFFERING && error == null) {
            Spinner(Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
            visible = osdVisible && error == null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
        ) {
            Osd(
                title = title,
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                buffered = buffered,
                playFocus = playFocus,
                seekFocus = seekFocus,
                audioFocus = audioFocus,
                subtitlesFocus = subtitlesFocus,
                onTogglePlay = { player.togglePlayPause() },
                onSeekBack = { player.seekBack() },
                onSeekForward = { player.seekForward() },
                onAudio = {
                    returnFocus = audioFocus
                    panel = TrackPanel.Audio
                },
                onSubtitles = {
                    returnFocus = subtitlesFocus
                    panel = TrackPanel.Subtitles
                },
                onStats = { statsVisible = !statsVisible },
            )
        }

        val snapshot = statsSnapshot
        if (statsVisible && snapshot != null) {
            StatsOverlay(snapshot, Modifier.align(Alignment.TopEnd).padding(32.dp))
        }

        AnimatedVisibility(
            visible = panel != null,
            enter = slideInHorizontally(tween(250)) { it / 3 } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(200)) { it / 3 } + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            val kind = panel ?: TrackPanel.Audio
            val options = remember(tracksVersion, kind) {
                if (kind == TrackPanel.Audio) player.audioOptions() else player.subtitleOptions()
            }
            TrackPanelView(
                title = if (kind == TrackPanel.Audio) "Audio" else "Subtitles",
                options = options,
                onSelect = { option ->
                    val type = if (kind == TrackPanel.Audio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
                    player.selectTrack(type, option)
                    panel = null
                },
            )
        }

        error?.let { ErrorOverlay(it, Modifier.align(Alignment.Center)) }
    }
}

private fun Player.togglePlayPause() {
    when {
        isPlaying -> pause()
        playbackState == Player.STATE_ENDED -> {
            seekTo(0)
            play()
        }
        else -> play()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Osd(
    title: String,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    buffered: Long,
    playFocus: FocusRequester,
    seekFocus: FocusRequester,
    audioFocus: FocusRequester,
    subtitlesFocus: FocusRequester,
    onTogglePlay: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onStats: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 56.dp, vertical = 40.dp),
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(start = 56.dp, end = 56.dp, top = 64.dp, bottom = 36.dp),
        ) {
            SeekBar(
                position = position,
                duration = duration,
                buffered = buffered,
                focusRequester = seekFocus,
                onSeekBack = onSeekBack,
                onSeekForward = onSeekForward,
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(formatTime(position), style = MaterialTheme.typography.labelLarge, color = Color.White)
                Spacer(Modifier.weight(1f))
                Text(
                    if (duration > 0) "-${formatTime(duration - position)}" else "LIVE",
                    style = MaterialTheme.typography.labelLarge,
                    color = ShieldColors.OnSurfaceMuted,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OsdButton(Icons.Filled.Replay10, "Back 10 seconds", onSeekBack)
                OsdButton(
                    icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    label = if (isPlaying) "Pause" else "Play",
                    onClick = onTogglePlay,
                    modifier = Modifier.focusRequester(playFocus),
                )
                OsdButton(Icons.Filled.Forward30, "Forward 30 seconds", onSeekForward)
                Spacer(Modifier.weight(1f))
                OsdButton(Icons.Filled.Audiotrack, "Audio", onAudio, Modifier.focusRequester(audioFocus))
                OsdButton(Icons.Filled.ClosedCaption, "Subtitles", onSubtitles, Modifier.focusRequester(subtitlesFocus))
                OsdButton(Icons.Filled.Info, "Playback stats", onStats)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OsdButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
private fun SeekBar(
    position: Long,
    duration: Long,
    buffered: Long,
    focusRequester: FocusRequester,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val played = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val loaded = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onSeekBack(); true }
                    Key.DirectionRight -> { onSeekForward(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        val barHeight = if (focused) 8.dp.toPx() else 4.dp.toPx()
        val top = (size.height - barHeight) / 2
        val radius = CornerRadius(barHeight / 2)
        drawRoundRect(Color.White.copy(alpha = 0.2f), Offset(0f, top), Size(size.width, barHeight), radius)
        drawRoundRect(Color.White.copy(alpha = 0.4f), Offset(0f, top), Size(size.width * loaded, barHeight), radius)
        drawRoundRect(ShieldColors.Accent, Offset(0f, top), Size(size.width * played, barHeight), radius)
        if (focused) {
            drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(size.width * played, size.height / 2))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TrackPanelView(
    title: String,
    options: List<TrackOption>,
    onSelect: (TrackOption) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val focusIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    LaunchedEffect(options) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxHeight()
            .width(440.dp)
            .background(ShieldColors.Surface.copy(alpha = 0.96f))
            .padding(vertical = 40.dp, horizontal = 20.dp)
            // Modal: D-pad must not wander into the OSD behind the panel. Back closes it.
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = ShieldColors.OnSurface,
            modifier = Modifier.padding(start = 12.dp, bottom = 16.dp),
        )
        if (options.isEmpty()) {
            Text("No tracks available", color = ShieldColors.OnSurfaceMuted, modifier = Modifier.padding(12.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(options) { index, option ->
                ListItem(
                    selected = option.selected,
                    onClick = { onSelect(option) },
                    headlineContent = { Text(option.title) },
                    supportingContent = option.detail?.let { { Text(it) } },
                    trailingContent = if (option.selected) {
                        { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                    } else {
                        null
                    },
                    modifier = if (index == focusIndex) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun StatsOverlay(stats: PlaybackStats, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        stats.lines.forEach { (label, value) ->
            Row {
                Text(
                    label,
                    color = ShieldColors.OnSurfaceMuted,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(140.dp),
                )
                Text(value, color = Color.White, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ErrorOverlay(error: PlaybackException, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(ShieldColors.Surface, RoundedCornerShape(16.dp))
            .padding(32.dp)
            .width(640.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Can't play this", style = MaterialTheme.typography.headlineSmall, color = ShieldColors.OnSurface)
        Text(error.errorCodeName, style = MaterialTheme.typography.titleSmall, color = ShieldColors.Accent)
        val detail = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.distinct().joinToString("\n")
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = ShieldColors.OnSurfaceMuted, maxLines = 6)
        Text("Press Back to exit", style = MaterialTheme.typography.labelLarge, color = ShieldColors.OnSurfaceMuted)
    }
}

@Composable
private fun Spinner(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "angle",
    )
    Canvas(modifier.size(56.dp)) {
        drawArc(
            color = Color.White.copy(alpha = 0.9f),
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

private fun SubtitleView.applyTvStyle() {
    setApplyEmbeddedStyles(true)
    setApplyEmbeddedFontSizes(false)
    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.15f)
    setBottomPaddingFraction(0.06f)
    setStyle(
        CaptionStyleCompat(
            android.graphics.Color.WHITE,
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK,
            null,
        ),
    )
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("PlayerScreen must be hosted in an Activity")
}
