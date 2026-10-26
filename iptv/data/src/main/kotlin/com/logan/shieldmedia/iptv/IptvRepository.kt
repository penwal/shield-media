package com.logan.shieldmedia.iptv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit

private const val CHANNEL_REFRESH_MS = 12 * 60 * 60_000L
private const val EPG_REFRESH_MS = 8 * 60 * 60_000L
private const val EPG_DAYS_BACK = 1L
private const val EPG_DAYS_AHEAD = 7L
private const val DAY_MS = 24 * 60 * 60_000L
const val DEFAULT_USER_AGENT = "ShieldMedia/0.1 (Android TV)"
private val TWENTY_FOUR_SEVEN = Regex("""\b24\s*[/x-]\s*7\b""", RegexOption.IGNORE_CASE)
private val XTREAM_LIVE_URL = Regex("""^(.*)/live/([^/]+)/([^/]+)/(\d+)\.\w+$""")

internal class IptvHttp(client: OkHttpClient) {
    // Panels and big guide files can be slow; give them room.
    private val client = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    /** Panels drop connections often; one quick retry hides most of it. */
    fun open(url: String, userAgent: String?): InputStream = try {
        openOnce(url, userAgent)
    } catch (e: java.io.IOException) {
        Thread.sleep(500)
        openOnce(url, userAgent)
    }

    private fun openOnce(url: String, userAgent: String?): InputStream {
        val request = Request.Builder().url(url.toHttpUrl()).header("User-Agent", userAgent ?: DEFAULT_USER_AGENT).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IptvException("HTTP ${response.code} from ${url.toHttpUrl().host}")
        }
        return response.body.byteStream()
    }

    suspend fun getString(url: okhttp3.HttpUrl, userAgent: String?): String = withContext(Dispatchers.IO) {
        open(url.toString(), userAgent).bufferedReader().use { it.readText() }
    }
}

/**
 * Owns every IPTV source: adding/removing playlists, syncing channels and the guide, and the
 * queries the Live TV screens run. One sync at a time; screens observe [dataVersion] to reload.
 */
class IptvRepository(context: Context, okHttp: OkHttpClient) {
    private val store = IptvStore(context)
    internal val http = IptvHttp(okHttp)
    private val prefs = context.applicationContext.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    private val syncLock = Mutex()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _sync.asStateFlow()

    /** Bumped whenever channels, favorites or the guide change. */
    private val _dataVersion = MutableStateFlow(0)
    val dataVersion: StateFlow<Int> = _dataVersion.asStateFlow()

