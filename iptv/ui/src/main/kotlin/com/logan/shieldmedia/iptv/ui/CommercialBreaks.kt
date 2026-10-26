package com.logan.shieldmedia.iptv.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.iptv.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs

/*
 * Hiding commercials on live TV.
 *
 * Most channels keep their logo (or a scoreboard) in a corner during the programme and drop it
 * for the ad break. A few times a second we copy a small version of the picture, learn which
 * corner pixels are always edges (the logo), and watch whether they are still there. This is the
 * "logo edge" method Comskip and YoutubeOverCommercials use, made to decide on the spot.
 *
 * Tuned on recordings of real channels (AMC, TNT hockey, HGTV):
 *  - logo gone + a black frame (how breaks start): cover within ~1.5 s
 *  - logo gone without a black frame: cover only once it's been mostly gone for 25 s, so a
 *    network pop-up over the logo, a sports replay or a stats graphic doesn't trigger it
 *  - uncover when the logo is back pixel-for-pixel (an advertiser's own logo in the same spot
 *    doesn't line up exactly); early in a break it must hold for 8 s, as network promos carry
 *    the channel logo briefly
 *
 * Everything lives in this file; LivePlayerScreen only asks "in a break?" and shows the cover.
 */

private const val TAG = "CommercialBreaks"
private const val SAMPLE_MS = 250L
private const val SAVE_EVERY_MS = 60_000L

/**
 * The detector itself: feed it 320x180 luma frames at [LogoTracker.FPS] per second and read
 * [isAd]. Plain logic (Base64 aside) mirroring the prototype it was tuned with.
 */
internal class LogoTracker {
    companion object {
        const val W = 320
        const val H = 180
        const val FPS = 4
        private const val BOX_W = 96 // corner search boxes: 30% x 30% of the frame
        private const val BOX_H = 54
        private const val EDGE_T = 30 // luma gradient that counts as an edge
        private const val TAU_S = 60f // how long the edge-rate average remembers
        private const val MASK_RATE = 0.85f // "always an edge": part of the logo
        private const val MIN_MASK = 25 // fewer logo pixels than this: no logo on this channel
        private const val MIN_SHAPE = 20 // smallest separate logo shape tracked on its own
        private const val ENTER_SCORE = 0.30f // below this the logo counts as gone
        private const val ENTER_N = 6 // 1.5 s gone, with a black frame: cover
        private const val SLOW_N = 25 * FPS // 25 s mostly gone, without one: cover
        private const val SLOW_SHARE = 0.85f
        private const val EXACT = 0.92f // share of a logo shape that must be back exactly
        private const val EARLY_S = 60 // in the first minute of a break...
        private const val EARLY_EXIT_N = 8 * FPS // ...the logo must be back for 8 s
        private const val LATE_EXIT_N = 3 * FPS // after that, 3 s
        private const val BLACK_MEAN = 20 // a black frame
        private const val BLACK_SPREAD = 10
        private const val BLACK_BEFORE_S = 4 // black up to this long before the logo went counts
        private const val MAX_AD_S = 6 * 60 // longer than any break: the logo must have changed
        private val ORIGINS = arrayOf(0 to 0, W - BOX_W to 0, 0 to H - BOX_H, W - BOX_W to H - BOX_H)
    }

    var isAd = false
        private set
    val hasLogo: Boolean get() = mask != null

    private val rate = Array(4) { FloatArray(BOX_W * BOX_H) }
    private val edges = Array(4) { BooleanArray(BOX_W * BOX_H) }
    private val alpha = 1f / (FPS * TAU_S)
    private var samples = 0
    private var frame = 0
    private var mask: BooleanArray? = null
    private var maskSize = 0
    private var shapes: List<BooleanArray> = emptyList()
    private var corner = -1
    private var runLength = 0
    private var adFrames = 0
    private var adEnteredAfter = 0 // samples the logo had been gone when the break was called
    private var lastBlack = -1_000_000
    private val recent = BooleanArray(SLOW_N) // ring buffer: was the logo gone, per sample
    private var recentCount = 0
    private var recentFilled = 0
    private var recentPos = 0

