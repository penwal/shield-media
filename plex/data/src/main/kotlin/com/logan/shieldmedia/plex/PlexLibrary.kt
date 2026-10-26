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

    /** Every row Plex can offer for one library (recently added, recently released, seasonal...). */
    suspend fun sectionHubs(server: ActiveServer, sectionKey: String): List<PlexHub> =
        server.container("/hubs/sections/$sectionKey", "count" to 20).hubs

    suspend fun sections(server: ActiveServer): List<LibrarySection> =
        server.container("/library/sections").directories

    /**
     * One page of a library. [sort] is a Plex sort key such as "addedAt:desc"; [genreKey] comes
     * from [genres]; [unwatched] hides anything already watched.
     */
    suspend fun sectionItems(
        server: ActiveServer,
        sectionKey: String,
        start: Int,
        size: Int,
        sort: String = "titleSort",
        genreKey: String? = null,
        unwatched: Boolean = false,
    ): MediaContainer {
        val query = mutableListOf<Pair<String, Any>>(
            "sort" to sort,
            "X-Plex-Container-Start" to start,
            "X-Plex-Container-Size" to size,
        )
        genreKey?.let { query += "genre" to it }
        if (unwatched) query += "unwatched" to 1
        return server.container("/library/sections/$sectionKey/all", *query.toTypedArray())
    }

    /** Trailers, featurettes, etc. attached to a title. */
    suspend fun extras(server: ActiveServer, ratingKey: String): List<PlexItem> =
        server.container("/library/metadata/$ratingKey/extras").metadata

    /** Genres present in a library, for filtering. */
    suspend fun genres(server: ActiveServer, sectionKey: String): List<LibrarySection> =
        server.container("/library/sections/$sectionKey/genre").directories

    suspend fun item(server: ActiveServer, ratingKey: String): PlexItem? =
        server.container("/library/metadata/$ratingKey", "includeImages" to 1, "includeMarkers" to 1)
            .metadata.firstOrNull()

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

    // ---- playback state ----

    /**
     * Tells the server where playback is. This drives Continue Watching everywhere, the "Now
     * Playing" view in Plex, and marks the item watched once a stopped report passes ~90%.
     */
    suspend fun timeline(server: ActiveServer, item: PlexItem, state: String, timeMs: Long, durationMs: Long) {
        http.send(
            server.url(
                "/:/timeline",
                "ratingKey" to item.ratingKey,
                "key" to "/library/metadata/${item.ratingKey}",
                "state" to state,
                "time" to timeMs,
                "duration" to durationMs,
                "identifier" to "com.plexapp.plugins.library",
            ),
            session.clientInfo,
            server.token,
        )
    }

    suspend fun markWatched(server: ActiveServer, ratingKey: String) {
        http.send(server.url("/:/scrobble", "key" to ratingKey, "identifier" to "com.plexapp.plugins.library"), session.clientInfo, server.token)
    }

    /**
     * Drops an in-progress title from Continue Watching and forgets where it was stopped, so it
     * just says Play again. Plex's own remove action keeps the position, so the position is
     * cleared too, without changing whether it counts as watched: a rewatch stays watched.
     */
    suspend fun removeFromContinueWatching(server: ActiveServer, item: PlexItem) {
        runCatching {
            http.send(server.url("/actions/removeFromContinueWatching", "ratingKey" to item.ratingKey), session.clientInfo, server.token, method = "PUT")
        }
        if ((item.viewCount ?: 0) > 0) markWatched(server, item.ratingKey) else markUnwatched(server, item.ratingKey)
    }

    suspend fun markUnwatched(server: ActiveServer, ratingKey: String) {
        http.send(server.url("/:/unscrobble", "key" to ratingKey, "identifier" to "com.plexapp.plugins.library"), session.clientInfo, server.token)
    }

    /** The episode after [episode]: next in its season, else the first of the following season. */
    suspend fun nextEpisode(server: ActiveServer, episode: PlexItem): PlexItem? {
        val seasonKey = episode.parentRatingKey ?: return null
        val inSeason = children(server, seasonKey).sortedBy { it.index ?: 0 }
        inSeason.firstOrNull { (it.index ?: 0) > (episode.index ?: 0) }?.let { return it }
        val showKey = episode.grandparentRatingKey ?: return null
        val seasons = children(server, showKey).sortedBy { it.index ?: 0 }
        val nextSeason = seasons.firstOrNull { (it.index ?: 0) > (episode.parentIndex ?: 0) } ?: return null
        return children(server, nextSeason.ratingKey).minByOrNull { it.index ?: 0 }
    }

    /**
     * Last resort when the original file can't play here: ask Plex to convert it to HLS.
     * Direct stream (remux, original video/audio when possible) is tried before a full transcode.
     */
    fun transcodeUrl(server: ActiveServer, item: PlexItem, offsetMs: Long, sessionId: String): String {
        val query = mutableListOf<Pair<String, Any>>(
            "path" to "/library/metadata/${item.ratingKey}",
            "mediaIndex" to 0,
            "partIndex" to 0,
            "protocol" to "hls",
            "offset" to offsetMs / 1000,
            "fastSeek" to 1,
            "directPlay" to 0,
            "directStream" to 1,
            "directStreamAudio" to 1,
            "videoQuality" to 100,
            "maxVideoBitrate" to 40_000,
            "videoResolution" to "3840x2160",
            "subtitles" to "auto",
            "session" to sessionId,
            "X-Plex-Session-Identifier" to sessionId,
            "X-Plex-Token" to server.token,
        )
        http.identityQuery(session.clientInfo).forEach { (k, v) -> query += k to v }
        return server.url("/video/:/transcode/universal/start.m3u8", *query.toTypedArray()).toString()
    }

    /** Direct-play URL for a media part: the original file, untouched. */
    fun directPlayUrl(server: ActiveServer, part: PlexPart): String =
        server.url(part.key, "X-Plex-Token" to server.token).toString()
}
