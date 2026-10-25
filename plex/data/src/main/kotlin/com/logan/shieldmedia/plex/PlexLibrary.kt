package com.logan.shieldmedia.plex

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Read access to a Plex Media Server's library. */
class PlexLibrary(private val session: PlexSession) {

    private val http get() = session.http

    private fun ActiveServer.url(path: String, vararg query: Pair<String, Any>): HttpUrl =
        "$baseUrl$path".toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v.toString()) }
        }.build()

    private suspend fun ActiveServer.container(path: String, vararg query: Pair<String, Any>): MediaContainer =
        http.get(url(path, *query), session.clientInfo, token, MediaContainerResponse.serializer()).container

    /** Home screen rows: Continue Watching, Recently Added per library, etc. */
    suspend fun homeHubs(server: ActiveServer): List<PlexHub> =
        server.container("/hubs", "count" to 20).hubs.filter { it.items.isNotEmpty() }

    suspend fun continueWatching(server: ActiveServer): PlexHub? =
        runCatching { server.container("/hubs/continueWatching", "count" to 20).hubs.firstOrNull() }.getOrNull()

    suspend fun sections(server: ActiveServer): List<LibrarySection> =
        server.container("/library/sections").directories

    suspend fun sectionItems(server: ActiveServer, sectionKey: String, start: Int, size: Int): MediaContainer =
        server.container(
            "/library/sections/$sectionKey/all",
            "sort" to "titleSort",
            "X-Plex-Container-Start" to start,
            "X-Plex-Container-Size" to size,
        )

    suspend fun item(server: ActiveServer, ratingKey: String): PlexItem? =
        server.container("/library/metadata/$ratingKey", "includeImages" to 1).metadata.firstOrNull()

    suspend fun children(server: ActiveServer, ratingKey: String): List<PlexItem> =
        server.container("/library/metadata/$ratingKey/children", "includeImages" to 1).metadata

    suspend fun search(server: ActiveServer, query: String): List<PlexHub> =
        server.container("/hubs/search", "query" to query, "limit" to 20).hubs.filter { it.items.isNotEmpty() }

    /** Server-side resized artwork, so a 4K poster never gets decoded just to fill a 136dp card. */
    fun imageUrl(server: ActiveServer, path: String?, width: Int, height: Int): String? {
        if (path.isNullOrBlank()) return null
        return server.url(
            "/photo/:/transcode",
            "width" to width,
            "height" to height,
            "minSize" to 1,
            "upscale" to 1,
            "url" to path,
            "X-Plex-Token" to server.token,
        ).toString()
    }

    /** Direct-play URL for a media part: the original file, untouched. */
    fun directPlayUrl(server: ActiveServer, part: PlexPart): String =
        server.url(part.key, "X-Plex-Token" to server.token).toString()
}
