package com.logan.shieldmedia.plex

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

private val Context.plexStore: DataStore<Preferences> by preferencesDataStore(name = "plex")

sealed interface PlexState {
    data object Loading : PlexState
    data object SignedOut : PlexState
    data class ChooseServer(val servers: List<PlexResource>) : PlexState
    data class Ready(val server: ActiveServer) : PlexState
    data class Error(val message: String) : PlexState
}

/**
 * Owns the Plex sign-in and which server we talk to. Everything else asks this for the
 * [ActiveServer]; nothing else stores tokens.
 */
class PlexSession(context: Context, okHttp: OkHttpClient) {

    private val store = context.applicationContext.plexStore
    internal val http = PlexHttp(okHttp)
    private val probeClient = okHttp.newBuilder()
        .callTimeout(4, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<PlexState>(PlexState.Loading)
    val state: StateFlow<PlexState> = _state.asStateFlow()

    lateinit var clientInfo: PlexClientInfo
        private set

    private object Keys {
        val ClientId = stringPreferencesKey("client_id")
        val AuthToken = stringPreferencesKey("auth_token")
        /** The token from signing in; profile switches are requested with it. */
        val AccountToken = stringPreferencesKey("account_token")
        val ProfileName = stringPreferencesKey("profile_name")
        val ServerId = stringPreferencesKey("server_id")
        val ServerName = stringPreferencesKey("server_name")
        val ServerUri = stringPreferencesKey("server_uri")
        val ServerToken = stringPreferencesKey("server_token")
        val ServerOwned = booleanPreferencesKey("server_owned")
    }

    suspend fun start() {
        val prefs = store.data.first()
        val clientId = prefs[Keys.ClientId] ?: UUID.randomUUID().toString().also { id ->
            store.edit { it[Keys.ClientId] = id }
        }
        clientInfo = PlexClientInfo(clientId)

        if (prefs[Keys.AuthToken] == null) {
            _state.value = PlexState.SignedOut
            return
        }
        val saved = prefs.savedServer()
        if (saved != null && http.ping("${saved.baseUrl}/identity".toHttpUrl(), clientInfo, saved.token, probeClient)) {
            _state.value = PlexState.Ready(saved)
            return
        }
        // Saved address stopped working (new IP, away from home...): rediscover, preferring the same server.
        discoverServers(preferredId = saved?.id)
    }

    // ---- Sign in with a plex.tv/link code ----

    suspend fun createPin(): PlexPin =
        http.post("https://plex.tv/api/v2/pins?strong=false".toHttpUrl(), clientInfo, null, PlexPin.serializer())

    /** Returns true once the user has entered the code at plex.tv/link. */
    suspend fun checkPin(pin: PlexPin): Boolean {
        val result = http.get("https://plex.tv/api/v2/pins/${pin.id}".toHttpUrl(), clientInfo, null, PlexPin.serializer())
        val token = result.authToken ?: return false
        store.edit {
            it[Keys.AuthToken] = token
            it[Keys.AccountToken] = token
        }
        discoverServers(preferredId = null)
        return true
    }

    // ---- Plex Home profiles ----

    suspend fun homeUsers(): List<PlexHomeUser> {
        val prefs = store.data.first()
        val token = prefs[Keys.AccountToken] ?: prefs[Keys.AuthToken] ?: return emptyList()
        return runCatching {
            http.get("https://clients.plex.tv/api/v2/home/users".toHttpUrl(), clientInfo, token, PlexHomeUsers.serializer()).users
        }.getOrDefault(emptyList())
    }

    suspend fun currentProfileName(): String? = store.data.first()[Keys.ProfileName]

    /**
     * Switches to another Home profile. Returns false for a wrong PIN. The server list is
     * rediscovered because each profile gets its own access token for shared servers.
     */
    suspend fun switchProfile(user: PlexHomeUser, pin: String?): Boolean {
        val prefs = store.data.first()
        val accountToken = prefs[Keys.AccountToken] ?: prefs[Keys.AuthToken] ?: return false
        val url = "https://clients.plex.tv/api/v2/home/users/${user.uuid}/switch".toHttpUrl().newBuilder()
            .apply { if (!pin.isNullOrEmpty()) addQueryParameter("pin", pin) }
            .build()
        val result = try {
            http.post(url, clientInfo, accountToken, PlexSwitchResult.serializer())
        } catch (e: PlexException) {
            if (e.code == 401 || e.code == 403 || e.code == 400) return false
            throw e
        }
        val token = result.authToken ?: return false
        store.edit {
            if (it[Keys.AccountToken] == null) it[Keys.AccountToken] = accountToken
            it[Keys.AuthToken] = token
            it[Keys.ProfileName] = user.title
        }
        discoverServers(preferredId = prefs[Keys.ServerId])
        return true
    }

    suspend fun user(): PlexUser? {
        val token = store.data.first()[Keys.AuthToken] ?: return null
        return runCatching {
            http.get("https://plex.tv/api/v2/user".toHttpUrl(), clientInfo, token, PlexUser.serializer())
        }.getOrNull()
    }

    // ---- Servers ----

    suspend fun discoverServers(preferredId: String? = null) {
        val token = store.data.first()[Keys.AuthToken] ?: run {
            _state.value = PlexState.SignedOut
            return
        }
        _state.value = PlexState.Loading
        val servers = try {
            http.get(
                "https://clients.plex.tv/api/v2/resources?includeHttps=1&includeRelay=1".toHttpUrl(),
                clientInfo,
                token,
                ListSerializer(PlexResource.serializer()),
            ).filter { it.isServer }
        } catch (e: PlexException) {
            if (e.isUnauthorized) signOut() else _state.value = PlexState.Error("Couldn't reach plex.tv (${e.message})")
            return
        } catch (e: Exception) {
            _state.value = PlexState.Error("Couldn't reach plex.tv. Check the Shield's internet connection.")
            return
        }

        val preferred = servers.firstOrNull { it.clientIdentifier == preferredId }
        when {
            preferred != null -> connect(preferred)
            servers.size == 1 -> connect(servers.single())
            servers.isEmpty() -> _state.value = PlexState.Error("No Plex Media Servers found on this account.")
            else -> _state.value = PlexState.ChooseServer(servers.sortedByDescending { it.owned })
        }
    }

    suspend fun connect(resource: PlexResource) {
        _state.value = PlexState.Loading
        val token = resource.accessToken ?: store.data.first()[Keys.AuthToken].orEmpty()
        val uri = bestConnection(resource, token)
        if (uri == null) {
            _state.value = PlexState.Error("Can't reach \"${resource.name}\". Is the server running and on the same network?")
            return
        }
        val server = ActiveServer(resource.clientIdentifier, resource.name, uri, token, resource.owned)
        store.edit {
            it[Keys.ServerId] = server.id
            it[Keys.ServerName] = server.name
            it[Keys.ServerUri] = server.baseUrl
            it[Keys.ServerToken] = server.token
            it[Keys.ServerOwned] = server.owned
        }
        _state.value = PlexState.Ready(server)
    }

    /**
     * Tries every advertised address at once and takes the best one that answers:
     * local first (fastest, no bandwidth limits), then remote, and the Plex relay last.
     */
    private suspend fun bestConnection(resource: PlexResource, token: String): String? = coroutineScope {
        val ordered = resource.connections.sortedBy {
            when {
                it.local && !it.relay -> 0
                !it.relay -> 1
                else -> 2
            }
        }
        val probes = ordered.map { c ->
            c.uri to async { http.ping("${c.uri}/identity".toHttpUrl(), clientInfo, token, probeClient) }
        }
        val winner = probes.firstOrNull { (_, ok) -> ok.await() }?.first
        probes.forEach { (_, job) -> job.cancel() }
        winner
    }

    suspend fun signOut() {
        store.edit { prefs ->
            listOf(
                Keys.AuthToken, Keys.AccountToken, Keys.ProfileName,
                Keys.ServerId, Keys.ServerName, Keys.ServerUri, Keys.ServerToken,
            ).forEach { prefs.remove(it) }
            prefs.remove(Keys.ServerOwned)
        }
        _state.value = PlexState.SignedOut
    }

    private fun Preferences.savedServer(): ActiveServer? {
        val id = this[Keys.ServerId] ?: return null
        return ActiveServer(
            id = id,
            name = this[Keys.ServerName].orEmpty(),
            baseUrl = this[Keys.ServerUri] ?: return null,
            token = this[Keys.ServerToken] ?: return null,
            owned = this[Keys.ServerOwned] ?: false,
        )
    }
}
