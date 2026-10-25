package com.logan.shieldmedia.plex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- plex.tv (account) ----

@Serializable
data class PlexPin(
    val id: Long,
    val code: String,
    val authToken: String? = null,
    val expiresIn: Int? = null,
)

@Serializable
data class PlexUser(
    val id: Long = 0,
    val uuid: String? = null,
    val username: String? = null,
    val title: String? = null,
    val thumb: String? = null,
)

@Serializable
data class PlexResource(
    val name: String,
    val product: String? = null,
    val provides: String = "",
    val clientIdentifier: String,
    val accessToken: String? = null,
    val owned: Boolean = false,
    val presence: Boolean = false,
    val connections: List<PlexConnection> = emptyList(),
) {
    val isServer: Boolean get() = provides.split(',').contains("server")
}

@Serializable
data class PlexConnection(
    val protocol: String = "",
    val address: String = "",
    val port: Int = 0,
    val uri: String,
    val local: Boolean = false,
    val relay: Boolean = false,
    @SerialName("IPv6") val ipv6: Boolean = false,
)

// ---- Plex Media Server ----

@Serializable
data class MediaContainerResponse(
    @SerialName("MediaContainer") val container: MediaContainer,
)

@Serializable
data class MediaContainer(
    val size: Int = 0,
    val totalSize: Int? = null,
    val offset: Int? = null,
    @SerialName("Directory") val directories: List<LibrarySection> = emptyList(),
    @SerialName("Metadata") val metadata: List<PlexItem> = emptyList(),
    @SerialName("Hub") val hubs: List<PlexHub> = emptyList(),
)

@Serializable
data class LibrarySection(
    val key: String,
    val title: String,
    val type: String = "",
)

@Serializable
data class PlexHub(
    val hubIdentifier: String = "",
    val title: String = "",
    val type: String = "",
    val size: Int = 0,
    val more: Boolean = false,
    val key: String? = null,
    @SerialName("Metadata") val items: List<PlexItem> = emptyList(),
)

/** Movies, shows, seasons, episodes, people in search results: Plex returns one shape for all. */
@Serializable
data class PlexItem(
    val ratingKey: String = "",
    val key: String = "",
    val type: String = "",
    val title: String = "",
    val originalTitle: String? = null,
    val summary: String? = null,
    val tagline: String? = null,
    val year: Int? = null,
    val thumb: String? = null,
    val art: String? = null,
    val parentThumb: String? = null,
    val grandparentThumb: String? = null,
    val grandparentArt: String? = null,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val parentRatingKey: String? = null,
    val grandparentRatingKey: String? = null,
    val index: Int? = null,
    val parentIndex: Int? = null,
    val duration: Long? = null,
    val viewOffset: Long? = null,
    val viewCount: Int? = null,
    val leafCount: Int? = null,
    val viewedLeafCount: Int? = null,
    val childCount: Int? = null,
    val contentRating: String? = null,
    val audienceRating: Double? = null,
    val rating: Double? = null,
    val studio: String? = null,
    val originallyAvailableAt: String? = null,
    @SerialName("Genre") val genres: List<PlexTag> = emptyList(),
    @SerialName("Director") val directors: List<PlexTag> = emptyList(),
    @SerialName("Role") val roles: List<PlexRole> = emptyList(),
    @SerialName("Media") val media: List<PlexMedia> = emptyList(),
    @SerialName("Image") val images: List<PlexImage> = emptyList(),
    @SerialName("Marker") val markers: List<PlexMarker> = emptyList(),
) {
    val isWatched: Boolean
        get() = if (leafCount != null) (viewedLeafCount ?: 0) >= leafCount else (viewCount ?: 0) > 0

    val unwatchedCount: Int?
        get() = leafCount?.let { it - (viewedLeafCount ?: 0) }?.takeIf { it > 0 }

    val progress: Float?
        get() {
            val offset = viewOffset ?: return null
            val total = duration ?: return null
            return if (total > 0) offset.toFloat() / total else null
        }

    val clearLogo: String?
        get() = images.firstOrNull { it.type == "clearLogo" }?.url

    /** Episode label like "S2 · E5" (just "E5" when Plex leaves out the season number). */
    val episodeLabel: String?
        get() {
            if (type != "episode" || index == null) return null
            return listOfNotNull(parentIndex?.let { "S$it" }, "E$index").joinToString(" · ")
        }
}

@Serializable
data class PlexTag(val tag: String)

@Serializable
data class PlexRole(val tag: String, val role: String? = null, val thumb: String? = null)

/** Intro/credits ranges Plex detects (requires the server's marker analysis). */
@Serializable
data class PlexMarker(
    val type: String = "",
    val startTimeOffset: Long = 0,
    val endTimeOffset: Long = 0,
    val final: Boolean? = null,
)

@Serializable
data class PlexImage(val type: String = "", val url: String = "")

@Serializable
data class PlexMedia(
    val id: Long = 0,
    val duration: Long? = null,
    val bitrate: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val audioChannels: Int? = null,
    val audioCodec: String? = null,
    val videoCodec: String? = null,
    val videoResolution: String? = null,
    val container: String? = null,
    val videoFrameRate: String? = null,
    @SerialName("Part") val parts: List<PlexPart> = emptyList(),
)

@Serializable
data class PlexPart(
    val id: Long = 0,
    val key: String = "",
    val duration: Long? = null,
    val file: String? = null,
    val size: Long? = null,
    val container: String? = null,
    @SerialName("Stream") val streams: List<PlexStream> = emptyList(),
)

@Serializable
data class PlexStream(
    val id: Long = 0,
    val streamType: Int = 0,
    val codec: String? = null,
    val displayTitle: String? = null,
    val extendedDisplayTitle: String? = null,
    val language: String? = null,
    val title: String? = null,
    val channels: Int? = null,
    val bitDepth: Int? = null,
    val colorTrc: String? = null,
    @SerialName("DOVIPresent") val doviPresent: Boolean? = null,
    @SerialName("DOVIProfile") val doviProfile: Int? = null,
    val selected: Boolean? = null,
    @SerialName("default") val isDefault: Boolean? = null,
) {
    companion object {
        const val VIDEO = 1
        const val AUDIO = 2
        const val SUBTITLE = 3
    }
}

data class ActiveServer(
    val id: String,
    val name: String,
    val baseUrl: String,
    val token: String,
    val owned: Boolean,
)