    fun feed(luma: ByteArray) {
        val now = frame++
        if (isBlack(luma)) lastBlack = now
        for (k in 0 until 4) boxEdges(luma, ORIGINS[k], edges[k])
        val m = mask
        val score = if (m != null) presence(edges[corner], m) else 1f

        // Learn only while the programme is on and its logo visible: an ad break, a replay or a
        // dark scene must never wear the learned logo away.
        if (!isAd && score >= ENTER_SCORE) {
            for (k in 0 until 4) {
                val r = rate[k]
                val e = edges[k]
                for (i in r.indices) r[i] += alpha * ((if (e[i]) 1f else 0f) - r[i])
            }
            samples++
            if (samples % (2 * FPS) == 0 && samples > FPS * TAU_S * 1.9f) learnMask()
        }
        if (mask == null) return

        if (!isAd) {
            val gone = score < ENTER_SCORE
            pushRecent(gone)
            runLength = if (gone) runLength + 1 else 0
            val fast = runLength >= ENTER_N && now - lastBlack <= runLength + BLACK_BEFORE_S * FPS
            val slow = recentFilled == SLOW_N && recentCount >= SLOW_SHARE * SLOW_N && gone
            if (fast || slow) {
                isAd = true
                adEnteredAfter = if (fast) ENTER_N else SLOW_N
                adFrames = 0
                runLength = 0
                clearRecent()
            }
        } else {
            adFrames++
            val inBreak = adFrames + adEnteredAfter
            runLength = if (exactMatch(edges[corner]) > EXACT) runLength + 1 else 0
            val need = if (inBreak < EARLY_S * FPS) EARLY_EXIT_N else LATE_EXIT_N
            if (inBreak > MAX_AD_S * FPS) {
                // Stuck: the channel's logo changed. Forget it and learn again.
                isAd = false
                runLength = 0
                mask = null
                shapes = emptyList()
                samples = 0
                rate.forEach { it.fill(0f) }
            } else if (runLength >= need) {
                isAd = false
                runLength = 0
            }
        }
    }

    private fun isBlack(luma: ByteArray): Boolean {
        var sum = 0L
        var sq = 0L
        for (b in luma) {
            val v = b.toInt() and 0xFF
            sum += v
            sq += v * v
        }
        val mean = sum.toDouble() / luma.size
        val spread = kotlin.math.sqrt((sq.toDouble() / luma.size - mean * mean).coerceAtLeast(0.0))
        return mean < BLACK_MEAN && spread < BLACK_SPREAD
    }

    private fun pushRecent(gone: Boolean) {
        if (recentFilled == SLOW_N && recent[recentPos]) recentCount--
        recent[recentPos] = gone
        if (gone) recentCount++
        recentPos = (recentPos + 1) % SLOW_N
        if (recentFilled < SLOW_N) recentFilled++
    }

    private fun clearRecent() {
        recent.fill(false)
        recentCount = 0
        recentFilled = 0
        recentPos = 0
    }

    private fun learnMask() {
        var best = -1
        var bestCount = 0
        for (k in 0 until 4) {
            val c = rate[k].count { it > MASK_RATE }
            if (c > bestCount) {
                bestCount = c
                best = k
            }
        }
        if (bestCount >= MIN_MASK) {
            setMask(best, BooleanArray(BOX_W * BOX_H) { rate[best][it] > MASK_RATE })
        } else {
            mask = null
            shapes = emptyList()
        }
    }

    private fun setMask(k: Int, m: BooleanArray) {
        corner = k
        mask = m
        maskSize = m.count { it }
        shapes = splitShapes(m).ifEmpty { listOf(m) }
    }

    /**
     * Whether the logo is there (forgiving): share of logo pixels with an edge on or next to
     * them, discounted by how busy the rest of the corner is. 1 = clearly there, 0 = gone.
     */
    private fun presence(e: BooleanArray, m: BooleanArray): Float {
        var hit = 0
        var busy = 0
        for (y in 0 until BOX_H) for (x in 0 until BOX_W) {
            val i = y * BOX_W + x
            val near = e[i] || (x > 0 && e[i - 1]) || (x < BOX_W - 1 && e[i + 1]) ||
                (y > 0 && e[i - BOX_W]) || (y < BOX_H - 1 && e[i + BOX_W])
            if (!near) continue
            if (m[i]) hit++ else busy++
        }
        val hitRate = hit.toFloat() / maskSize
        val density = busy.toFloat() / (BOX_W * BOX_H - maskSize)
        return ((hitRate - density) / (1f - density + 1e-6f)).coerceAtLeast(0f)
    }

