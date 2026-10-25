package com.logan.shieldmedia.player

import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Lip-sync adjustment. Positive values delay the sound relative to the picture (for when audio
 * arrives early); negative values advance it (for soundbars/receivers that add latency).
 */
class AudioDelay {
    @Volatile
    var millis: Int = 0

    companion object {
        val STEPS = listOf(-300, -200, -100, -50, 0, 50, 100, 200, 300)

        fun label(ms: Int): String = when {
            ms == 0 -> "Off"
            ms > 0 -> "Audio later by $ms ms"
            else -> "Audio earlier by ${-ms} ms"
        }
    }
}

/**
 * Video frames are released against the audio clock, so offsetting the reported audio
 * position moves the picture relative to the sound without touching the audio itself.
 */
internal class DelayedAudioSink(sink: AudioSink, private val delay: AudioDelay) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (position == AudioSink.CURRENT_POSITION_NOT_SET) return position
        return position + delay.millis * 1_000L
    }
}
