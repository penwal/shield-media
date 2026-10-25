package com.logan.shieldmedia.iptv

enum class PlaylistType { XTREAM, M3U }

data class Playlist(
    val id: Long = 0,
    val name: String,
    val type: PlaylistType,
    /** M3U: the playlist URL. Xtream: the server base, e.g. http://host:8080 */
    val url: String,
    val username: String? = null,
    val password: String? = null,
    /** Optional override; otherwise the playlist's own guide (url-tvg / xmltv.php) is used. */
    val epgUrl: String? = null,
    val userAgent: String? = null,
    val lastSync: Long = 0,
    val lastEpgSync: Long = 0,
    val channelCount: Int = 0,
)

data class Channel(
    val id: Long,
    val playlistId: Long,
    /** Stable identity across re-syncs (Xtream stream id or M3U url), used for favorites. */
    val key: String,
    val number: Int,
    val name: String,
    val logo: String?,
    val group: String,
    val streamUrl: String,
    val epgId: String?,
    val catchupDays: Int,
    val favorite: Boolean,
)

data class Program(
    val epgId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
) {
    fun isAiring(now: Long) = now in start until stop
    fun progress(now: Long): Float = ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

/** Which channels a screen shows. */
sealed interface ChannelFilter {
    data object All : ChannelFilter
    data object Favorites : ChannelFilter
    data class Group(val name: String) : ChannelFilter

    val label: String
        get() = when (this) {
            All -> "All channels"
            Favorites -> "Favorites"
            is Group -> name
        }

    /** Round-trips through a navigation argument. */
    val key: String
        get() = when (this) {
            All -> "all"
            Favorites -> "fav"
            is Group -> "g:$name"
        }

    companion object {
        fun fromKey(key: String?): ChannelFilter = when {
            key == "fav" -> Favorites
            key != null && key.startsWith("g:") -> Group(key.removePrefix("g:"))
            else -> All
        }
    }
}

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val playlist: String, val step: String) : SyncState
    data class Failed(val playlist: String, val message: String) : SyncState
}
