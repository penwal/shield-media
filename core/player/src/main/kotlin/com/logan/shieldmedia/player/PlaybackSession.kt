package com.logan.shieldmedia.player

/** A range the viewer can jump past, e.g. an intro or the end credits. */
data class SkipMarker(val label: String, val startMs: Long, val endMs: Long, val isCredits: Boolean)

/** What plays after this item, shown on the Up Next card. */
data class UpNext(val title: String, val subtitle: String?, val imageUrl: String?)

enum class PlaybackReport { Playing, Paused, Stopped }

/**
 * Hooks a content source (Plex) into the generic player: progress reporting, skip markers,
 * Up Next, and a fallback stream when the original file can't be played on this device.
 */
interface PlaybackSession {
    val markers: List<SkipMarker>

    /** Called on play/pause, every few seconds while playing, and once when the player closes. */
    fun report(state: PlaybackReport, positionMs: Long, durationMs: Long)

    /** Loaded near the end of playback; null when there is nothing to play next. */
    suspend fun loadUpNext(): UpNext?

    /** Switch to the Up Next item. */
    fun playUpNext()

    /**
     * A converted stream to use when direct play fails, starting at [positionMs].
     * Null when no fallback exists.
     */
    suspend fun fallbackUri(positionMs: Long): String?
}
