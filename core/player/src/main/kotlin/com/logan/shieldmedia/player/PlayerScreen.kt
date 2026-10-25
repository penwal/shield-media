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
import androidx.compose.ui.draw.clip
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
import com.logan.shieldmedia.designsystem.ShieldIcon
import com.logan.shieldmedia.designsystem.ShieldIcons
import com.logan.shieldmedia.designsystem.SpecBadge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class OsdFocus { PlayPause, SeekBar }
private enum class TrackPanel { Audio, Subtitles, Settings }
private enum class Zoom(val label: String) { Fit("Fit"), Fill("Fill screen") }
private enum class SubtitleSize(val label: String, val scale: Float) {
    Small("Small", 0.85f), Normal("Normal", 1.15f), Large("Large", 1.45f)
}


private const val OSD_TIMEOUT_MS = 5_000L
private const val REPORT_INTERVAL_MS = 10_000L
private const val UP_NEXT_SECONDS = 10
private const val SEEK_BACK_MS = 10_000L
private const val SEEK_FORWARD_MS = 30_000L
private const val SEEK_COMMIT_MS = 650L

@Composable
fun PlayerScreen(
    uri: String,
    title: String,
    config: PlaybackConfig,
    onExit: () -> Unit,
    startPositionMs: Long = 0L,
    /** What is being played, e.g. "Direct Play · Original"; shown under the title and in stats. */
    sourceLabel: String? = null,
    /** Progress reporting, skip markers, Up Next and fallback for library content (Plex). */
    session: PlaybackSession? = null,
    /** Start with the controls hidden, like a cinema; any key brings them up. */
    theater: Boolean = false,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioDelay = remember { AudioDelay() }
    var audioDelayMs by remember { mutableIntStateOf(0) }
    val player = remember { PlayerFactory.create(context, config, audioDelay = audioDelay) }
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

    var osdVisible by remember { mutableStateOf(!theater) }
    var osdFocus by remember { mutableStateOf(OsdFocus.PlayPause) }
    var lastInteraction by remember { mutableLongStateOf(0L) }
    var panel by remember { mutableStateOf<TrackPanel?>(null) }
    var statsVisible by remember { mutableStateOf(config.showStats) }
    var zoom by remember { mutableStateOf(Zoom.Fit) }
    var subtitleSize by remember { mutableStateOf(SubtitleSize.Normal) }
    var statsSnapshot by remember { mutableStateOf<PlaybackStats?>(null) }
    var label by remember { mutableStateOf(sourceLabel) }
    var triedFallback by remember { mutableStateOf(false) }
    // A fallback stream starts at the resume point, so its timeline is offset from the file's.
    var timelineOffsetMs by remember { mutableLongStateOf(0L) }
    var upNext by remember { mutableStateOf<UpNext?>(null) }
    // Skips accumulate into a target shown on the seek bar; the actual seek happens once the
    // presses stop, so holding Right scrubs smoothly instead of queueing dozens of network seeks.
    var pendingSeek by remember { mutableStateOf<Long?>(null) }
    var seekInputAt by remember { mutableLongStateOf(0L) }
    var upNextRequested by remember { mutableStateOf(false) }
    var upNextDismissed by remember { mutableStateOf(false) }
    var upNextCountdown by remember { mutableIntStateOf(UP_NEXT_SECONDS) }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val subtitlesFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    // When a track panel closes, focus goes back to the button that opened it.
    var returnFocus by remember { mutableStateOf<FocusRequester?>(null) }

    fun fileDuration() = player.duration.coerceAtLeast(0L) + timelineOffsetMs
    fun filePosition() = player.currentPosition + timelineOffsetMs

    /** Original file failed here: ask the session for a converted stream once. */
    fun tryFallback(cause: PlaybackException?): Boolean {
        val s = session ?: return false
        if (triedFallback) return false
        triedFallback = true
        val resumeAt = filePosition()
        scope.launch {
            val uri = s.fallbackUri(resumeAt)
            if (uri == null) {
                error = cause
                return@launch
            }
            error = null
            timelineOffsetMs = resumeAt
            label = "Transcoding · converted by Plex"
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = true
        }
        return true
    }

    fun nudge(deltaMs: Long) {
        val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        pendingSeek = ((pendingSeek ?: player.currentPosition) + deltaMs).coerceIn(0, dur)
        seekInputAt = System.nanoTime()
    }

    fun showOsd(focus: OsdFocus) {
        if (!osdVisible) osdFocus = focus
        osdVisible = true
        lastInteraction = System.nanoTime()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (player.playbackState != Player.STATE_ENDED) {
                    session?.report(if (playing) PlaybackReport.Playing else PlaybackReport.Paused, filePosition(), fileDuration())
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                if (state == Player.STATE_ENDED && session != null) {
                    // Finished: roll into the next episode, or go back to where playback started.
                    if (upNext != null && !upNextDismissed) session.playUpNext() else onExit()
                }
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    videoAspect = size.width * size.pixelWidthHeightRatio / size.height
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                // Decoder, container or audio-output failures mean this device can't play the
                // original file; anything else (network, server) is shown as an error.
                val unsupported = e.errorCode in 3000..3999 || e.errorCode in 4000..4999 || e.errorCode in 5000..5999
                if (!(unsupported && tryFallback(e))) error = e
            }

            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
                // A video track nothing here can decode plays as silence-with-sound; convert instead.
                val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                if (video.isNotEmpty() && video.none { g -> (0 until g.length).any { g.isTrackSupported(it) } }) {
                    tryFallback(null)
                }
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
            session?.report(PlaybackReport.Stopped, filePosition(), fileDuration())
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
        var sinceReport = 0L
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            buffered = player.bufferedPosition
            delay(500)
            sinceReport += 500
            if (sinceReport >= REPORT_INTERVAL_MS && player.isPlaying) {
                sinceReport = 0
                session?.report(PlaybackReport.Playing, filePosition(), fileDuration())
            }
        }
    }

    LaunchedEffect(seekInputAt) {
        val target = pendingSeek ?: return@LaunchedEffect
        delay(SEEK_COMMIT_MS)
        player.seekTo(target)
        position = target
        pendingSeek = null
    }

    // Skip Intro / Skip Credits: whichever marker the playhead is inside.
    val filePos = position + timelineOffsetMs
    val fileDur = duration + timelineOffsetMs
    val activeMarker = session?.markers?.firstOrNull { filePos in it.startMs until it.endMs }
    val creditsStart = session?.markers?.firstOrNull { it.isCredits }?.startMs
    // Up Next shows at the credits, or 30 s from the end when the server found no credits.
    val nearEnd = fileDur > 0 && (
        (creditsStart != null && filePos >= creditsStart) || (creditsStart == null && fileDur - filePos <= 30_000)
        )
    LaunchedEffect(fileDur > 0 && fileDur - filePos < 4 * 60_000) {
        if (session != null && !upNextRequested && fileDur > 0 && fileDur - filePos < 4 * 60_000) {
            upNextRequested = true
            upNext = session.loadUpNext()
        }
    }
    val showUpNext = nearEnd && upNext != null && !upNextDismissed && error == null
    LaunchedEffect(showUpNext) {
        if (!showUpNext) {
            upNextCountdown = UP_NEXT_SECONDS
            return@LaunchedEffect
        }
        while (upNextCountdown > 0) {
            delay(1_000)
            if (player.isPlaying) upNextCountdown--
        }
        session?.playUpNext()
    }

    LaunchedEffect(statsVisible) {
        while (statsVisible) {
            val snap = stats.snapshot(player, frameRateMatcher.currentModeLabel())
            statsSnapshot = label?.let { PlaybackStats(listOf("Source" to it) + snap.lines) } ?: snap
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
            showUpNext -> upNextDismissed = true
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
                        nudge(SEEK_FORWARD_MS); showOsd(OsdFocus.SeekBar); true
                    }
                    Key.MediaRewind -> {
                        nudge(-SEEK_BACK_MS); showOsd(OsdFocus.SeekBar); true
                    }
                    else -> if (osdVisible || panel != null) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                when {
                                    showUpNext -> session?.playUpNext()
                                    activeMarker != null -> player.seekTo(activeMarker.endMs - timelineOffsetMs)
                                    else -> {
                                        player.togglePlayPause()
                                        showOsd(OsdFocus.PlayPause)
                                    }
                                }
                                true
                            }
                            Key.DirectionLeft -> {
                                nudge(-SEEK_BACK_MS); showOsd(OsdFocus.SeekBar); true
                            }
                            Key.DirectionRight -> {
                                nudge(SEEK_FORWARD_MS); showOsd(OsdFocus.SeekBar); true
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
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val screenAspect = maxWidth / maxHeight
            // Fill crops to cover the screen (never stretches); Fit letterboxes.
            val surfaceModifier = when {
                zoom == Zoom.Fit -> Modifier.aspectRatio(videoAspect)
                videoAspect > screenAspect -> Modifier.requiredSize(maxHeight * videoAspect, maxHeight)
                else -> Modifier.requiredSize(maxWidth, maxWidth / videoAspect)
            }
            AndroidView(
                factory = { ctx ->
                    // SurfaceView (not TextureView) is required for HDR/Dolby Vision output and lowest power.
                    SurfaceView(ctx).apply {
                        keepScreenOn = true
                        player.setVideoSurfaceView(this)
                    }
                },
                modifier = surfaceModifier,
            )
        }

        AndroidView(
            factory = { ctx -> SubtitleView(ctx).apply { applyTvStyle() } },
            update = {
                it.setCues(cues)
                it.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleSize.scale)
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
                sourceLabel = label,
                isPlaying = isPlaying,
                position = pendingSeek ?: position,
                duration = duration,
                buffered = buffered,
                playFocus = playFocus,
                seekFocus = seekFocus,
                audioFocus = audioFocus,
                subtitlesFocus = subtitlesFocus,
                settingsFocus = settingsFocus,
                onTogglePlay = { player.togglePlayPause() },
                onSeekBack = { nudge(-SEEK_BACK_MS) },
                onSeekForward = { nudge(SEEK_FORWARD_MS) },
                onAudio = {
                    returnFocus = audioFocus
                    panel = TrackPanel.Audio
                },
                onSubtitles = {
                    returnFocus = subtitlesFocus
                    panel = TrackPanel.Subtitles
                },
                onSettings = {
                    returnFocus = settingsFocus
                    panel = TrackPanel.Settings
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
            val rows: List<PanelRow> = when (kind) {
                TrackPanel.Audio, TrackPanel.Subtitles -> {
                    val type = if (kind == TrackPanel.Audio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
                    val options = remember(tracksVersion, kind) {
                        if (kind == TrackPanel.Audio) player.audioOptions() else player.subtitleOptions()
                    }
                    options.map { option ->
                        PanelRow.Option(option.title, option.detail, option.selected) {
                            player.selectTrack(type, option)
                            panel = null
                        }
                    }
                }
                TrackPanel.Settings -> buildList {
                    add(PanelRow.Header("Quality"))
                    // Always the untouched original file; transcoding is only ever a fallback.
                    add(PanelRow.Option(if (timelineOffsetMs > 0 || triedFallback) "Converted by Plex" else "Original", label ?: "Plays the stream as-is", true) {})
                    add(PanelRow.Header("Audio delay"))
                    AudioDelay.STEPS.forEach { ms ->
                        add(
                            PanelRow.Option(if (ms == 0) "Off" else "%+d ms".format(ms), null, audioDelayMs == ms) {
                                audioDelayMs = ms
                                audioDelay.millis = ms
                            },
                        )
                    }
                    add(PanelRow.Header("Zoom"))
                    Zoom.entries.forEach { z -> add(PanelRow.Option(z.label, null, zoom == z) { zoom = z }) }
                    add(PanelRow.Header("Subtitle size"))
                    SubtitleSize.entries.forEach { size ->
                        add(PanelRow.Option(size.label, null, subtitleSize == size) { subtitleSize = size })
                    }
                }
            }
            OptionsPanel(
                title = when (kind) {
                    TrackPanel.Audio -> "Audio"
                    TrackPanel.Subtitles -> "Subtitles"
                    TrackPanel.Settings -> "Playback"
                },
                rows = rows,
                emptyText = "No tracks available",
            )
        }

        // Skip and Up Next sit bottom-right, above where the OSD controls appear.
        val promptModifier = Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = if (osdVisible) 170.dp else 56.dp)
        val next = upNext
        AnimatedVisibility(
            visible = showUpNext && next != null && panel == null,
            enter = fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 4 },
            exit = fadeOut(tween(150)),
            modifier = promptModifier,
        ) {
            if (next != null) UpNextCard(next, upNextCountdown, osdHidden = !osdVisible)
        }
        AnimatedVisibility(
            visible = activeMarker != null && !showUpNext && panel == null && error == null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(150)),
            modifier = promptModifier,
        ) {
            SkipPrompt(activeMarker?.label ?: "", osdHidden = !osdVisible)
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
    sourceLabel: String?,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    buffered: Long,
    playFocus: FocusRequester,
    seekFocus: FocusRequester,
    audioFocus: FocusRequester,
    subtitlesFocus: FocusRequester,
    settingsFocus: FocusRequester,
    onTogglePlay: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onSettings: () -> Unit,
    onStats: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))),
        ) {
            Column(
                Modifier.padding(horizontal = 56.dp, vertical = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                if (sourceLabel != null) SpecBadge(sourceLabel)
            }
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
                OsdButton(ShieldIcons.Back10, "Back 10 seconds", onSeekBack)
                OsdButton(
                    icon = if (isPlaying) ShieldIcons.Pause else ShieldIcons.Play,
                    label = if (isPlaying) "Pause" else "Play",
                    onClick = onTogglePlay,
                    modifier = Modifier.focusRequester(playFocus),
                )
                OsdButton(ShieldIcons.Forward30, "Forward 30 seconds", onSeekForward)
                Spacer(Modifier.weight(1f))
                OsdButton(ShieldIcons.Audio, "Audio", onAudio, Modifier.focusRequester(audioFocus))
                OsdButton(ShieldIcons.Subtitles, "Subtitles", onSubtitles, Modifier.focusRequester(subtitlesFocus))
                OsdButton(ShieldIcons.Tune, "Playback settings", onSettings, Modifier.focusRequester(settingsFocus))
                OsdButton(ShieldIcons.Info, "Playback stats", onStats)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OsdButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        ShieldIcon(icon, contentDescription = label)
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

@Composable
private fun SkipPrompt(text: String, osdHidden: Boolean) {
    Row(
        Modifier
            .background(Color.White, RoundedCornerShape(50))
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = ShieldColors.Background)
        if (osdHidden) {
            Text("OK", style = MaterialTheme.typography.labelSmall, color = ShieldColors.OnSurfaceFaint)
        }
    }
}

@Composable
private fun UpNextCard(next: UpNext, secondsLeft: Int, osdHidden: Boolean) {
    Row(
        Modifier
            .width(460.dp)
            .background(ShieldColors.Surface.copy(alpha = 0.95f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).background(ShieldColors.SurfaceHigh, RoundedCornerShape(10.dp))) {
            coil3.compose.AsyncImage(
                model = next.imageUrl,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("UP NEXT  ·  ${secondsLeft}s", style = MaterialTheme.typography.labelSmall, color = ShieldColors.Accent)
            Text(next.title, style = MaterialTheme.typography.titleMedium, color = ShieldColors.OnSurface, maxLines = 1)
            next.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = ShieldColors.OnSurfaceMuted, maxLines = 1)
            }
            Text(
                if (osdHidden) "OK to play now · Back to keep watching" else "Back to keep watching",
                style = MaterialTheme.typography.labelSmall,
                color = ShieldColors.OnSurfaceFaint,
            )
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
