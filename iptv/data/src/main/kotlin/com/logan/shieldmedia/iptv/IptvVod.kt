package com.logan.shieldmedia.iptv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class VodKind { MOVIE, SERIES }

data class VodCategory(val id: String, val name: String)

data class VodItem(
    val kind: VodKind,
    val id: String,
    val name: String,
    val poster: String?,
    val rating: String?,
    val year: String?,
    val categoryId: String?,
    val extension: String,
)

data class VodDetails(
    val title: String,
    val plot: String?,
    val poster: String?,
    val backdrop: String?,
    val year: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val rating: String?,
    val durationSecs: Long?,
)

data class VodEpisode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String?,
    val image: String?,
    val durationSecs: Long?,
    val extension: String,
)

data class SeriesDetails(val details: VodDetails, val seasons: Map<Int, List<VodEpisode>>) {
    val allEpisodes: List<VodEpisode> get() = seasons.toSortedMap().values.flatten()
}

/** Saved position for resume and "Continue watching". */
data class VodProgress(val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
    val fraction: Float get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * 0.92
}

/**
 * Movies and series from an Xtream Codes account. Lists are fetched on demand and kept in memory
 * (providers can have tens of thousands of titles; there's no need to store them).
 * Progress is stored locally per account.
 */
class IptvVodRepository(context: Context, private val iptv: IptvRepository) {
    private val progressPrefs = context.applicationContext.getSharedPreferences("iptv_vod_progress", Context.MODE_PRIVATE)
    private val listCache = HashMap<String, List<VodItem>>()

    /** The title whose page is open; kept here so it survives a trip to the player and back. */
    var selected: VodItem? = null

    private fun activeXtream(): Playlist? =
        iptv.playlists.value.firstOrNull { it.id == iptv.activePlaylistId.value && it.type == PlaylistType.XTREAM }

    /** False for M3U sources, which carry no movie/series catalogue. */
    fun isSupported(): Boolean = activeXtream() != null

