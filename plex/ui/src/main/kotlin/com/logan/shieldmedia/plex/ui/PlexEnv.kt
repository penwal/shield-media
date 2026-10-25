package com.logan.shieldmedia.plex.ui

import com.logan.shieldmedia.plex.ActiveServer
import com.logan.shieldmedia.plex.PlexItem
import com.logan.shieldmedia.plex.PlexLibrary
import com.logan.shieldmedia.plex.PlexStream

/** Starts playback of a Plex item; [fromStart] ignores any saved position. */
typealias PlexPlayHandler = (ratingKey: String, fromStart: Boolean) -> Unit

sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Failed(val message: String) : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
}

/** Everything a Plex screen needs: the library API and the server it's talking to. */
class PlexEnv(val library: PlexLibrary, val server: ActiveServer) {

    fun poster(item: PlexItem): String? = library.imageUrl(server, item.posterPath(), 300, 450)
    /** Big single posters (the Blu-Ray deck): sharp at 4K output. */
    fun posterLarge(item: PlexItem): String? = library.imageUrl(server, item.posterPath(), 800, 1200)
    fun landscape(item: PlexItem): String? = library.imageUrl(server, item.landscapePath(), 520, 293)
    fun backdrop(item: PlexItem?): String? = item?.let { library.imageUrl(server, it.artPath(), 1920, 1080) }
    fun logo(item: PlexItem): String? = library.imageUrl(server, item.clearLogo, 900, 360)
    fun person(path: String?): String? = library.imageUrl(server, path, 200, 200)

    /**
     * The sharpest trailer available: every trailer comes in several versions (1080p, 720p,
     * 480p...), so take the highest-resolution, highest-bitrate one, preferring the main trailer.
     */
    suspend fun trailerUrl(ratingKey: String): String? {
        val trailers = library.extras(server, ratingKey).filter { it.subtype == "trailer" }
        val best = trailers.withIndex()
            .flatMap { (i, t) -> t.media.map { m -> Triple(i, m, m.parts.firstOrNull()) } }
            .filter { it.third != null }
            .maxWithOrNull(
                compareBy<Triple<Int, com.logan.shieldmedia.plex.PlexMedia, com.logan.shieldmedia.plex.PlexPart?>>(
                    { it.second.width ?: 0 },
                    { -it.first }, // same resolution: the earlier (main) trailer wins
                    { it.second.bitrate ?: 0 },
                ),
            ) ?: return null
        return library.directPlayUrl(server, best.third!!)
    }

    fun play(item: PlexItem, fromStart: Boolean, onPlay: PlexPlayHandler): Boolean {
        if (item.type !in setOf("movie", "episode")) return false
        onPlay(item.ratingKey, fromStart)
        return true
    }
}

internal fun PlexItem.posterPath(): String? = when (type) {
    "episode" -> grandparentThumb ?: parentThumb ?: thumb
    "season" -> thumb ?: parentThumb
    else -> thumb
}

internal fun PlexItem.landscapePath(): String? = when (type) {
    "episode" -> thumb ?: grandparentArt
    else -> art ?: thumb
}

internal fun PlexItem.artPath(): String? = when (type) {
    "episode", "season" -> grandparentArt ?: art
    else -> art
}

internal fun PlexItem.playerTitle(): String = when (type) {
    "episode" -> listOfNotNull(grandparentTitle, episodeLabel, title).joinToString("  ·  ")
    else -> listOfNotNull(title, year?.toString()).joinToString("  ·  ")
}

/** Card caption pieces: (title, subtitle). Episodes lead with the show name. */
internal fun PlexItem.cardText(): Pair<String, String?> = when (type) {
    "episode" -> (grandparentTitle ?: title) to listOfNotNull(episodeLabel, title).joinToString(" · ")
    "season" -> (parentTitle ?: title) to title
    "movie" -> {
        val offset = viewOffset ?: 0L
        val total = duration
        title to if (offset > 0 && total != null) "${formatDuration(total - offset)} left" else year?.toString()
    }
    else -> title to year?.toString()
}

internal fun formatDuration(ms: Long): String {
    val minutes = (ms / 60_000).toInt()
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

internal fun formatTimestamp(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "2019 · 2h 14m · ★ 8.1" — the quiet facts line under a title. */
internal fun PlexItem.factsLine(): String = listOfNotNull(
    if (type == "episode") listOfNotNull(episodeLabel, originallyAvailableAt?.take(4)).joinToString(" · ") else year?.toString(),
    duration?.takeIf { type != "show" }?.let(::formatDuration),
    childCount?.takeIf { type == "show" }?.let { if (it == 1) "1 season" else "$it seasons" },
    audienceRating?.let { "★ %.1f".format(it) },
).filter { it.isNotBlank() }.joinToString("  ·  ")

/** Tech badges from the primary file: 4K, DOLBY VISION / HDR10, ATMOS / TRUEHD 7.1, CC. */
internal fun PlexItem.specBadges(): List<String> {
    val media = media.firstOrNull() ?: return listOfNotNull(contentRating)
    val streams = media.parts.firstOrNull()?.streams.orEmpty()
    val video = streams.firstOrNull { it.streamType == PlexStream.VIDEO }
    val audio = streams.filter { it.streamType == PlexStream.AUDIO }.let { list ->
        list.firstOrNull { it.selected == true } ?: list.firstOrNull { it.isDefault == true } ?: list.firstOrNull()
    }
    val hasSubs = streams.any { it.streamType == PlexStream.SUBTITLE }

    val resolution = when (media.videoResolution?.lowercase()) {
        "4k" -> "4K"
        "1080" -> "1080p"
        "720" -> "720p"
        null -> null
        else -> "SD"
    }
    val range = when {
        video?.doviPresent == true -> "Dolby Vision"
        video?.colorTrc == "smpte2084" -> "HDR10"
        video?.colorTrc == "arib-std-b67" -> "HLG"
        else -> null
    }
    val audioLabel = audio?.let { a ->
        val title = (a.extendedDisplayTitle ?: a.displayTitle).orEmpty()
        val codec = when (a.codec?.lowercase()) {
            "truehd" -> "TrueHD"
            "eac3" -> "DD+"
            "ac3" -> "Dolby Digital"
            "dca", "dts" -> if (title.contains("DTS-HD", ignoreCase = true)) "DTS-HD" else "DTS"
            "flac" -> "FLAC"
            "aac" -> "AAC"
            "opus" -> "Opus"
            else -> a.codec?.uppercase()
        }
        val channels = when (a.channels ?: media.audioChannels) {
            8 -> "7.1"
            6 -> "5.1"
            2 -> "Stereo"
            else -> null
        }
        if (title.contains("Atmos", ignoreCase = true)) "Atmos" else listOfNotNull(codec, channels).joinToString(" ")
    }
    return listOfNotNull(contentRating, resolution, range, audioLabel, "CC".takeIf { hasSubs })
}
