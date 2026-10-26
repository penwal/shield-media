package com.logan.shieldmedia.plex.ui

import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.logan.shieldmedia.plex.PlexItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val SETTLE_MS = 1_200L
private const val BAR_ZOOM = 1.25f
private const val ART_WAIT_MS = 2_500L

/**
 * Plays the focused title's trailer silently behind the showcase, filling the screen (cropped,
 * never stretched). It fades in over the static artwork once the first frame is ready and
 * fades out before switching titles, so flicking through titles never shows a black flash.
 */
@Composable
fun TrailerBackdrop(env: PlexEnv, item: PlexItem?, modifier: Modifier = Modifier, artReady: () -> Boolean = { true }) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }
    val alpha = remember { Animatable(0f) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> if (player.mediaItemCount > 0) player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(item?.ratingKey) {
        if (alpha.value > 0f) {
            // Keep the old trailer up until the new title's artwork is behind it, then fade
            // straight to that, never back to the previous title's picture.
            withTimeoutOrNull(ART_WAIT_MS) { snapshotFlow { artReady() }.first { it } }
            alpha.animateTo(0f, tween(700, easing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)))
        }
        player.stop()
        player.clearMediaItems()
        val key = item?.ratingKey ?: return@LaunchedEffect
        delay(SETTLE_MS) // only once you stop on a title
        val url = runCatching { env.trailerUrl(key) }.getOrNull() ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
        // Wait for real frames before revealing.
        while (!(player.isPlaying && player.currentPosition > 300)) delay(100)
        alpha.animateTo(1f, tween(1200))
    }

    BoxWithConstraints(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }, contentAlignment = Alignment.Center) {
        val screen = maxWidth / maxHeight
        // Fill: crop the video to cover the screen.
        val size = if (aspect > screen) Modifier.requiredSize(maxHeight * aspect, maxHeight) else Modifier.requiredSize(maxWidth, maxWidth / aspect)
        // A 16:9 frame from a widescreen film usually has black bars baked in; zoom past those.
        // Already-wide videos are cropped to fill, so they get no extra zoom (keeps them sharp).
        val zoom = if (aspect < 1.9f) BAR_ZOOM else 1f
        Box(size.graphicsLayer { scaleX = zoom; scaleY = zoom }) {
            AndroidView(
                factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
