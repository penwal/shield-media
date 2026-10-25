package com.logan.shieldmedia.plex

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PlexException(val code: Int, message: String) : Exception(message) {
    val isUnauthorized: Boolean get() = code == 401
}

/** Identity Plex wants on every request; it is how the device shows up under "Authorized Devices". */
data class PlexClientInfo(
    val clientId: String,
    val product: String = "Shield Media",
    val version: String = "0.1.0",
)

internal val plexJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

internal class PlexHttp(private val client: OkHttpClient) {

    private fun headers(info: PlexClientInfo, token: String?): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("X-Plex-Client-Identifier", info.clientId)
        put("X-Plex-Product", info.product)
        put("X-Plex-Version", info.version)
        put("X-Plex-Platform", "Android")
        put("X-Plex-Platform-Version", Build.VERSION.RELEASE)
        put("X-Plex-Device", Build.MODEL)
        put("X-Plex-Device-Name", Build.MODEL)
        put("X-Plex-Model", Build.MODEL)
        if (token != null) put("X-Plex-Token", token)
    }

    suspend fun <T> get(
        url: HttpUrl,
        info: PlexClientInfo,
        token: String?,
        deserializer: DeserializationStrategy<T>,
        client: OkHttpClient = this.client,
    ): T = execute(url, "GET", info, token, client).let { plexJson.decodeFromString(deserializer, it) }

    suspend fun <T> post(
        url: HttpUrl,
        info: PlexClientInfo,
        token: String?,
        deserializer: DeserializationStrategy<T>,
    ): T = execute(url, "POST", info, token, client).let { plexJson.decodeFromString(deserializer, it) }

    /** Returns true if the server answers at all; used to pick a working connection. */
    suspend fun ping(url: HttpUrl, info: PlexClientInfo, token: String?, client: OkHttpClient): Boolean =
        runCatching { execute(url, "GET", info, token, client) }.isSuccess

    private suspend fun execute(
        url: HttpUrl,
        method: String,
        info: PlexClientInfo,
        token: String?,
        client: OkHttpClient,
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .apply { headers(info, token).forEach { (k, v) -> header(k, v) } }
            .method(method, if (method == "POST") ByteArray(0).toRequestBody() else null)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw PlexException(response.code, "HTTP ${response.code} for ${url.encodedPath}")
            body
        }
    }
}
