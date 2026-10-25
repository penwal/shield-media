package com.logan.shieldmedia.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.ScreenTitle
import com.logan.shieldmedia.navigation.PlayHandler
import com.logan.shieldmedia.player.PlaybackConfig
import com.logan.shieldmedia.player.PlayerScreen
import com.logan.shieldmedia.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/** Loads the saved player settings once, then hands off to the full-screen player. */
@Composable
fun PlayerRoute(
    uri: String,
    title: String,
    startMs: Long,
    settingsRepository: SettingsRepository,
    onExit: () -> Unit,
) {
    val config by produceState<PlaybackConfig?>(initialValue = null) {
        val s = settingsRepository.playerSettings.first()
        value = PlaybackConfig(
            autoFrameRate = s.autoFrameRate,
            audioPassthrough = s.audioPassthrough,
            showStats = s.showStatsOverlay,
        )
    }
    val loaded = config
    if (loaded == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
    } else {
        PlayerScreen(
            uri = uri,
            title = title,
            startPositionMs = startMs,
            config = loaded,
            onExit = onExit,
            // Plex part URLs are the original file, streamed untouched.
            sourceLabel = if ("/library/parts/" in uri) "Direct Play · Original" else null,
        )
    }
}

private data class TestStream(val name: String, val detail: String, val uri: String)

// Public sample streams from the Media3 demo app. HDR, Dolby Vision and lossless audio
// need real files from Plex on the Shield; none of the public samples cover those.
private val testStreams = listOf(
    TestStream(
        "H.264 + AAC in MKV", "720p progressive file",
        "https://storage.googleapis.com/exoplayer-test-media-1/mkv/android-screens-lavf-56.36.100-aac-avc-main-1280x720.mkv",
    ),
    TestStream(
        "SRT subtitles in MKV", "Subtitle track selection",
        "https://storage.googleapis.com/exoplayer-test-media-1/mkv/android-screens-with-subrip.mkv",
    ),
    TestStream(
        "Overlapping SSA in MKV", "Styled subtitle rendering",
        "https://storage.googleapis.com/exoplayer-test-media-1/mkv/android-screens-with-overlapping-ssa.mkv",
    ),
    TestStream(
        "MP4 with timed text", "tx3g subtitles",
        "https://storage.googleapis.com/exoplayer-test-media-1/mp4/dizzy-with-tx3g.mp4",
    ),
    TestStream(
        "HEVC 1080p (DASH)", "Hardware HEVC decode",
        "https://storage.googleapis.com/wvmedia/clear/hevc/tears/tears.mpd",
    ),
    TestStream(
        "HEVC 4K (DASH)", "4K hardware HEVC decode",
        "https://storage.googleapis.com/wvmedia/clear/hevc/tears/tears_uhd.mpd",
    ),
    TestStream(
        "VP9 4K (DASH)", "Hardware VP9 decode",
        "https://storage.googleapis.com/wvmedia/clear/vp9/tears/tears_uhd.mpd",
    ),
    TestStream(
        "H.264 4K 60fps (DASH)", "High frame rate, frame rate matching",
        "https://storage.googleapis.com/exoplayer-test-media-1/60fps/bbb-clear-2160/manifest.mpd",
    ),
    TestStream(
        "HEVC HDR metadata (Beach)", "HDR signalling on the Shield",
        "https://storage.googleapis.com/exoplayer-test-media-1/hagc/beach_hevc_t35track_v0_20260603.mp4",
    ),
    TestStream(
        "AV1 480p", "Software decode (no AV1 hardware on Shield)",
        "https://storage.googleapis.com/downloads.webmproject.org/av1/exoplayer/bbb-av1-480p.mp4",
    ),
    TestStream(
        "HLS with MPEG-TS segments", "Similar to most IPTV streams",
        "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8",
    ),
    TestStream(
        "HLS fMP4, multiple audio + subtitles", "Adaptive bitrate, track switching",
        "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
    ),
    TestStream(
        "Raw MPEG-TS file", "IPTV-style container",
        "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/gear1/fileSequence0.ts",
    ),
    TestStream(
        "FLAC audio only", "Lossless audio",
        "https://storage.googleapis.com/exoplayer-test-media-1/flac/play.flac",
    ),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerTestScreen(onPlay: PlayHandler) {
    // Saved across the trip to the player so focus lands back on the stream that was played.
    var focusedIndex by rememberSaveable { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Player test streams", "Open one, then press the info button for playback stats")
        LazyColumn(
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(testStreams) { index, stream ->
                ListItem(
                    selected = false,
                    onClick = {
                        focusedIndex = index
                        onPlay(stream.uri, stream.name, 0L)
                    },
                    headlineContent = { Text(stream.name) },
                    supportingContent = { Text(stream.detail) },
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .then(if (index == focusedIndex) Modifier.focusRequester(focusRequester) else Modifier),
                )
            }
        }
    }
}
