package com.logan.shieldmedia.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

private const val LATEST_RELEASE = "https://api.github.com/repos/penwal/shield-media/releases/latest"
private const val APK_NAME = "ShieldMedia.apk"

/** A newer version published on GitHub. */
data class AppRelease(val version: String, val notes: String, val apkUrl: String, val size: Long)

/**
 * Updates from the GitHub releases page: [check] finds a newer release, [download] fetches its
 * APK, [install] hands it to Android's installer (which asks the viewer to confirm).
 */
class AppUpdater(private val context: Context, private val okHttp: OkHttpClient) {

    val installedVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** The latest release if it's newer than this install, else null (also when offline). */
    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(LATEST_RELEASE).header("Accept", "application/vnd.github+json").build()
            val json = okHttp.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                JSONObject(r.body.string())
            }
            val version = json.getString("tag_name").removePrefix("v")
            if (!isNewer(version, installedVersion)) return@runCatching null
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map(assets::getJSONObject).firstOrNull { it.getString("name") == APK_NAME }
                ?: return@runCatching null
            AppRelease(
                version = version,
                notes = json.optString("body").trim(),
                apkUrl = apk.getString("browser_download_url"),
                size = apk.optLong("size"),
            )
        }.getOrNull()
    }

    /** Downloads the release's APK, reporting progress from 0 to 1. */
    suspend fun download(release: AppRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, APK_NAME)
        okHttp.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val total = r.body.contentLength().takeIf { it > 0 } ?: release.size
            r.body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var reported = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        // Whole percents only: a report per 64 KB chunk would recompose the prompt constantly.
                        val percent = if (total > 0) (done * 100 / total).toInt().coerceAtMost(100) else -1
                        if (percent > reported) {
                            reported = percent
                            onProgress(percent / 100f)
                        }
                    }
                    if (total > 0 && done != total) throw IOException("Download was cut short")
                }
            }
        }
        file
    }

    /** Opens Android's installer for the downloaded APK. */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/** Compares dotted versions number by number: "0.10.0" is newer than "0.9.2". */
internal fun isNewer(candidate: String, installed: String): Boolean {
    val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
    val b = installed.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
