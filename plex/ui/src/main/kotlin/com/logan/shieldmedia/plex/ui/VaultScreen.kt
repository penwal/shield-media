package com.logan.shieldmedia.plex.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.plex.PlexItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private val Ice = Color(0xFFD8ECFF)
private val IceDeep = Color(0xFF5FA8FF)

private const val PHASE_INTRO = 0
private const val PHASE_COLLECTION = 1
private const val EXIT_FADE_MS = 2000

private val STATUS = listOf(
    "MOUNTING ARCHIVE",
    "READING DISC INDEX",
    "VERIFYING 4K MASTERS",
    "CALIBRATING HDR",
    "ENGAGING DOLBY ATMOS",
    "READY",
)

/**
 * The Blu-Ray collection as its own full-screen experience: a cinematic boot sequence (while the
 * library really loads), then the showcase. Back fades out to the main app.
 */
@Composable
fun PlexVaultScreen(
    env: PlexEnv,
    sectionKey: String,
    title: String,
    onOpen: (PlexItem) -> Unit,
    onExit: () -> Unit,
) {
    // Same key as the showcase, so the intro and the collection share one (pre-loading) model.
    val vm = viewModel(key = "showcase-$sectionKey-${env.key}") { PlexShowcaseViewModel(env, sectionKey) }
    // Saveable: coming back from a title's page goes straight to the collection, no replay.
    var phase by rememberSaveable { mutableStateOf(PHASE_INTRO) }
    var leaving by remember { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }
    val blackout = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val collectionFocus = remember { FocusRequester() }
    if (!leaving) VaultMusicEffect()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (phase == PHASE_COLLECTION) {
            Box(Modifier.fillMaxSize().focusRequester(collectionFocus).focusGroup()) {
                PlexShowcaseScreen(env, sectionKey, title, onOpen)
            }
        } else {
            VaultIntro(
                title = title,
                ready = { vm.items != null || vm.error != null },
                onFinished = {
                    scope.launch {
                        flash.animateTo(1f, tween(130))
                        phase = PHASE_COLLECTION
                        flash.animateTo(0f, tween(900, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
                    }
                },
            )
        }

        if (flash.value > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = flash.value }.background(Ice))
        if (blackout.value > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = blackout.value }.background(Color.Black))
    }

    BackHandler(enabled = phase == PHASE_INTRO && !leaving) { onExit() }
    BackHandler(enabled = phase == PHASE_COLLECTION && !leaving) {
        leaving = true
        // Picture and music fade out together, slowly, before returning to the app.
        VaultMusic.fadeOut(EXIT_FADE_MS.toLong())
        scope.launch {
            blackout.animateTo(1f, tween(EXIT_FADE_MS, easing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)))
            delay(250) // a beat of black and silence
            onExit()
        }
    }
}

