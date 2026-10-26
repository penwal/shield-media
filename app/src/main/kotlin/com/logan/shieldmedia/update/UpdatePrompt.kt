package com.logan.shieldmedia.update

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.PrimaryButton
import com.logan.shieldmedia.designsystem.SecondaryButton
import com.logan.shieldmedia.designsystem.ShieldColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

private sealed interface Phase {
    data object Offer : Phase
    data class Downloading(val progress: Float) : Phase
    data class Ready(val file: File) : Phase
    data object Failed : Phase
}

/**
 * Offers a newer version from GitHub: Install update downloads it (with progress) and opens
 * Android's installer; Not now (or Back) closes it until the next launch.
 */
@Composable
fun UpdatePrompt(updater: AppUpdater, release: AppRelease, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<Phase>(Phase.Offer) }
    var job by remember { mutableStateOf<Job?>(null) }
    val primary = remember { FocusRequester() }

    fun start() {
        phase = Phase.Downloading(0f)
        job = scope.launch {
            phase = try {
                val file = updater.download(release) { p -> phase = Phase.Downloading(p) }
                runCatching { updater.install(file) }
                Phase.Ready(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Phase.Failed
            }
        }
    }

    fun dismiss() {
        job?.cancel()
        onDismiss()
    }

    // Each step puts focus on its main button, so OK always does the obvious thing.
    LaunchedEffect(phase::class) {
        withFrameNanos { }
        runCatching { primary.requestFocus() }
    }

    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(620.dp)
                .background(ShieldColors.SurfaceHigh, RoundedCornerShape(24.dp))
                .padding(horizontal = 40.dp, vertical = 36.dp),
        ) {
            Text(
                "UPDATE AVAILABLE",
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.18.em),
                color = ShieldColors.Accent,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Shield Media ${release.version}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = ShieldColors.OnSurface,
            )
            Spacer(Modifier.height(4.dp))
            val size = if (release.size > 0) " · ${Formatter.formatShortFileSize(context, release.size)}" else ""
            Text(
                "You have version ${updater.installedVersion}$size",
                style = MaterialTheme.typography.bodyMedium,
                color = ShieldColors.OnSurfaceMuted,
            )
            if (release.notes.isNotBlank()) {
                Spacer(Modifier.height(18.dp))
                Text(
                    release.notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShieldColors.OnSurface.copy(alpha = 0.85f),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(26.dp))

            when (val p = phase) {
                Phase.Offer -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton("Install update", onClick = ::start, modifier = Modifier.focusRequester(primary))
                    SecondaryButton("Not now", onClick = ::dismiss)
                }
                is Phase.Downloading -> {
                    ProgressRail(p.progress)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Downloading… ${(p.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ShieldColors.OnSurfaceMuted,
                    )
                    Spacer(Modifier.height(20.dp))
                    SecondaryButton("Cancel", onClick = ::dismiss, modifier = Modifier.focusRequester(primary))
                }
                is Phase.Ready -> {
                    Text(
                        "Confirm the install on the next screen. The app restarts when it's done.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ShieldColors.OnSurfaceMuted,
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryButton(
                            "Install",
                            onClick = { runCatching { updater.install(p.file) } },
                            modifier = Modifier.focusRequester(primary),
                        )
                        SecondaryButton("Not now", onClick = ::dismiss)
                    }
                }
                Phase.Failed -> {
                    Text(
                        "Couldn't download the update. Check the internet connection and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ShieldColors.OnSurfaceMuted,
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryButton("Try again", onClick = ::start, modifier = Modifier.focusRequester(primary))
                        SecondaryButton("Not now", onClick = ::dismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressRail(progress: Float) {
    // A spring keeps its speed when the target moves again, so the bar keeps up with the download.
    val shown by animateFloatAsState(progress, spring(stiffness = Spring.StiffnessMediumLow), label = "progress")
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.10f)),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(ShieldColors.Accent))
    }
}
