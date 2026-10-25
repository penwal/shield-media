package com.logan.shieldmedia.iptv.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.PrimaryButton
import com.logan.shieldmedia.designsystem.SecondaryButton
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.iptv.IptvRepository
import com.logan.shieldmedia.iptv.Reminder
import kotlinx.coroutines.delay
import java.util.Date

private const val CHECK_MS = 15_000L
private const val LEAD_MS = 60_000L
private const val SHOW_MS = 25_000L

/**
 * Wraps the whole app: when a programme with a reminder is about to start, a card slides up
 * offering to switch to it. Focus moves to the card and goes back where it was afterwards.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ReminderHost(repo: IptvRepository, onWatch: (Reminder) -> Unit, content: @Composable () -> Unit) {
    val reminders by repo.reminders.collectAsStateWithLifecycle()
    var showing by remember { mutableStateOf<Reminder?>(null) }
    val contentFocus = remember { FocusRequester() }
    val cardFocus = remember { FocusRequester() }
    var cardFocused by remember { mutableStateOf(false) }

    LaunchedEffect(reminders) {
        while (true) {
            val now = System.currentTimeMillis()
            repo.pruneReminders(now)
            if (showing == null) {
                showing = repo.reminders.value.firstOrNull { it.start - now in -LEAD_MS..LEAD_MS }
            }
            delay(CHECK_MS)
        }
    }

    fun close(reminder: Reminder) {
        repo.dismissReminder(reminder)
        showing = null
        runCatching { contentFocus.restoreFocusedChild() }
    }

    val current = showing
    BackHandler(enabled = current != null) { current?.let(::close) }
    LaunchedEffect(current) {
        if (current == null) return@LaunchedEffect
        runCatching { contentFocus.saveFocusedChild() }
        // The card is modal while it shows: screens that finish loading underneath may try to take
        // focus, so it's reclaimed until the viewer chooses (or the card times out).
        val until = System.currentTimeMillis() + SHOW_MS
        while (showing == current && System.currentTimeMillis() < until) {
            withFrameNanos { }
            if (!cardFocused) runCatching { cardFocus.requestFocus() }
            delay(250)
        }
        if (showing == current) close(current)
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().focusRequester(contentFocus).focusGroup()) { content() }
        AnimatedVisibility(
            visible = current != null,
            enter = slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(180)) { it / 2 } + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 48.dp),
        ) {
            val r = current ?: return@AnimatedVisibility
            val context = LocalContext.current
            val time = remember(r) { DateFormat.getTimeFormat(context).format(Date(r.start)) }
            Column(
                Modifier
                    .width(520.dp)
                    .background(ShieldColors.SurfaceHigh, RoundedCornerShape(18.dp))
                    .padding(24.dp)
                    .onFocusChanged { cardFocused = it.hasFocus }
                    .focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (r.start > System.currentTimeMillis()) "STARTING AT $time" else "STARTED AT $time",
                    style = MaterialTheme.typography.labelSmall,
                    color = ShieldColors.Accent,
                )
                Text(r.title, style = MaterialTheme.typography.titleLarge, color = ShieldColors.OnSurface, maxLines = 2)
                Text(r.channelName, style = MaterialTheme.typography.bodyMedium, color = ShieldColors.OnSurfaceMuted)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton(
                        "Watch now",
                        onClick = {
                            close(r)
                            onWatch(r)
                        },
                        modifier = Modifier.focusRequester(cardFocus),
                    )
                    SecondaryButton("Dismiss", onClick = { close(r) })
                }
            }
        }
    }
}
