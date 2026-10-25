package com.logan.shieldmedia.plex.ui

import android.annotation.SuppressLint
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.plex.PlexItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the narrator says (res/raw/feature_intro.*), shown line by line in time with it. */
private val NARRATION = listOf(
    "Thank you for choosing tonight's feature.",
    "Your premium cinematic experience is about to begin.",
    "Sit back, relax, and enjoy the show.",
)
private const val FALLBACK_MS = 11_000L
private val Ice = Color(0xFFD8ECFF)

/**
 * The pre-show for Blu-Ray titles: fade to black, the film's logo, and the narration with its
 * lines appearing as they're spoken. Then the movie starts. OK skips; Back cancels.
 */
@SuppressLint("DiscouragedApi")
@Composable
fun FeatureIntroScreen(env: PlexEnv, ratingKey: String, onStart: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val item by produceState<PlexItem?>(null, ratingKey) { value = runCatching { env.library.item(env.server, ratingKey) }.getOrNull() }
    var line by remember { mutableIntStateOf(-1) }
    var finished by remember { mutableStateOf(false) }
    val logoAlpha = remember { Animatable(0f) }
    val curtain = remember { Animatable(1f) } // black over everything; lifts, then falls at the end
    val glow = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    val player = remember {
        val id = context.resources.getIdentifier("feature_intro", "raw", context.packageName)
        if (id != 0) MediaPlayer.create(context, id) else null
    }
    DisposableEffect(player) { onDispose { player?.release() } }

    fun finish() {
        if (finished) return
        finished = true
        scope.launch {
            player?.let { p -> runCatching { p.setVolume(0f, 0f) } }
            curtain.animateTo(1f, tween(900, easing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)))
            onStart()
        }
    }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        delay(900) // a moment of black while the vault music fades away
        launch { curtain.animateTo(0f, tween(1200)) }
        launch { glow.animateTo(1f, tween(2400, easing = LinearEasing)) }
        launch { logoAlpha.animateTo(1f, tween(1600, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) }
        delay(700)

        val total = player?.duration?.toLong()?.takeIf { it > 0 } ?: FALLBACK_MS
        player?.start()
        val started = System.currentTimeMillis()
        // Line timing follows the length of each sentence (the narrator's pace is steady).
        val weights = NARRATION.map { it.length.toFloat() }
        var acc = 0f
        val starts = weights.map { w -> (acc / weights.sum() * total).toLong().also { acc += w } }
        starts.forEachIndexed { i, at ->
            delay((at - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            if (finished) return@LaunchedEffect
            line = i
        }
        delay((total - (System.currentTimeMillis() - started)).coerceAtLeast(0) + 1200)
        finish()
    }

    BackHandler(enabled = !finished) {
        finished = true
        player?.stop()
        onCancel()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && (it.key == Key.DirectionCenter || it.key == Key.Enter)) {
                    finish()
                    true
                } else {
                    false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        // A faint projector glow from above, like light falling on a screen in a dark theater.
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = glow.value }) {
            drawRect(
                Brush.radialGradient(
                    listOf(Ice.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(size.width / 2, size.height * 0.38f),
                    radius = size.width * 0.5f,
                ),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "TONIGHT'S FEATURE",
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.45.em),
                color = ShieldColors.Accent,
                modifier = Modifier.graphicsLayer { alpha = logoAlpha.value },
            )
            Spacer(Modifier.height(28.dp))
            Box(Modifier.height(170.dp).graphicsLayer { alpha = logoAlpha.value }, contentAlignment = Alignment.Center) {
                val current = item
                val logo = current?.let { env.logo(it) }
                if (logo != null) {
                    AsyncImage(logo, contentDescription = current.title, contentScale = ContentScale.Fit, modifier = Modifier.heightIn(max = 170.dp).widthIn(max = 720.dp))
                } else if (current != null) {
                    Text(current.title, style = MaterialTheme.typography.displayMedium, color = Color.White, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(22.dp))
            Box(Modifier.width(240.dp).height(1.dp).graphicsLayer { alpha = logoAlpha.value * 0.6f }.background(Brush.horizontalGradient(listOf(Color.Transparent, Ice, Color.Transparent))))
            Spacer(Modifier.height(48.dp))
            AnimatedContent(
                targetState = line,
                transitionSpec = { fadeIn(tween(900, delayMillis = 150)) togetherWith fadeOut(tween(500)) },
                label = "line",
            ) { i ->
                Text(
                    NARRATION.getOrElse(i) { "" },
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 0.04.em,
                    color = Color.White.copy(alpha = 0.92f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 900.dp).height(40.dp),
                )
            }
        }

        // Curtain: full black that lifts at the start and falls before the film.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = curtain.value }.background(Color.Black))
    }
}