    private val _activeId = MutableStateFlow(prefs.getLong("active", 0L))
    val activePlaylistId: StateFlow<Long> = _activeId.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _playlists.value = store.playlists()
        if (_playlists.value.none { it.id == _activeId.value }) setActive(_playlists.value.firstOrNull()?.id ?: 0)
    }

    fun setActive(id: Long) {
        _activeId.value = id
        prefs.edit().putLong("active", id).apply()
        _dataVersion.value++
    }

    /** Validates (Xtream login / M3U download), saves, then syncs channels and guide. */
    suspend fun addPlaylist(playlist: Playlist): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            if (playlist.type == PlaylistType.XTREAM) XtreamClient(http, playlist).authenticate()
            val id = store.insertPlaylist(playlist)
            load()
            setActive(id)
            // Only keep the source if its channels loaded; a guide problem alone is not fatal.
            sync(id, force = true).onFailure {
                store.deletePlaylist(id)
                load()
                throw it
            }
            id
        }
    }

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        store.deletePlaylist(id)
        load()
        _dataVersion.value++
    }

    suspend fun syncAllIfStale() {
        load()
        _playlists.value.forEach { sync(it.id, force = false) }
    }

    suspend fun sync(id: Long, force: Boolean): Result<Unit> = syncLock.withLock {
        withContext(Dispatchers.IO) {
            val playlist = store.playlists().firstOrNull { it.id == id } ?: return@withContext Result.success(Unit)
            val now = System.currentTimeMillis()
            var guideError: Throwable? = null
            runCatching {
                var epgSource = playlist.epgUrl
                if (force || now - playlist.lastSync > CHANNEL_REFRESH_MS || playlist.channelCount == 0) {
                    _sync.value = SyncState.Running(playlist.name, "Loading channels")
                    val (channels, sourceEpg) = fetchChannels(playlist)
                    if (channels.isEmpty()) throw IptvException("The playlist has no channels.")
                    store.replaceChannels(id, channels)
                    store.markSynced(id, epg = false)
                    if (epgSource == null) epgSource = sourceEpg
                    _dataVersion.value++
                } else if (epgSource == null) {
                    epgSource = if (playlist.type == PlaylistType.XTREAM) XtreamClient(http, playlist).epgUrl() else savedEpgUrl(id)
                }
                if (epgSource != null) {
                    prefs.edit().putString("epg_$id", epgSource).apply()
                    if (force || now - playlist.lastEpgSync > EPG_REFRESH_MS) {
                        _sync.value = SyncState.Running(playlist.name, "Loading TV guide")
                        guideError = runCatching { importGuide(id, playlist.userAgent, epgSource) }
                            .onFailure { android.util.Log.w("IptvRepository", "Guide import failed", it) }
                            .onSuccess {
                                store.markSynced(id, epg = true)
                                _dataVersion.value++
                            }
                            .exceptionOrNull()
                    }
                }
                load()
                _sync.value = guideError?.let { SyncState.Failed(playlist.name, "TV guide: ${it.message}") } ?: SyncState.Idle
            }.onFailure {
                android.util.Log.w("IptvRepository", "Sync failed for ${playlist.name}", it)
                _sync.value = SyncState.Failed(playlist.name, it.message ?: "Sync failed")
            }
        }
    }

    private fun savedEpgUrl(id: Long): String? = prefs.getString("epg_$id", null)

    private suspend fun fetchChannels(playlist: Playlist): Pair<List<ParsedChannel>, String?> = when (playlist.type) {
        PlaylistType.XTREAM -> {
            val client = XtreamClient(http, playlist)
            val ext = client.authenticate()
            client.timezone?.let { prefs.edit().putString("tz_${playlist.id}", it).apply() }
            client.liveChannels(ext) to client.epgUrl()
        }
        PlaylistType.M3U -> {
            val parsed = http.open(playlist.url, playlist.userAgent).use { M3uParser.parse(it) }
            parsed.channels to parsed.epgUrl
        }
    }

    /**
     * Streams the XMLTV file straight into SQLite, keeping only programmes for this playlist's
     * channels within the guide window. Channels without a tvg-id are matched by display name.
     */
    private fun importGuide(playlistId: Long, userAgent: String?, url: String) {
        val wanted = store.epgIds(playlistId).toMutableSet()
        val byName = store.unmatchedNames(playlistId)
        val nameMatches = HashMap<Long, String>()
        val now = System.currentTimeMillis()
        val from = now - EPG_DAYS_BACK * DAY_MS
        val to = now + EPG_DAYS_AHEAD * DAY_MS

        val writer = store.programWriter(playlistId)
        try {
            http.open(url, userAgent).use { stream ->
                parseGuideTolerant(writer) {
                    XmltvParser.parse(
                    stream,
                    onChannel = { id, name ->
                        byName[name.normalizedName()]?.let { channelId ->
                            if (channelId !in nameMatches) {
                                nameMatches[channelId] = id
                                wanted += id
                            }
                        }
                    },
                        onProgram = { channel, start, stop, title, desc ->
                            if (channel in wanted && stop > from && start < to) writer.add(channel, start, stop, title, desc)
                        },
                    )
                }
            }
            writer.finish()
        } catch (e: Exception) {
            writer.abort()
            throw e
        }
        if (nameMatches.isNotEmpty()) store.assignEpgIds(nameMatches)
    }

    /**
     * Guide files from panels sometimes end mid-document (the connection closes early). If we
     * already have programmes, keep them rather than throwing the whole guide away.
     */
    private inline fun parseGuideTolerant(writer: ProgramWriter, parse: () -> Unit) {
        try {
            parse()
        } catch (e: Exception) {
            if (writer.count == 0) throw e
            android.util.Log.w("IptvRepository", "Guide ended early; kept ${writer.count} programmes", e)
        }
    }

    // ---- queries for the UI (call off the main thread) ----

    /** Groups A to Z, with the 24/7 loop channels ("24/7 Comedy"...) after the rest, also A to Z. */
    suspend fun groups(playlistId: Long): List<String> = withContext(Dispatchers.IO) {
        store.groups(playlistId).sortedWith(
            compareBy<String> { TWENTY_FOUR_SEVEN.containsMatchIn(it) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.trim() },
        )
    }

    /** Channels for a screen. Locked groups stay hidden until the parental PIN is entered. */
    suspend fun channels(playlistId: Long, filter: ChannelFilter): List<Channel> = withContext(Dispatchers.IO) {
        val list = store.channels(playlistId, filter)
        if (unlocked.value || !hasParentalPin) return@withContext list
        val locked = lockedGroups(playlistId)
        if (locked.isEmpty()) list else list.filter { it.group !in locked }
    }

    suspend fun programs(playlistId: Long, epgIds: Collection<String>, from: Long, to: Long): Map<String, List<Program>> =
        withContext(Dispatchers.IO) { store.programs(playlistId, epgIds, from, to) }

    suspend fun setFavorite(channel: Channel, favorite: Boolean) = withContext(Dispatchers.IO) {
        store.setFavorite(channel.playlistId, channel.key, favorite)
        _dataVersion.value++
    }

    /**
     * Archive URL for a past programme on a catch-up channel (Xtream "timeshift"), or null when
     * the channel has no archive or the programme is older than the provider keeps.
     */
    fun catchupUrl(channel: Channel, program: Program, now: Long = System.currentTimeMillis()): String? {
        if (channel.catchupDays <= 0 || program.stop > now) return null
        if (program.start < now - channel.catchupDays * DAY_MS) return null
        val m = XTREAM_LIVE_URL.matchEntire(channel.streamUrl) ?: return null
        val (base, user, pass, id) = m.destructured
        val zone = runCatching { java.time.ZoneId.of(prefs.getString("tz_${channel.playlistId}", "UTC")) }
            .getOrDefault(java.time.ZoneOffset.UTC)
        val start = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")
            .format(java.time.Instant.ofEpochMilli(program.start).atZone(zone))
        val minutes = ((program.stop - program.start + 59_999) / 60_000).coerceAtLeast(1)
        return "$base/timeshift/$user/$pass/$minutes/$start/$id.ts"
    }

    // ---- parental lock ----

    private val _unlocked = MutableStateFlow(false)
    /** True after the PIN was entered; lasts until the app is closed. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    val hasParentalPin: Boolean get() = prefs.getString("parental_pin", null) != null

    /** Setting null turns the lock off entirely: every group is unlocked and unmarked. */
    fun setParentalPin(pin: String?) {
        prefs.edit().apply {
            if (pin == null) {
                remove("parental_pin")
                prefs.all.keys.filter { it.startsWith("locked_") }.forEach(::remove)
            } else {
                putString("parental_pin", pin)
            }
        }.apply()
        _unlocked.value = false
        _dataVersion.value++
    }

    /** Checks the PIN and, if right, shows locked groups for the rest of the session. */
    fun unlock(pin: String): Boolean {
        val ok = pin == prefs.getString("parental_pin", null)
        if (ok) {
            _unlocked.value = true
            _dataVersion.value++
        }
        return ok
    }

    fun relock() {
        _unlocked.value = false
        _dataVersion.value++
    }

    fun lockedGroups(playlistId: Long): Set<String> = prefs.getStringSet("locked_$playlistId", emptySet()).orEmpty()

    fun setGroupLocked(playlistId: Long, group: String, locked: Boolean) {
        val now = lockedGroups(playlistId).toMutableSet().apply { if (locked) add(group) else remove(group) }
        prefs.edit().putStringSet("locked_$playlistId", now).apply()
        _dataVersion.value++
    }

    // ---- channel direction ----

    /** Off: Up goes to the previous (lower) channel, like TiviMate. On: Up goes to the next. */
    var upIsNextChannel: Boolean
        get() = prefs.getBoolean("up_is_next", false)
        set(value) = prefs.edit().putBoolean("up_is_next", value).apply()

    // ---- reminders ----

    private val _reminders = MutableStateFlow(loadReminders())
    val reminders: StateFlow<List<Reminder>> = _reminders.asStateFlow()

    private fun loadReminders(): List<Reminder> =
        prefs.getStringSet("reminders", emptySet()).orEmpty().mapNotNull(Reminder::decode).sortedBy { it.start }

    private fun saveReminders(list: List<Reminder>) {
        prefs.edit().putStringSet("reminders", list.map { it.encode() }.toSet()).apply()
        _reminders.value = list.sortedBy { it.start }
    }

    fun hasReminder(channel: Channel, program: Program): Boolean =
        _reminders.value.any { it.channelId == channel.id && it.start == program.start }

    fun toggleReminder(channel: Channel, program: Program) {
        val list = _reminders.value
        if (hasReminder(channel, program)) {
            saveReminders(list.filterNot { it.channelId == channel.id && it.start == program.start })
        } else {
            saveReminders(list + Reminder(channel.playlistId, channel.id, channel.name, program.title, program.start))
        }
    }

    fun dismissReminder(reminder: Reminder) = saveReminders(_reminders.value - reminder)

    /** Drops reminders for programmes that started more than 10 minutes ago. */
    fun pruneReminders(now: Long = System.currentTimeMillis()) {
        val keep = _reminders.value.filter { it.start > now - 10 * 60_000L }
        if (keep.size != _reminders.value.size) saveReminders(keep)
    }

    suspend fun channel(playlistId: Long, channelId: Long): Channel? =
        channels(playlistId, ChannelFilter.All).firstOrNull { it.id == channelId }

    fun userAgent(playlistId: Long): String? = _playlists.value.firstOrNull { it.id == playlistId }?.userAgent
}