    /** Best share of any one logo shape that is back pixel-for-pixel (strict). */
    private fun exactMatch(e: BooleanArray): Float {
        var best = 0f
        for (s in shapes) {
            var hit = 0
            var size = 0
            for (i in s.indices) if (s[i]) {
                size++
                if (e[i]) hit++
            }
            if (size > 0) best = maxOf(best, hit.toFloat() / size)
        }
        return best
    }

    /** Separate shapes in the logo mask (8-connected); specks under [MIN_SHAPE] dropped. */
    private fun splitShapes(m: BooleanArray): List<BooleanArray> {
        val seen = BooleanArray(m.size)
        val out = mutableListOf<BooleanArray>()
        val stack = ArrayDeque<Int>()
        for (start in m.indices) {
            if (!m[start] || seen[start]) continue
            val shape = BooleanArray(m.size)
            var count = 0
            seen[start] = true
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                shape[i] = true
                count++
                val x = i % BOX_W
                val y = i / BOX_W
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until BOX_W || ny !in 0 until BOX_H) continue
                    val j = ny * BOX_W + nx
                    if (m[j] && !seen[j]) {
                        seen[j] = true
                        stack.addLast(j)
                    }
                }
            }
            if (count >= MIN_SHAPE) out += shape
        }
        return out
    }

    private fun boxEdges(luma: ByteArray, origin: Pair<Int, Int>, out: BooleanArray) {
        val (ox, oy) = origin
        for (y in 0 until BOX_H) for (x in 0 until BOX_W) {
            val p = (oy + y) * W + ox + x
            val gx = if (x in 1 until BOX_W - 1) abs(lum(luma, p + 1) - lum(luma, p - 1)) else 0
            val gy = if (y in 1 until BOX_H - 1) abs(lum(luma, p + W) - lum(luma, p - W)) else 0
            out[y * BOX_W + x] = gx + gy > EDGE_T
        }
    }

    private fun lum(luma: ByteArray, i: Int) = luma[i].toInt() and 0xFF

    /** The learned logo, to remember for this channel; null until one is found. */
    fun save(): String? {
        val m = mask ?: return null
        val bytes = ByteArray((m.size + 7) / 8)
        m.forEachIndexed { i, on -> if (on) bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte() }
        return "$corner:" + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Starts with a logo learned last time, so a familiar channel works straight away. */
    fun restore(saved: String) {
        runCatching {
            val k = saved.substringBefore(':').toInt()
            val bytes = Base64.decode(saved.substringAfter(':'), Base64.NO_WRAP)
            val m = BooleanArray(BOX_W * BOX_H) { i -> (bytes[i / 8].toInt() shr (i % 8)) and 1 == 1 }
            if (k in 0..3 && m.count { it } >= MIN_MASK) setMask(k, m)
        }
    }
}

/** Holds the live picture's SurfaceView so the detector can sample it. */
internal class VideoSurfaceRef {
    var view: SurfaceView? = null
}

/**
 * True while [channelKey] appears to be in a commercial break. Samples the picture only while
 * [enabled] and playing; starts over (with any logo remembered for that channel) on each change.
 */
@Composable
internal fun rememberCommercialBreak(
    enabled: Boolean,
    channelKey: String?,
    surface: VideoSurfaceRef,
    isPlaying: () -> Boolean,
): Boolean {
    val context = LocalContext.current
    var inBreak by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, channelKey) {
        inBreak = false
        if (!enabled || channelKey == null) return@LaunchedEffect
        val prefs = context.getSharedPreferences("commercial_logos", Context.MODE_PRIVATE)
        val tracker = LogoTracker()
        prefs.getString(channelKey, null)?.let(tracker::restore)
        val thread = HandlerThread("commercial-breaks").apply { start() }
        val handler = Handler(thread.looper)
        val bitmap = Bitmap.createBitmap(LogoTracker.W, LogoTracker.H, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(LogoTracker.W * LogoTracker.H)
        val luma = ByteArray(pixels.size)
        var hadLogo = false
        var lastSave = System.currentTimeMillis()
        var next = System.currentTimeMillis()
        try {
            while (true) {
                next += SAMPLE_MS
                delay((next - System.currentTimeMillis()).coerceAtLeast(0))
                val view = surface.view ?: continue
                if (!isPlaying() || !copyFrame(view, bitmap, handler)) continue
                withContext(Dispatchers.Default) {
                    bitmap.getPixels(pixels, 0, LogoTracker.W, 0, 0, LogoTracker.W, LogoTracker.H)
                    for (i in pixels.indices) {
                        val c = pixels[i]
                        luma[i] = ((77 * (c shr 16 and 0xFF) + 150 * (c shr 8 and 0xFF) + 29 * (c and 0xFF)) shr 8).toByte()
                    }
                    tracker.feed(luma)
                }
                if (tracker.isAd != inBreak) Log.i(TAG, "$channelKey: ${if (tracker.isAd) "break started" else "show is back"}")
                if (tracker.hasLogo != hadLogo) {
                    hadLogo = tracker.hasLogo
                    Log.i(TAG, "$channelKey: ${if (hadLogo) "logo learned" else "no logo"}")
                }
                inBreak = tracker.isAd
                if (System.currentTimeMillis() - lastSave > SAVE_EVERY_MS) {
                    lastSave = System.currentTimeMillis()
                    tracker.save()?.let { prefs.edit().putString(channelKey, it).apply() }
                }
            }
        } finally {
            thread.quitSafely()
        }
    }
    return inBreak
}

