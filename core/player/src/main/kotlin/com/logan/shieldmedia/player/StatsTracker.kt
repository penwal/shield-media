package com.logan.shieldmedia.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink

data class PlaybackStats(val lines: List<Pair<String, String>>)

/** Collects what the player is actually doing (decoders, passthrough, drops) for the stats overlay. */
class StatsTracker : AnalyticsListener {
    private var videoDecoder = "-"
    private var audioDecoder = "-"
    private var audioOutput = "-"
    private var droppedFrames = 0

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        videoDecoder = decoderName
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        audioDecoder = decoderName
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        val pcm = Util.isEncodingLinearPcm(audioTrackConfig.encoding)
        audioOutput = if (pcm) {
            "PCM (decoded) ${audioTrackConfig.sampleRate / 1000} kHz"
        } else {
            "Passthrough ${encodingName(audioTrackConfig.encoding)}"
        }
        if (!pcm) audioDecoder = "none (bitstream)"
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        this.droppedFrames += droppedFrames
    }

    fun snapshot(player: ExoPlayer, displayMode: String): PlaybackStats {
        val v = player.videoFormat
        val a = player.audioFormat
        val buffered = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0) / 1000f
        return PlaybackStats(
            listOf(
                "Video" to (v?.let {
                    val fps = if (it.frameRate > 0) " @ ${"%.3f".format(it.frameRate)} fps" else ""
                    "${codecName(it.effectiveMime())} ${it.width}x${it.height}$fps"
                } ?: "-"),
                "Range" to hdrLabel(v),
                "Video decoder" to videoDecoder,
                "Bitrate" to (v?.bitrate?.takeIf { it > 0 }?.let { "%.1f Mbps".format(it / 1e6) } ?: "-"),
                "Audio" to (a?.let {
                    listOfNotNull(
                        codecName(it.effectiveMime()),
                        it.channelCount.takeIf { c -> c > 0 }?.let(::channelsLabel),
                    ).joinToString(" ")
                } ?: "-"),
                "Audio output" to audioOutput,
                "Audio decoder" to audioDecoder,
                "Display" to displayMode,
                "Dropped frames" to droppedFrames.toString(),
                "Buffer" to "%.1f s".format(buffered),
            ),
        )
    }

    private fun hdrLabel(f: Format?): String = when {
        f == null -> "-"
        f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision ${f.codecs.orEmpty()}".trim()
        f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
        f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
        else -> "SDR"
    }

    private fun encodingName(encoding: Int): String = when (encoding) {
        C.ENCODING_AC3 -> "Dolby Digital"
        C.ENCODING_E_AC3 -> "Dolby Digital+"
        C.ENCODING_E_AC3_JOC -> "Dolby Digital+ Atmos"
        C.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
        C.ENCODING_DTS -> "DTS"
        C.ENCODING_DTS_HD -> "DTS-HD"
        else -> "encoding $encoding"
    }
}
