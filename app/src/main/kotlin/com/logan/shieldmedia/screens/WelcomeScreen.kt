package com.logan.shieldmedia.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.logan.shieldmedia.AppContainer
import com.logan.shieldmedia.designsystem.Motion
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.iptv.SyncState
import com.logan.shieldmedia.plex.PlexState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

private const val MIN_MS = 2_500L // long enough to read, never a flash
private const val MAX_MS = 15_000L // then the app opens on Plex's "couldn't connect" screen
private const val SETTLE_MS = 700L // after connecting, a beat for Home's rows to arrive behind
private const val SLOW_MS = 6_000L // the status admits it's taking a while

/**
 * Shown once when the app starts, over the app as it loads underneath: the brand, a greeting
 * for whoever's profile is signed in, and what's being loaded. Calls [onDone] once Plex has
 * connected (or failed) and Home has had a moment, but never before [MIN_MS]. If Plex is still
 * connecting after [MAX_MS], it gives up so the app opens on the "couldn't connect" message.
 */
@Composable
fun WelcomeScreen(container: AppContainer, onDone: () -> Unit) {
    val plexState by container.plexSession.state.collectAsState()
    val sync by container.iptv.syncState.collectAsState()
    var name by remember { mutableStateOf<String?>(null) }
    var slow by remember { mutableStateOf(false) }
    val enter = remember { Animatable(0f) }

    LaunchedEffect(Unit) { enter.animateTo(1f, tween(900, easing = Motion.EaseOut)) }
    LaunchedEffect(plexState) {
        if (plexState is PlexState.Ready && name == null) {
            val session = container.plexSession
            name = runCatching { session.currentProfileName() ?: session.user()?.let { it.title ?: it.username } }
                .getOrNull()?.substringBefore(' ')
        }
    }
    LaunchedEffect(Unit) {
        delay(SLOW_MS)
        slow = true
    }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        val connected = withTimeoutOrNull(MAX_MS) {
            container.plexSession.state.first { it !is PlexState.Loading }
        }
        if (connected == null) container.plexSession.giveUpWaiting() else delay(SETTLE_MS)
        delay((MIN_MS - (System.currentTimeMillis() - start)).coerceAtLeast(0))
        onDone()
    }

    val status = when {
        plexState is PlexState.Loading && slow -> "Still trying to reach your Plex server"
        plexState is PlexState.Loading -> "Connecting to your Plex server"
        sync is SyncState.Running -> "Updating the TV guide"
        plexState is PlexState.Ready -> "Getting your library ready"
        else -> "Starting up"
    }
    val greeting = name?.let { "${timeOfDayGreeting()}, $it" } ?: "Welcome"

    Box(Modifier.fillMaxSize().background(ShieldColors.Background), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = enter.value }) {
            drawRect(
                Brush.radialGradient(
                    listOf(ShieldColors.Accent.copy(alpha = 0.08f), Color.Transparent),
                    center = Offset(size.width / 2, size.height * 0.45f),
                    radius = size.width * 0.5f,
                ),
            )
        }
        Column(
            Modifier.graphicsLayer {
                alpha = enter.value
                val s = 0.96f + 0.04f * enter.value
                scaleX = s
                scaleY = s
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Canvas(Modifier.size(width = 30.dp, height = 33.dp)) {
                    drawPath(
                        Path().apply {
                            moveTo(size.width * 0.12f, size.height * 0.08f)
                            lineTo(size.width * 0.12f, size.height * 0.92f)
                            lineTo(size.width * 0.98f, size.height * 0.5f)
                            close()
                        },
                        ShieldColors.Accent,
                    )
                }
                Text("Shield Media", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = ShieldColors.OnSurface)
            }
            Spacer(Modifier.height(44.dp))
            AnimatedContent(
                targetState = greeting,
                transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(300)) },
                label = "greeting",
            ) { text ->
                Text(text, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, color = ShieldColors.OnSurface)
            }
            Spacer(Modifier.height(14.dp))
            AnimatedContent(
                targetState = status,
                transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(250)) },
                label = "status",
            ) { text ->
                Text(
                    text.uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.18.em),
                    color = ShieldColors.OnSurfaceMuted,
                )
            }
            Spacer(Modifier.height(30.dp))
            LoadingRail()
        }
    }
}

/** A thin rail with a soft light running along it while things load. */
@Composable
private fun LoadingRail() {
    val sweep by rememberInfiniteTransition(label = "rail").animateFloat(
        initialValue = -0.35f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    Canvas(Modifier.width(220.dp).height(2.dp)) {
        drawRect(Color.White.copy(alpha = 0.10f))
        val x = size.width * sweep
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, ShieldColors.Accent, Color.Transparent),
                startX = x - 60.dp.toPx(),
                endX = x + 60.dp.toPx(),
            ),
        )
    }
}

private fun timeOfDayGreeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}
