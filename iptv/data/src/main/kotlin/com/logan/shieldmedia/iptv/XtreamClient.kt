package com.logan.shieldmedia.iptv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

class IptvException(message: String) : Exception(message)

/**
 * Xtream Codes "player_api". Panels disagree on types (numbers arrive as strings, ints, or null),
 * so responses are read as loose JSON instead of strict data classes.
 */
internal class XtreamClient(private val http: IptvHttp, private val playlist: Playlist) {

    private val base = playlist.url.trimEnd('/')
    private val user = playlist.username.orEmpty()
    private val pass = playlist.password.orEmpty()

    /** Server timezone from the login response; catch-up start times are in this zone. */
    var timezone: String? = null
        private set

    private fun api(action: String? = null): HttpUrl =
        "$base/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .apply { if (action != null) addQueryParameter("action", action) }
            .build()

    /** Logs in and returns the stream extension to use ("ts" when the panel allows it). */
    suspend fun authenticate(): String {
        val info = parse(http.getString(api(), playlist.userAgent)).jsonObject
        val userInfo = info["user_info"] as? JsonObject ?: throw IptvException("This doesn't look like an Xtream Codes server.")
        if (userInfo.str("auth") != "1") throw IptvException("Wrong username or password.")
        val status = userInfo.str("status")
        if (status != null && !status.equals("Active", ignoreCase = true)) throw IptvException("Account status: $status")
        timezone = (info["server_info"] as? JsonObject)?.str("timezone")
        val formats = (userInfo["allowed_output_formats"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return if (formats.isEmpty() || "ts" in formats) "ts" else formats.first()
    }

    private suspend fun fetchList(action: String): JsonElement =
        try {
            parse(http.getString(api(action), playlist.userAgent))
        } catch (e: IptvException) {
            parse(http.getString(api(action), playlist.userAgent))
        }

    suspend fun liveChannels(extension: String): List<ParsedChannel> {
        val categories = fetchList("get_live_categories").asArray()
            .mapNotNull { it as? JsonObject }
            .associate { it.str("category_id").orEmpty() to (it.str("category_name") ?: "Uncategorized") }
        return fetchList("get_live_streams").asArray()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { s ->
                val id = s.str("stream_id") ?: return@mapNotNull null
                ParsedChannel(
                    key = id,
                    name = s.str("name")?.trim().orEmpty().ifEmpty { "Channel $id" },
                    url = "$base/live/$user/$pass/$id.$extension",
                    group = categories[s.str("category_id")] ?: "Uncategorized",
                    logo = s.str("stream_icon")?.ifBlank { null },
                    epgId = s.str("epg_channel_id")?.ifBlank { null },
                    number = s.str("num")?.toIntOrNull(),
                    catchupDays = if (s.str("tv_archive") == "1") s.str("tv_archive_duration")?.toIntOrNull() ?: 0 else 0,
                )
            }
    }

    fun epgUrl(): String = "$base/xmltv.php?username=$user&password=$pass"

    private fun parse(body: String): JsonElement {
        runCatching { return Json.parseToJsonElement(body) }
        // Some panels close the connection before the last bytes of a list arrive (seen with
        // XUI behind openresty: the closing "]" goes missing). Keep every complete entry.
        repairTruncated(body)?.let { repaired ->
            runCatching { return Json.parseToJsonElement(repaired) }
        }
        android.util.Log.w("XtreamClient", "Unparseable response (${body.length} chars): ${body.take(200)}")
        throw IptvException("The server sent an unexpected response.")
    }

    private fun repairTruncated(body: String): String? {
        val text = body.trim()
        if (!text.startsWith("[")) return null
        val lastComplete = text.lastIndexOf('}')
        if (lastComplete < 0) return "[]"
        return text.substring(0, lastComplete + 1) + "]"
    }

    private fun JsonElement.asArray(): JsonArray = (this as? JsonArray) ?: (this as? JsonObject)?.values?.let { JsonArray(it.toList()) } ?: jsonArray

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
