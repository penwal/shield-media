package com.logan.shieldmedia.iptv

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain SQLite rather than Room: the EPG import is a bulk insert of up to millions of rows,
 * which is fastest with one compiled statement inside a transaction, and there is no
 * annotation processing to keep in step with the Kotlin/AGP versions.
 */
internal class IptvStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "iptv.db", null, 1) {

    init {
        setWriteAheadLoggingEnabled(true) // guide stays readable while a sync writes
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE playlists (
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, type TEXT NOT NULL, url TEXT NOT NULL,
                username TEXT, password TEXT, epg_url TEXT, user_agent TEXT,
                last_sync INTEGER NOT NULL DEFAULT 0, last_epg_sync INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL(
            """CREATE TABLE channels (
                id INTEGER PRIMARY KEY AUTOINCREMENT, playlist_id INTEGER NOT NULL, channel_key TEXT NOT NULL,
                number INTEGER NOT NULL, name TEXT NOT NULL, logo TEXT, group_name TEXT NOT NULL,
                stream_url TEXT NOT NULL, epg_id TEXT, catchup_days INTEGER NOT NULL DEFAULT 0,
                sort_index INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX channels_order ON channels(playlist_id, sort_index)")
        db.execSQL("CREATE INDEX channels_group ON channels(playlist_id, group_name, sort_index)")
        db.execSQL(
            """CREATE TABLE favorites (playlist_id INTEGER NOT NULL, channel_key TEXT NOT NULL,
                PRIMARY KEY (playlist_id, channel_key))""",
        )
        db.execSQL(
            """CREATE TABLE programs (playlist_id INTEGER NOT NULL, epg_id TEXT NOT NULL, start INTEGER NOT NULL,
                stop INTEGER NOT NULL, title TEXT NOT NULL, description TEXT)""",
        )
        db.execSQL("CREATE INDEX programs_lookup ON programs(playlist_id, epg_id, start)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---- playlists ----

    fun playlists(): List<Playlist> = readableDatabase.rawQuery(
        """SELECT p.*, (SELECT COUNT(*) FROM channels c WHERE c.playlist_id = p.id) AS channel_count
           FROM playlists p ORDER BY p.id""",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Playlist(
                        id = c.long("id"),
                        name = c.str("name")!!,
                        type = PlaylistType.valueOf(c.str("type")!!),
                        url = c.str("url")!!,
                        username = c.str("username"),
                        password = c.str("password"),
                        epgUrl = c.str("epg_url"),
                        userAgent = c.str("user_agent"),
                        lastSync = c.long("last_sync"),
                        lastEpgSync = c.long("last_epg_sync"),
                        channelCount = c.int("channel_count"),
                    ),
                )
            }
        }
    }

    fun insertPlaylist(p: Playlist): Long = writableDatabase.insertOrThrow(
        "playlists",
        null,
        ContentValues().apply {
            put("name", p.name)
            put("type", p.type.name)
            put("url", p.url)
            put("username", p.username)
            put("password", p.password)
            put("epg_url", p.epgUrl)
            put("user_agent", p.userAgent)
        },
    )

    fun deletePlaylist(id: Long) = writableDatabase.transaction {
        val args = arrayOf(id.toString())
        delete("programs", "playlist_id = ?", args)
        delete("channels", "playlist_id = ?", args)
        delete("favorites", "playlist_id = ?", args)
        delete("playlists", "id = ?", args)
    }

    fun markSynced(id: Long, epg: Boolean) {
        writableDatabase.update(
            "playlists",
            ContentValues().apply { put(if (epg) "last_epg_sync" else "last_sync", System.currentTimeMillis()) },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    // ---- channels ----

    fun replaceChannels(playlistId: Long, channels: List<ParsedChannel>) = writableDatabase.transaction {
        delete("channels", "playlist_id = ?", arrayOf(playlistId.toString()))
        val stmt = compileStatement(
            """INSERT INTO channels (playlist_id, channel_key, number, name, logo, group_name, stream_url,
               epg_id, catchup_days, sort_index) VALUES (?,?,?,?,?,?,?,?,?,?)""",
        )
        channels.forEachIndexed { i, ch ->
            stmt.clearBindings()
            stmt.bindLong(1, playlistId)
            stmt.bindString(2, ch.key)
            stmt.bindLong(3, (ch.number ?: (i + 1)).toLong())
            stmt.bindString(4, ch.name)
            ch.logo?.let { stmt.bindString(5, it) } ?: stmt.bindNull(5)
            stmt.bindString(6, ch.group)
            stmt.bindString(7, ch.url)
            ch.epgId?.let { stmt.bindString(8, it) } ?: stmt.bindNull(8)
            stmt.bindLong(9, ch.catchupDays.toLong())
            stmt.bindLong(10, i.toLong())
            stmt.executeInsert()
        }
    }

    fun channels(playlistId: Long, filter: ChannelFilter): List<Channel> {
        val (where, args) = when (filter) {
            ChannelFilter.All -> "c.playlist_id = ?" to arrayOf(playlistId.toString())
            ChannelFilter.Favorites -> "c.playlist_id = ? AND f.channel_key IS NOT NULL" to arrayOf(playlistId.toString())
            is ChannelFilter.Group -> "c.playlist_id = ? AND c.group_name = ?" to arrayOf(playlistId.toString(), filter.name)
        }
        return readableDatabase.rawQuery(
            """SELECT c.*, f.channel_key IS NOT NULL AS fav FROM channels c
               LEFT JOIN favorites f ON f.playlist_id = c.playlist_id AND f.channel_key = c.channel_key
               WHERE $where ORDER BY c.sort_index""",
            args,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Channel(
                            id = c.long("id"),
                            playlistId = c.long("playlist_id"),
                            key = c.str("channel_key")!!,
                            number = c.int("number"),
                            name = c.str("name")!!,
                            logo = c.str("logo"),
                            group = c.str("group_name")!!,
                            streamUrl = c.str("stream_url")!!,
                            epgId = c.str("epg_id"),
                            catchupDays = c.int("catchup_days"),
                            favorite = c.int("fav") == 1,
                        ),
                    )
                }
            }
        }
    }

    /** Groups in playlist order (the order of each group's first channel). */
    fun groups(playlistId: Long): List<String> = readableDatabase.rawQuery(
        "SELECT group_name FROM channels WHERE playlist_id = ? GROUP BY group_name ORDER BY MIN(sort_index)",
        arrayOf(playlistId.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun epgIds(playlistId: Long): Set<String> = readableDatabase.rawQuery(
        "SELECT DISTINCT epg_id FROM channels WHERE playlist_id = ? AND epg_id IS NOT NULL",
        arrayOf(playlistId.toString()),
    ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    /** Channels without a tvg-id, by lower-cased name, so the guide can match them on display name. */
    fun unmatchedNames(playlistId: Long): Map<String, Long> = readableDatabase.rawQuery(
        "SELECT id, name FROM channels WHERE playlist_id = ? AND (epg_id IS NULL OR epg_id = '')",
        arrayOf(playlistId.toString()),
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(1).normalizedName(), c.getLong(0)) } }

    fun assignEpgIds(ids: Map<Long, String>) = writableDatabase.transaction {
        val stmt = compileStatement("UPDATE channels SET epg_id = ? WHERE id = ?")
        ids.forEach { (channelId, epgId) ->
            stmt.bindString(1, epgId)
            stmt.bindLong(2, channelId)
            stmt.executeUpdateDelete()
        }
    }

    fun setFavorite(playlistId: Long, key: String, favorite: Boolean) {
        if (favorite) {
            writableDatabase.insertWithOnConflict(
                "favorites",
                null,
                ContentValues().apply {
                    put("playlist_id", playlistId)
                    put("channel_key", key)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        } else {
            writableDatabase.delete("favorites", "playlist_id = ? AND channel_key = ?", arrayOf(playlistId.toString(), key))
        }
    }

    // ---- programs ----

    /** Opens a bulk import; [ProgramWriter.finish] swaps the new guide in atomically. */
    fun programWriter(playlistId: Long): ProgramWriter = ProgramWriter(writableDatabase, playlistId)

    fun programs(playlistId: Long, epgIds: Collection<String>, from: Long, to: Long): Map<String, List<Program>> {
        if (epgIds.isEmpty()) return emptyMap()
        val result = HashMap<String, MutableList<Program>>()
        // Chunked to stay under SQLite's bound-parameter limit.
        epgIds.chunked(400).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                """SELECT epg_id, start, stop, title, description FROM programs
                   WHERE playlist_id = ? AND epg_id IN ($placeholders) AND stop > ? AND start < ?
                   ORDER BY start""",
                arrayOf(playlistId.toString()) + chunk + arrayOf(from.toString(), to.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val p = Program(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4))
                    result.getOrPut(p.epgId) { mutableListOf() }.add(p)
                }
            }
        }
        return result
    }
}

internal class ProgramWriter(private val db: SQLiteDatabase, private val playlistId: Long) {
    private val stmt = db.compileStatement("INSERT INTO programs VALUES (?,?,?,?,?,?)")
    var count = 0
        private set

    init {
        db.beginTransaction()
        db.delete("programs", "playlist_id = ?", arrayOf(playlistId.toString()))
    }

    fun add(epgId: String, start: Long, stop: Long, title: String, description: String?) {
        stmt.clearBindings()
        stmt.bindLong(1, playlistId)
        stmt.bindString(2, epgId)
        stmt.bindLong(3, start)
        stmt.bindLong(4, stop)
        stmt.bindString(5, title)
        description?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
        stmt.executeInsert()
        count++
    }

    fun finish() {
        db.setTransactionSuccessful()
        db.endTransaction()
    }

    fun abort() {
        if (db.inTransaction()) db.endTransaction()
    }
}

internal fun String.normalizedName(): String =
    lowercase().replace(Regex("""\b(hd|fhd|uhd|4k|sd)\b"""), "").replace(Regex("[^a-z0-9]"), "")

private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try {
        block()
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}

private fun Cursor.str(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
private fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))
private fun Cursor.int(col: String): Int = getInt(getColumnIndexOrThrow(col))
