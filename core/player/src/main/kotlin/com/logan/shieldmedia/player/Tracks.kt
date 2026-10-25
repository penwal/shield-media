package com.logan.shieldmedia.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import java.util.Locale

data class TrackOption(
    val title: String,
    val detail: String?,
    val selected: Boolean,
    val group: Tracks.Group?,
    val trackIndex: Int,
)

fun Tracks.selectedVideoFormat(): Format? =
    groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.let { group ->
        (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat)
    }

private fun Player.optionsFor(type: Int): List<TrackOption> =
    currentTracks.groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length)
            .filter { group.isTrackSupported(it, /* allowExceedsCapabilities = */ true) }
            .map { i ->
                val f = group.getTrackFormat(i)
                val language = f.languageName()
                TrackOption(
                    title = language ?: f.label ?: "Track ${i + 1}",
                    detail = listOfNotNull(
                        codecName(f.effectiveMime()),
                        f.channelCount.takeIf { it > 0 }?.let(::channelsLabel),
                        f.label?.takeIf { language != null && it != language },
                        "Forced".takeIf { f.selectionFlags and C.SELECTION_FLAG_FORCED != 0 },
                    ).joinToString(" · ").ifEmpty { null },
                    selected = group.isTrackSelected(i),
                    group = group,
                    trackIndex = i,
                )
            }
    }

fun Player.audioOptions(): List<TrackOption> = optionsFor(C.TRACK_TYPE_AUDIO)

fun Player.subtitleOptions(): List<TrackOption> {
    val tracks = optionsFor(C.TRACK_TYPE_TEXT)
    val off = TrackOption("Off", null, tracks.none { it.selected }, null, -1)
    return listOf(off) + tracks
}

fun Player.selectTrack(type: Int, option: TrackOption) {
    trackSelectionParameters = trackSelectionParameters.buildUpon().apply {
        val group = option.group
        if (group == null) {
            setTrackTypeDisabled(type, true)
        } else {
            setTrackTypeDisabled(type, false)
            setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
    }.build()
}

private fun Format.languageName(): String? =
    language?.takeUnless { it == C.LANGUAGE_UNDETERMINED }
        ?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }

/** Subtitles parsed during extraction report a Media3-internal MIME; the real one moves to `codecs`. */
internal fun Format.effectiveMime(): String? =
    if (sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) codecs else sampleMimeType

internal fun channelsLabel(count: Int): String = when (count) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "${count}ch"
}

internal fun codecName(mime: String?): String = when (mime) {
    MimeTypes.VIDEO_H264 -> "H.264"
    MimeTypes.VIDEO_H265 -> "HEVC"
    MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
    MimeTypes.VIDEO_VP9 -> "VP9"
    MimeTypes.VIDEO_AV1 -> "AV1"
    MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
    MimeTypes.VIDEO_VC1 -> "VC-1"
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_AC3 -> "Dolby Digital"
    MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
    MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+ Atmos"
    MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
    MimeTypes.AUDIO_DTS -> "DTS"
    MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
    MimeTypes.AUDIO_DTS_X -> "DTS:X"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_ALAC -> "ALAC"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.AUDIO_RAW -> "PCM"
    MimeTypes.APPLICATION_SUBRIP -> "SRT"
    MimeTypes.TEXT_SSA -> "ASS/SSA"
    MimeTypes.APPLICATION_PGS -> "PGS"
    MimeTypes.TEXT_VTT -> "WebVTT"
    MimeTypes.APPLICATION_TTML -> "TTML"
    MimeTypes.APPLICATION_DVBSUBS -> "DVB"
    MimeTypes.APPLICATION_VOBSUB -> "VobSub"
    MimeTypes.APPLICATION_TX3G -> "Timed Text"
    MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708 -> "Closed captions"
    null -> "Unknown"
    else -> mime.substringAfter('/').uppercase()
}
