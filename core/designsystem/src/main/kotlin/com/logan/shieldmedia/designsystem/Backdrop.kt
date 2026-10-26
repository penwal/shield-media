package com.logan.shieldmedia.designsystem

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.delay

/**
 * Full-bleed artwork behind a screen. The image only changes after focus has settled, and only
 * once the new image has finished loading, so it crossfades from picture to picture instead of
 * flashing through an empty frame.
 */
@Composable
fun Backdrop(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    /** How long focus must rest before the picture changes; 0 changes as soon as it's loaded. */
    settleMs: Long = Motion.BACKDROP_SETTLE_MS,
) {
    var settledUrl by remember { mutableStateOf(imageUrl) }
    LaunchedEffect(imageUrl) {
        if (settledUrl != null) delay(settleMs)
        settledUrl = imageUrl
    }

    var shownUrl by remember { mutableStateOf<String?>(null) }
    val painter = rememberAsyncImagePainter(settledUrl)
    val state by painter.state.collectAsState()
    LaunchedEffect(state, settledUrl) {
        if (state is AsyncImagePainter.State.Success || settledUrl == null) shownUrl = settledUrl
    }

    Box(modifier.fillMaxSize()) {
        Crossfade(
            targetState = shownUrl,
            animationSpec = tween(Motion.BACKDROP_MS, easing = Motion.EaseInOut),
            label = "backdrop",
        ) { url ->
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(Modifier.fillMaxSize().backdropScrim())
    }
}
