package com.logan.shieldmedia.iptv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.logan.shieldmedia.iptv.Channel
import com.logan.shieldmedia.iptv.ChannelFilter
import com.logan.shieldmedia.iptv.IptvRepository
import com.logan.shieldmedia.iptv.Program
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

internal const val SLOT_MS = 30 * 60_000L
internal const val WINDOW_MS = 4 * SLOT_MS // two hours on screen
private const val CACHE_BEFORE_MS = 6 * 60 * 60_000L
private const val CACHE_AFTER_MS = 24 * 60 * 60_000L
private const val PAST_LIMIT_MS = 2 * 60 * 60_000L
private const val DAY_MS = 24 * 60 * 60_000L

internal fun snapToSlot(t: Long) = t - t % SLOT_MS

/**
 * Guide state. Focus is logical (row + a point in time) rather than one Compose focus target per
 * programme: moving around a 2,000-channel guide never asks Compose to search thousands of nodes.
 */
class GuideViewModel(private val repo: IptvRepository) : ViewModel() {
    var playlistId by mutableLongStateOf(0L)
        private set
    var filter by mutableStateOf<ChannelFilter>(ChannelFilter.All)
        private set
    var groups by mutableStateOf<List<String>>(emptyList())
        private set
    var channels by mutableStateOf<List<Channel>?>(null)
        private set
    /** How far back the guide scrolls: 2 h normally, up to a day when channels have catch-up. */
    private var pastLimitMs = PAST_LIMIT_MS

    var focusedRow by mutableIntStateOf(0)
        private set
    var focusedTime by mutableLongStateOf(System.currentTimeMillis())
        private set
    var windowStart by mutableLongStateOf(snapToSlot(System.currentTimeMillis()))
        private set

    /** Programmes by epg id for the cached time range. */
    val programs = mutableStateMapOf<String, List<Program>>()
    private var cacheFrom = 0L
    private var cacheTo = 0L
    private val requested = HashSet<String>()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            repo.load()
            reload()
        }
        viewModelScope.launch { repo.dataVersion.drop(1).collect { reload() } }
    }

    private suspend fun reload() {
        val id = repo.activePlaylistId.value
        val changedPlaylist = id != playlistId
        playlistId = id
        if (id == 0L) {
            channels = emptyList()
            return
        }
        groups = repo.groups(id)
        lockedGroups = repo.lockedGroups(id)
        if (changedPlaylist) filter = ChannelFilter.All
        val list = repo.channels(id, filter)
        channels = list
        pastLimitMs = maxOf(PAST_LIMIT_MS, (list.maxOfOrNull { it.catchupDays } ?: 0).coerceAtMost(1) * DAY_MS)
        focusedRow = focusedRow.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        programs.clear()
        requested.clear()
        cacheFrom = 0
    }

    fun selectFilter(newFilter: ChannelFilter) {
        filter = newFilter
        focusedRow = 0
        viewModelScope.launch { reload() }
    }

    /** Loads guide data for channels near the focused row; cheap to call on every move. */
    fun ensurePrograms(rows: IntRange) {
        val list = channels ?: return
        if (windowStart < cacheFrom || windowStart + WINDOW_MS > cacheTo) {
            cacheFrom = windowStart - CACHE_BEFORE_MS
            cacheTo = windowStart + CACHE_AFTER_MS
            programs.clear()
            requested.clear()
        }
        val ids = rows.mapNotNull { list.getOrNull(it)?.epgId }.filter { it !in requested }
        if (ids.isEmpty()) return
        requested += ids
        val (from, to, pid) = Triple(cacheFrom, cacheTo, playlistId)
        loadJob = viewModelScope.launch {
            val loaded = repo.programs(pid, ids, from, to)
            // Dropped if the guide moved to another time range or playlist while this was loading.
            if (from != cacheFrom || pid != playlistId) return@launch
            ids.forEach { programs[it] = loaded[it].orEmpty() }
        }
    }

    /** Real programmes, or half-hour "No information" blocks so empty rows still navigate. */
    fun blocks(channel: Channel): List<Program> {
        val real = channel.epgId?.let { programs[it] }
        if (!real.isNullOrEmpty()) return real
        val start = windowStart - 2 * WINDOW_MS
        return (0 until 12).map { i ->
            val s = start + i * SLOT_MS
            Program(channel.epgId ?: "", s, s + SLOT_MS, "", null)
        }
    }

    fun focusedChannel(): Channel? = channels?.getOrNull(focusedRow)

    fun focusedProgram(): Program? {
        val ch = focusedChannel() ?: return null
        return blocks(ch).firstOrNull { focusedTime in it.start until it.stop }
    }

    fun moveRow(delta: Int) {
        val size = channels?.size ?: return
        focusedRow = (focusedRow + delta).coerceIn(0, (size - 1).coerceAtLeast(0))
    }

    fun moveTime(forward: Boolean) {
        val ch = focusedChannel() ?: return
        val current = blocks(ch).firstOrNull { focusedTime in it.start until it.stop }
        val now = System.currentTimeMillis()
        val target = if (forward) {
            current?.stop ?: (snapToSlot(focusedTime) + SLOT_MS)
        } else {
            (current?.start ?: snapToSlot(focusedTime)) - 1
        }
        val earliest = snapToSlot(now) - pastLimitMs
        if (target < earliest) return
        focusedTime = target
        // Keep the focused programme on screen, scrolling the timeline in half-hour steps.
        while (focusedTime >= windowStart + WINDOW_MS - SLOT_MS / 2) windowStart += SLOT_MS
        while (focusedTime < windowStart) windowStart -= SLOT_MS
    }

    fun jumpToNow() {
        val now = System.currentTimeMillis()
        focusedTime = now
        windowStart = snapToSlot(now)
    }

    /** Archive URL for the focused programme if it can be watched from the start (catch-up). */
    fun focusedCatchupUrl(): String? {
        val ch = focusedChannel() ?: return null
        val p = focusedProgram()?.takeIf { it.title.isNotEmpty() } ?: return null
        return repo.catchupUrl(ch, p)
    }

    fun hasCatchup(channel: Channel, program: Program, now: Long): Boolean =
        channel.catchupDays > 0 && program.title.isNotEmpty() && program.stop <= now &&
            program.start >= now - channel.catchupDays * DAY_MS

    // ---- parental lock & reminders (thin pass-throughs so the screen talks to one object) ----

    var lockedGroups by mutableStateOf<Set<String>>(emptySet())
        private set
    val unlocked: Boolean get() = repo.unlocked.value
    val hasParentalPin: Boolean get() = repo.hasParentalPin

    fun setParentalPin(pin: String) = repo.setParentalPin(pin)
    fun checkPin(pin: String): Boolean = repo.unlock(pin)
    fun unlock(pin: String): Boolean = repo.unlock(pin)

    fun setGroupLocked(group: String, locked: Boolean) {
        repo.setGroupLocked(playlistId, group, locked)
        lockedGroups = repo.lockedGroups(playlistId)
    }

    fun hasReminder(channel: Channel, program: Program) = repo.hasReminder(channel, program)
    fun toggleReminder(channel: Channel, program: Program) = repo.toggleReminder(channel, program)

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch { repo.setFavorite(channel, !channel.favorite) }
    }
}
