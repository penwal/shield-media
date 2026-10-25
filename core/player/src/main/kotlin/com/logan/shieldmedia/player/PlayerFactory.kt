package com.logan.shieldmedia.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.util.Locale

data class PlaybackConfig(
    val autoFrameRate: Boolean = true,
    val audioPassthrough: Boolean = false,
    val showStats: Boolean = false,
    val userAgent: String = "ShieldMedia/0.1",
)

object PlayerFactory {

    fun create(context: Context, config: PlaybackConfig): ExoPlayer {
        // Platform (hardware) decoders first; FFmpeg only for formats the Shield can't decode itself
        // (TrueHD, DTS, DTS-HD when passthrough is off).
        val renderers = ShieldRenderersFactory(context, config.audioPassthrough)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(config.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setPreferredAudioLanguage(Locale.getDefault().language)
                .build()
        }

        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
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
        return builder.build()
    }
}
