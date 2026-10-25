package com.logan.shieldmedia.plex.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.designsystem.Spacing
import com.logan.shieldmedia.plex.PlexPin
import com.logan.shieldmedia.plex.PlexSession
import kotlinx.coroutines.delay

private const val PIN_POLL_MS = 2_000L
private const val PIN_REFRESH_MS = 10 * 60_000L

/** "Go to plex.tv/link and enter this code" — no typing passwords with a remote. */
@Composable
fun PlexSignInScreen(session: PlexSession, onDone: () -> Unit, onBack: () -> Unit) {
    var pin by remember { mutableStateOf<PlexPin?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)
    val screenFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { screenFocus.requestFocus() } }

    LaunchedEffect(Unit) {
        while (true) {
            val fresh = runCatching { session.createPin() }
                .onFailure {
                    android.util.Log.w("PlexSignIn", "Creating a PIN failed", it)
                    error = "Couldn't reach plex.tv. Check the internet connection."
                }
                .getOrNull()
            if (fresh == null) {
                delay(5_000)
                continue
            }
            pin = fresh
            error = null
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < PIN_REFRESH_MS) {
                delay(PIN_POLL_MS)
                if (runCatching { session.checkPin(fresh) }.getOrDefault(false)) {
                    onDone()
                    return@LaunchedEffect
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(ShieldColors.SurfaceBright.copy(alpha = 0.55f), ShieldColors.Background),
                    radius = 1400f,
                ),
            )
            // The screen has no buttons; it still needs focus to receive Back.
            .focusRequester(screenFocus)
            .focusable(),
    ) {
        Column(
            Modifier.align(Alignment.CenterStart).padding(horizontal = Spacing.ScreenH * 2),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Text("Sign in to Plex", style = MaterialTheme.typography.displaySmall, color = ShieldColors.OnSurface)
            Step(1, "On your phone or computer, go to", "plex.tv/link")
            Step(2, "Enter this code", null)
            CodeTiles(pin?.code)
            WaitingLine(error ?: if (pin == null) "Getting a code…" else "Waiting for you to sign in…", isError = error != null)
        }
    }
}

@Composable
private fun Step(number: Int, text: String, emphasis: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.size(32.dp).border(1.5.dp, ShieldColors.Hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.titleSmall, color = ShieldColors.OnSurfaceMuted)
        }
        Text(text, style = MaterialTheme.typography.titleLarge, color = ShieldColors.OnSurfaceMuted)
        if (emphasis != null) {
            Text(emphasis, style = MaterialTheme.typography.titleLarge, color = ShieldColors.Accent)
        }
    }
}

@Composable
private fun CodeTiles(code: String?) {
    Row(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        (code ?: "    ").padEnd(4).take(8).forEach { ch ->
            Box(
                Modifier
                    .size(width = 76.dp, height = 96.dp)
                    .background(ShieldColors.SurfaceHigh, RoundedCornerShape(12.dp))
                    .border(1.dp, ShieldColors.Hairline, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    ch.toString(),
                    fontSize = 52.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ShieldColors.OnSurface,
                )
            }
        }
    }
}

@Composable
private fun WaitingLine(text: String, isError: Boolean) {
    val pulse by rememberInfiniteTransition(label = "waiting").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "dot",
    )
    Row(
        Modifier.padding(start = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .graphicsLayer { alpha = if (isError) 1f else pulse }
                .background(if (isError) ShieldColors.Accent else ShieldColors.OnSurfaceMuted, CircleShape),
        )
        Text(text, style = MaterialTheme.typography.bodyLarge, color = ShieldColors.OnSurfaceMuted)
    }
}