/** A small copy of what the SurfaceView is showing; false if there's no picture yet. */
private suspend fun copyFrame(view: SurfaceView, into: Bitmap, handler: Handler): Boolean =
    suspendCancellableCoroutine { cont ->
        try {
            PixelCopy.request(view, into, { result -> cont.resume(result == PixelCopy.SUCCESS) }, handler)
        } catch (e: IllegalArgumentException) {
            cont.resume(false) // surface not ready
        }
    }

/** The small live preview during a break: bottom-right, 16:9. */
internal val PreviewWidth = 288.dp
internal val PreviewHeight = 162.dp
internal val PreviewMargin = 56.dp
private val PreviewCorner = 12.dp

private fun androidx.compose.ui.unit.Density.previewRect(width: Float, height: Float): Rect {
    val w = PreviewWidth.toPx()
    val h = PreviewHeight.toPx()
    val m = PreviewMargin.toPx()
    return Rect(width - m - w, height - m - h, width - m, height - m)
}

/** What shows instead of the ads, with a window onto the muted live picture. */
@Composable
internal fun CommercialCover(channel: Channel, programTitle: String?, modifier: Modifier = Modifier) {
    val since = remember { System.currentTimeMillis() }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            elapsed = (System.currentTimeMillis() - since) / 1000
            delay(1_000)
        }
    }
    Box(
        modifier.fillMaxSize().drawBehind {
            // Solid everywhere except a window in the corner, where the (muted) live picture
            // keeps playing small: LivePlayerScreen shrinks the video into exactly this spot.
            val hole = previewRect(size.width, size.height)
            val corner = CornerRadius(PreviewCorner.toPx())
            drawPath(
                Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addRoundRect(RoundRect(hole, corner))
                },
                ShieldColors.Background,
            )
            drawRoundRect(
                Color.White.copy(alpha = 0.22f),
                topLeft = hole.topLeft,
                size = hole.size,
                cornerRadius = corner,
                style = Stroke(1.dp.toPx()),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(ShieldColors.Accent.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(size.width / 2, size.height * 0.42f),
                    radius = size.width * 0.45f,
                ),
            )
        }
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = PreviewMargin, bottom = PreviewMargin + PreviewHeight + 10.dp)
                .width(PreviewWidth),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(Color(0xFFE5484D), CircleShape))
            Text(
                "STILL ON  ·  ${channel.name}",
                style = MaterialTheme.typography.labelMedium,
                color = ShieldColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(width = 180.dp, height = 90.dp), contentAlignment = Alignment.Center) {
                AsyncImage(channel.logo, contentDescription = channel.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(36.dp))
            Text(
                "COMMERCIAL BREAK",
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.4.em),
                color = ShieldColors.Accent,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "Taking a short commercial break",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = ShieldColors.OnSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                programTitle?.let { "$it will be right back" } ?: "We'll be right back",
                style = MaterialTheme.typography.titleMedium,
                color = ShieldColors.OnSurfaceMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                "%d:%02d".format(elapsed / 60, elapsed % 60),
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                color = ShieldColors.OnSurfaceFaint,
            )
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "OK",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = ShieldColors.OnSurfaceMuted,
                modifier = Modifier
                    .border(1.dp, ShieldColors.Hairline, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
            Text("Watch anyway", style = MaterialTheme.typography.labelMedium, color = ShieldColors.OnSurfaceFaint)
        }
    }
}