    private fun api(p: Playlist, action: String, vararg extra: Pair<String, String>) =
        "${p.url.trimEnd('/')}/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", p.username.orEmpty())
            .addQueryParameter("password", p.password.orEmpty())
            .addQueryParameter("action", action)
            .apply { extra.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()

    private suspend fun fetch(p: Playlist, action: String, vararg extra: Pair<String, String>): JsonElement {
        val body = iptv.http.getString(api(p, action, *extra), p.userAgent)
        return parseLenient(body)
    }

    suspend fun categories(kind: VodKind): List<VodCategory> = withContext(Dispatchers.IO) {
        val p = activeXtream() ?: return@withContext emptyList()
        val action = if (kind == VodKind.MOVIE) "get_vod_categories" else "get_series_categories"
        fetch(p, action).asList().mapNotNull { c ->
            val id = c.str("category_id") ?: return@mapNotNull null
            VodCategory(id, c.str("category_name") ?: "Other")
        }
    }

    suspend fun items(kind: VodKind, categoryId: String?): List<VodItem> = withContext(Dispatchers.IO) {
        val p = activeXtream() ?: return@withContext emptyList()
        val key = "${p.id}/$kind/${categoryId ?: "all"}"
        listCache[key]?.let { return@withContext it }
        val action = if (kind == VodKind.MOVIE) "get_vod_streams" else "get_series"
        val extra = categoryId?.let { arrayOf("category_id" to it) } ?: emptyArray()
        val list = fetch(p, action, *extra).asList().mapNotNull { o ->
            if (kind == VodKind.MOVIE) {
                val id = o.str("stream_id") ?: return@mapNotNull null
                VodItem(kind, id, o.str("name").orEmpty().trim(), o.str("stream_icon")?.ifBlank { null }, o.str("rating")?.takeIf { it != "0" },
                    o.str("year") ?: yearOf(o.str("releasedate")), o.str("category_id"), o.str("container_extension") ?: "mp4")
            } else {
                val id = o.str("series_id") ?: return@mapNotNull null
                VodItem(kind, id, o.str("name").orEmpty().trim(), o.str("cover")?.ifBlank { null }, o.str("rating")?.takeIf { it != "0" },
                    yearOf(o.str("releaseDate") ?: o.str("release_date")), o.str("category_id"), "mp4")
            }
        }
        listCache[key] = list
        list
    }

    suspend fun movieDetails(item: VodItem): VodDetails = withContext(Dispatchers.IO) {
        val p = activeXtream() ?: error("No Xtream source")
        val info = (fetch(p, "get_vod_info", "vod_id" to item.id) as? JsonObject)?.get("info") as? JsonObject
        details(info, item)
    }

    suspend fun seriesDetails(item: VodItem): SeriesDetails = withContext(Dispatchers.IO) {
        val p = activeXtream() ?: error("No Xtream source")
        val root = fetch(p, "get_series_info", "series_id" to item.id) as? JsonObject
        val info = root?.get("info") as? JsonObject
        // Panels send episodes either as {"1": [...], "2": [...]} or as a flat array.
        val raw = root?.get("episodes")
        val eps = when (raw) {
            is JsonObject -> raw.values.flatMap { (it as? JsonArray).orEmpty() }
            is JsonArray -> raw.toList()
            else -> emptyList()
        }.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val epInfo = o["info"] as? JsonObject
            VodEpisode(
                id = o.str("id") ?: return@mapNotNull null,
                season = o.str("season")?.toIntOrNull() ?: 1,
                number = o.str("episode_num")?.toIntOrNull() ?: 0,
                title = o.str("title").orEmpty(),
                plot = epInfo?.str("plot")?.ifBlank { null },
                image = epInfo?.str("movie_image")?.ifBlank { null },
                durationSecs = epInfo?.str("duration_secs")?.toLongOrNull(),
                extension = o.str("container_extension") ?: "mp4",
            )
        }
        SeriesDetails(details(info, item), eps.groupBy { it.season }.mapValues { (_, v) -> v.sortedBy { it.number } })
    }

    fun movieUrl(item: VodItem): String? = activeXtream()?.let {
        "${it.url.trimEnd('/')}/movie/${it.username}/${it.password}/${item.id}.${item.extension}"
    }

    fun episodeUrl(ep: VodEpisode): String? = activeXtream()?.let {
        "${it.url.trimEnd('/')}/series/${it.username}/${it.password}/${ep.id}.${ep.extension}"
    }

    fun userAgent(): String? = activeXtream()?.userAgent

    // ---- progress ----

    private fun key(kind: String, id: String) = "${iptv.activePlaylistId.value}/$kind/$id"

    fun progress(kind: String, id: String): VodProgress? =
        progressPrefs.getString(key(kind, id), null)?.split(',')?.takeIf { it.size == 3 }?.let {
            VodProgress(it[0].toLong(), it[1].toLong(), it[2].toLong())
        }

    fun saveProgress(kind: String, id: String, positionMs: Long, durationMs: Long) {
        progressPrefs.edit().putString(key(kind, id), "$positionMs,$durationMs,${System.currentTimeMillis()}").apply()
    }

    private fun details(info: JsonObject?, item: VodItem) = VodDetails(
        title = info?.str("name")?.ifBlank { null } ?: item.name,
        plot = info?.str("plot")?.ifBlank { null } ?: info?.str("description")?.ifBlank { null },
        poster = info?.str("movie_image")?.ifBlank { null } ?: info?.str("cover")?.ifBlank { null } ?: item.poster,
        backdrop = (info?.get("backdrop_path") as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }?.ifBlank { null }
            ?: info?.str("backdrop_path")?.ifBlank { null },
        year = yearOf(info?.str("releasedate") ?: info?.str("releaseDate")) ?: item.year,
        genre = info?.str("genre")?.ifBlank { null },
        cast = info?.str("cast")?.ifBlank { null },
        director = info?.str("director")?.ifBlank { null },
        rating = info?.str("rating")?.takeIf { it.isNotBlank() && it != "0" } ?: item.rating,
        durationSecs = info?.str("duration_secs")?.toLongOrNull(),
    )
}

private fun yearOf(date: String?): String? = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

private fun JsonElement.asList(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> values.mapNotNull { it as? JsonObject }
    else -> emptyList()
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Same tolerance as the live lists: keep every complete entry of a truncated array. */
internal fun parseLenient(body: String): JsonElement {
    runCatching { return Json.parseToJsonElement(body) }
    val t = body.trim()
    if (t.startsWith("[")) {
        val last = t.lastIndexOf('}')
        runCatching { return Json.parseToJsonElement(if (last < 0) "[]" else t.substring(0, last + 1) + "]") }
    }
    throw IptvException("The server sent an unexpected response.")
}
