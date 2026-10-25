package com.logan.shieldmedia.plex.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Background music for the Blu-Ray vault, from `app/src/main/res/raw/vault_theme.*`.
 * Screens inside the vault hold it with [VaultMusicEffect]; it fades out once none do (leaving
 * the vault or starting a movie), and pauses while the app is in the background.
 * Does nothing if the file isn't there.
 */
object VaultMusic {
    private const val RESOURCE = "vault_theme"
    private const val VOLUME = 0.40f
    private const val FADE_STEPS = 40

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var holders = 0
    private var fadeJob: Job? = null
    private var volume = 0f

    @SuppressLint("DiscouragedApi")
    fun acquire(context: Context) {
        holders++
        fadeJob?.cancel()
        val p = player ?: run {
            val app = context.applicationContext
            val id = app.resources.getIdentifier(RESOURCE, "raw", app.packageName)
            if (id == 0) return
            MediaPlayer.create(app, id)?.apply { isLooping = true }?.also { player = it }
        } ?: return
        if (!p.isPlaying) p.start()
        fadeTo(VOLUME, durationMs = 1500)
    }

    /** Released after a short grace period, so moving between vault screens doesn't cut the music. */
    fun release() {
        holders = (holders - 1).coerceAtLeast(0)
        fadeJob?.cancel()
        fadeJob = scope.launch {
            delay(400)
            if (holders > 0) return@launch
            fadeTo(0f, durationMs = 1500, then = ::stop)
        }
    }

    /** Fades to silence now (e.g. while the screen fades out); the player stops once released. */
    fun fadeOut(durationMs: Long) {
        fadeTo(0f, durationMs)
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
    }

    fun resume() {
        if (holders > 0) player?.takeIf { !it.isPlaying }?.start()
    }

    private fun fadeTo(target: Float, durationMs: Long, then: (() -> Unit)? = null) {
        fadeJob?.cancel()
        val start = volume
        fadeJob = scope.launch {
            for (i in 1..FADE_STEPS) {
                volume = start + (target - start) * i / FADE_STEPS
                player?.setVolume(volume, volume)
                delay(durationMs / FADE_STEPS)
            }
            then?.invoke()
        }
    }

    private fun stop() {
        player?.release()
        player = null
        volume = 0f
    }
}

/** Keeps the vault music playing while this screen is shown. */
@Composable
fun VaultMusicEffect() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        VaultMusic.acquire(context)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> VaultMusic.pause()
                Lifecycle.Event.ON_START -> VaultMusic.resume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            VaultMusic.release()
        }
    }
}
