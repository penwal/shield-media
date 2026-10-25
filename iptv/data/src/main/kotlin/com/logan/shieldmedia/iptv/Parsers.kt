package com.logan.shieldmedia.iptv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream

/** A channel as read from a source, before it has a database id. */
internal data class ParsedChannel(
    val key: String,
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val epgId: String?,
    val number: Int?,
    val catchupDays: Int,
)

internal data class ParsedM3u(val channels: List<ParsedChannel>, val epgUrl: String?)

internal object M3uParser {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(input: InputStream): ParsedM3u {
        val channels = ArrayList<ParsedChannel>()
        var epgUrl: String? = null
        var pending: Map<String, String>? = null
        var pendingName = ""
        var pendingGroup: String? = null

        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("#EXTM3U") -> {
                        val header = attrs(line)
                        epgUrl = (header["url-tvg"] ?: header["x-tvg-url"])?.split(',')?.firstOrNull()?.trim()
                    }
                    line.startsWith("#EXTINF") -> {
                        pending = attrs(line)
                        pendingName = displayName(line)
                        pendingGroup = null
                    }
                    line.startsWith("#EXTGRP:") -> pendingGroup = line.removePrefix("#EXTGRP:").trim()
                    line.startsWith("#") -> Unit
                    pending != null -> {
                        val a = pending!!
                        val name = a["tvg-name"]?.takeIf { pendingName.isBlank() } ?: pendingName.ifBlank { "Channel ${channels.size + 1}" }
                        channels += ParsedChannel(
                            key = line,
                            name = name,
                            url = line,
                            group = (a["group-title"] ?: pendingGroup)?.split(';')?.firstOrNull()?.trim()?.ifEmpty { null } ?: "Uncategorized",
                            logo = a["tvg-logo"]?.ifBlank { null },
                            epgId = a["tvg-id"]?.ifBlank { null },
                            number = a["tvg-chno"]?.toIntOrNull(),
                            catchupDays = (a["catchup-days"] ?: a["tvg-rec"])?.toIntOrNull() ?: 0,
                        )
                        pending = null
                    }
                }
            }
        }
        return ParsedM3u(channels, epgUrl)
    }

    /** The display name follows the first comma that isn't inside a quoted attribute value. */
    private fun displayName(line: String): String {
        var inQuotes = false
        line.forEachIndexed { i, c ->
            if (c == '"') inQuotes = !inQuotes else if (c == ',' && !inQuotes) return line.substring(i + 1).trim()
        }
        return ""
    }

    private fun attrs(line: String): Map<String, String> =
        attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
}

/**
 * Streaming XMLTV reader: never holds the document in memory, so a 500 MB guide is fine.
 * Calls [onChannel] for <channel> display names and [onProgram] for each <programme>.
 */
internal object XmltvParser {

    fun parse(
        input: InputStream,
        onChannel: (id: String, displayName: String) -> Unit,
        onProgram: (channel: String, start: Long, stop: Long, title: String, desc: String?) -> Unit,
    ) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(maybeGunzip(input), null)

        var channelId: String? = null
        var progChannel: String? = null
        var progStart = 0L
        var progStop = 0L
        var title: String? = null
        var desc: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> channelId = parser.getAttributeValue(null, "id")
                    "display-name" -> {
                        val id = channelId
                        val name = parser.nextText()
                        if (id != null && name.isNotBlank()) onChannel(id, name)
                    }
                    "programme" -> {
                        progChannel = parser.getAttributeValue(null, "channel")
                        progStart = parseTime(parser.getAttributeValue(null, "start"))
                        progStop = parseTime(parser.getAttributeValue(null, "stop"))
                        title = null
                        desc = null
                    }
                    "title" -> if (progChannel != null && title == null) title = parser.nextText()
                    "desc" -> if (progChannel != null && desc == null) desc = parser.nextText()
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "channel" -> channelId = null
                    "programme" -> {
                        val ch = progChannel
                        if (ch != null && progStart > 0 && progStop > progStart) {
                            onProgram(ch, progStart, progStop, title ?: "", desc?.ifBlank { null })
                        }
                        progChannel = null
                    }
                }
            }
            event = parser.next()
        }
    }

    /** XMLTV time: "20251025120000 +0100" (offset optional). Hand-parsed: it runs millions of times. */
    internal fun parseTime(value: String?): Long {
        if (value == null || value.length < 14) return 0
        return try {
            val dt = LocalDateTime.of(
                value.substring(0, 4).toInt(),
                value.substring(4, 6).toInt(),
                value.substring(6, 8).toInt(),
                value.substring(8, 10).toInt(),
                value.substring(10, 12).toInt(),
                value.substring(12, 14).toInt(),
            )
            val tz = value.substring(14).trim()
            val offset = if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
                val sign = if (tz[0] == '-') -1 else 1
                ZoneOffset.ofHoursMinutes(sign * tz.substring(1, 3).toInt(), sign * tz.substring(3, 5).toInt())
            } else {
                ZoneOffset.UTC
            }
            dt.toInstant(offset).toEpochMilli()
        } catch (e: RuntimeException) {
            0
        }
    }

    private fun maybeGunzip(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024)) else buffered
    }
}