@Composable
private fun VaultIntro(title: String, ready: () -> Boolean, onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    var skip by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        progress.animateTo(0.9f, tween(2600, easing = CubicBezierEasing(0.35f, 0f, 0.25f, 1f)))
        snapshotFlow { ready() }.first { it } // hold at 90% until the library has really loaded
        progress.animateTo(1f, tween(380, easing = LinearEasing))
        onFinished()
    }
    LaunchedEffect(skip) {
        if (skip) {
            snapshotFlow { ready() }.first { it }
            onFinished()
        }
    }

    val spin by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "spin",
    )
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    val p = progress.value

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent {
                // OK skips the sequence (it still waits for the library to arrive).
                if (it.type == KeyEventType.KeyUp && (it.key == Key.DirectionCenter || it.key == Key.Enter)) {
                    skip = true
                    true
                } else {
                    false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        // Faint perspective grid and a cold glow that brightens as the disc spins up.
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height * 0.42f
            drawCircle(
                Brush.radialGradient(
                    listOf(IceDeep.copy(alpha = 0.10f + 0.18f * p), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = size.minDimension * (0.45f + 0.25f * p),
                ),
                radius = size.maxDimension,
                center = Offset(cx, cy),
            )
            val horizon = size.height * 0.74f
            for (i in -14..14) {
                val x = cx + i * size.width / 18f
                drawLine(Ice.copy(alpha = 0.05f), Offset(cx + i * 6f, horizon), Offset(x, size.height), strokeWidth = 1f)
            }
            var y = horizon
            var gap = 6f
            while (y < size.height) {
                drawLine(Ice.copy(alpha = 0.05f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += gap
                gap *= 1.35f
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Disc(spin = spin * (0.4f + 1.6f * p), progress = p, pulse = pulse)
            Spacer(Modifier.height(44.dp))
            Text(
                title.uppercase(),
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 0.6.em,
                color = Color.White.copy(alpha = (p * 3f).coerceIn(0f, 1f)),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "REFERENCE COLLECTION",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.5.em),
                color = ShieldColors.Accent.copy(alpha = ((p - 0.15f) * 3f).coerceIn(0f, 1f)),
            )
            Spacer(Modifier.height(40.dp))
            ProgressRail(p)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.width(460.dp), verticalAlignment = Alignment.CenterVertically) {
                val status = STATUS[((p * (STATUS.size - 1)) + 0.0001f).toInt().coerceIn(0, STATUS.size - 1)]
                Text(
                    "▸ $status",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    letterSpacing = 0.12.em,
                    color = Ice.copy(alpha = if (p < 1f) pulse else 1f),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "%03d%%".format((p * 100).toInt()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = Ice,
                )
            }
        }
    }
}

/** A disc spinning up: concentric data tracks, a lit sweep, and a ring of ticks that fills with progress. */
@Composable
private fun Disc(spin: Float, progress: Float, pulse: Float) {
    Canvas(Modifier.size(230.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        // Progress ticks around the outside.
        val ticks = 72
        for (i in 0 until ticks) {
            val a = Math.toRadians((i * 360.0 / ticks) - 90)
            val lit = i < (progress * ticks).toInt()
            val inner = r * 0.9f
            drawLine(
                if (lit) Ice.copy(alpha = 0.9f) else Ice.copy(alpha = 0.12f),
                Offset(c.x + cos(a).toFloat() * inner, c.y + sin(a).toFloat() * inner),
                Offset(c.x + cos(a).toFloat() * r, c.y + sin(a).toFloat() * r),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        val disc = r * 0.78f
        drawCircle(Color(0xFF0B0F16), radius = disc, center = c)
        // Data tracks.
        var t = disc * 0.42f
        while (t < disc) {
            drawCircle(Ice.copy(alpha = 0.06f), radius = t, center = c, style = Stroke(1f))
            t += 3.dp.toPx()
        }
        // Spinning light sweep (the "laser" catching the surface).
        rotate(spin, c) {
            drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, Ice.copy(alpha = 0.55f * pulse), Color.Transparent), center = c),
                startAngle = 0f,
                sweepAngle = 70f,
                useCenter = true,
                topLeft = Offset(c.x - disc, c.y - disc),
                size = Size(disc * 2, disc * 2),
            )
            drawArc(
                IceDeep.copy(alpha = 0.35f),
                startAngle = 180f,
                sweepAngle = 40f,
                useCenter = false,
                topLeft = Offset(c.x - disc * 0.7f, c.y - disc * 0.7f),
                size = Size(disc * 1.4f, disc * 1.4f),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        drawCircle(Ice.copy(alpha = 0.35f), radius = disc, center = c, style = Stroke(1.5.dp.toPx()))
        // Hub.
        drawCircle(Color.Black, radius = disc * 0.2f, center = c)
        drawCircle(Ice.copy(alpha = 0.6f), radius = disc * 0.2f, center = c, style = Stroke(1.5.dp.toPx()))
        drawCircle(Ice.copy(alpha = 0.25f + 0.5f * progress), radius = disc * 0.07f, center = c)
    }
}

@Composable
private fun ProgressRail(p: Float) {
    Canvas(Modifier.width(460.dp).height(10.dp)) {
        val y = size.height / 2
        drawLine(Ice.copy(alpha = 0.14f), Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        val x = size.width * p
        drawLine(
            Brush.horizontalGradient(listOf(IceDeep.copy(alpha = 0.5f), Ice), endX = x.coerceAtLeast(1f)),
            Offset(0f, y),
            Offset(x, y),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        // Glowing head.
        drawCircle(Brush.radialGradient(listOf(Ice, Color.Transparent), center = Offset(x, y), radius = 14.dp.toPx()), radius = 14.dp.toPx(), center = Offset(x, y))
        drawCircle(Color.White, radius = 2.5.dp.toPx(), center = Offset(x, y))
    }
}
