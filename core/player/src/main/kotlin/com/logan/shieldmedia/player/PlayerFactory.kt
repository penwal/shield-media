package com.logan.shieldmedia.player

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import java.util.Locale

data class PlaybackConfig(
    val autoFrameRate: Boolean = true,
    val audioPassthrough: Boolean = false,
    val showStats: Boolean = false,
    val userAgent: String = "ShieldMedia/0.1",
    /** Live TV: how much video to hold before starting (and twice that after a stall). */
    val liveBufferMs: Int = 3_000,
    /** Live TV: cover the picture during detected commercial breaks. */
    val hideCommercials: Boolean = false,
)

object PlayerFactory {
    private const val VOD_BUFFER_BYTES = 200 * 1024 * 1024

    /** [live]: small start buffer so channel changes are fast (TiviMate-style zapping). */
    fun create(
        context: Context,
        config: PlaybackConfig,
        live: Boolean = false,
        audioDelay: AudioDelay = AudioDelay(),
    ): ExoPlayer {
        // Platform (hardware) decoders first; FFmpeg only for formats the Shield can't decode itself
        // (TrueHD, DTS, DTS-HD when passthrough is off).
        val renderers = ShieldRenderersFactory(context, config.audioPassthrough, audioDelay)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(config.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            // A live stream that goes quiet for 8 s is dead: reconnect rather than sit frozen.
            .setReadTimeoutMs(if (live) 8_000 else 20_000)

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setPreferredAudioLanguage(Locale.getDefault().language)
                .build()
        }

        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .apply {
                if (live) {
                    // Live streams arrive in real time, so the buffer never grows much past what we
                    // wait for at the start. Too little and any network hiccup freezes the picture.
                    val start = config.liveBufferMs
                    setLoadControl(
                        DefaultLoadControl.Builder()
                            .setBufferDurationsMs(maxOf(15_000, start * 4), 50_000, start, start * 2)
                            .build(),
                    )
                } else {
                    // Files: read well ahead so Wi-Fi dips don't stall playback. The default caps the
                    // buffer at ~128 MB, under 20 s of a 4K remux; this holds ~30 s of one (needs largeHeap).
                    setLoadControl(
                        DefaultLoadControl.Builder()
                            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
                            .setTargetBufferBytes(VOD_BUFFER_BYTES)
                            .build(),
                    )
                }
            }
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
            .apply {
                // Debug builds log every buffering/drop/error event under the "EventLogger" tag.
                if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    addAnalyticsListener(EventLogger())
                }
                if (config.autoFrameRate) {
                    // FrameRateMatcher switches the HDMI display mode itself; stop Media3 from also hinting.
                    videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
                }
            }
    }
}

/**
 * With passthrough off, the audio sink is told the output only supports PCM, so every
 * compressed format is decoded on the Shield. With it on, HDMI capabilities decide.
 */
private class ShieldRenderersFactory(
    context: Context,
    private val passthrough: Boolean,
    private val audioDelay: AudioDelay,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
        if (!passthrough) {
            @Suppress("DEPRECATION")
            builder.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
        }
        return DelayedAudioSink(builder.build(), audioDelay)
    }
}
