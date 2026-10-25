package com.logan.shieldmedia.designsystem

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Four-digit PIN: number keys on the remote, or the on-screen pad. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PinPad(title: String, error: String?, busy: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember(title) { mutableStateOf("") }
    val firstKey = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    // Whatever was on screen before may still hold focus for a moment (exit animations); retry.
    LaunchedEffect(Unit) {
        repeat(12) {
            withFrameNanos { }
            if (hasFocus) return@LaunchedEffect
            runCatching { firstKey.requestFocus() }
            delay(80)
        }
    }
    LaunchedEffect(error) { if (error != null) pin = "" }
    fun type(d: String) {
        if (busy || pin.length >= 4) return
        pin += d
        if (pin.length == 4) onSubmit(pin)
    }
    BackHandler(onBack = onCancel)

    Column(
        Modifier.onFocusChanged { hasFocus = it.hasFocus }.onPreviewKeyEvent { e ->
            val digit = e.nativeKeyEvent.number.takeIf { it.isDigit() }
            if (e.type == KeyEventType.KeyDown && digit != null) {
                type(digit.toString()); true
            } else if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace) {
                pin = pin.dropLast(1); true
            } else {
                false
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = ShieldColors.OnSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier
                        .size(18.dp)
                        .then(
                            if (i < pin.length) Modifier.background(ShieldColors.OnSurface, CircleShape)
                            else Modifier.border(2.dp, ShieldColors.OnSurfaceFaint, CircleShape),
                        ),
                )
            }
        }
        Text(error ?: if (busy) "Checking…" else " ", color = if (error != null) ShieldColors.Accent else ShieldColors.OnSurfaceMuted)
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", ""))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEachIndexed { r, keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    keys.forEachIndexed { c, label ->
                        if (label.isEmpty()) {
                            Box(Modifier.size(84.dp, 64.dp))
                        } else {
                            Card(
                                onClick = { if (label == "⌫") pin = pin.dropLast(1) else type(label) },
                                modifier = Modifier.size(84.dp, 64.dp).then(if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier),
                                shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
                                scale = CardDefaults.scale(focusedScale = 1.06f),
                                colors = CardDefaults.colors(
                                    containerColor = ShieldColors.SurfaceHigh,
                                    focusedContainerColor = ShieldColors.OnSurface,
                                    contentColor = ShieldColors.OnSurface,
                                    focusedContentColor = ShieldColors.Background,
                                ),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(label, style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
