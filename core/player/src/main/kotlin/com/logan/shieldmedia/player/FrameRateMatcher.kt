package com.logan.shieldmedia.player

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Switches the HDMI refresh rate to match the video (23.976 → 23.976 Hz, 25 → 50 Hz, ...)
 * so motion is judder-free. Resolution is left alone; the Shield's upscaler handles that.
 */
class FrameRateMatcher(private val activity: Activity) {

    private var originalModeId: Int? = null

    private val display: Display?
        get() = if (Build.VERSION.SDK_INT >= 30) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }

    /** Lowest refresh rate at the current resolution that is an exact multiple of [fps]. */
    private fun bestModeFor(fps: Float): Display.Mode? {
        val d = display ?: return null
        val current = d.mode
        return d.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .mapNotNull { mode ->
                val multiple = (mode.refreshRate / fps).roundToInt()
                // 0.01 Hz per multiple keeps 23.976 and 24 (or 59.94 and 60) distinct.
                if (multiple >= 1 && abs(mode.refreshRate - fps * multiple) < 0.01f * multiple) {
                    mode to multiple
                } else {
                    null
                }
            }
            .minByOrNull { it.second }
            ?.first
    }

    fun needsSwitch(fps: Float): Boolean {
        val target = bestModeFor(fps) ?: return false
        return target.modeId != display?.mode?.modeId
    }

    /** Requests the mode switch and suspends until the display reports it (TVs blank while resyncing). */
    suspend fun switchAndAwait(fps: Float) {
        val target = bestModeFor(fps) ?: return
        val displayManager = activity.getSystemService(DisplayManager::class.java)
        val switched = CompletableDeferred<Unit>()
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (display?.mode?.modeId == target.modeId) switched.complete(Unit)
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        try {
            applyMode(target.modeId)
            withTimeoutOrNull(4_000) { switched.await() }
        } finally {
            displayManager.unregisterDisplayListener(listener)
        }
        delay(800) // let the TV finish its HDMI resync before audio/video start
    }

    fun restore() {
        val id = originalModeId ?: return
        applyMode(id)
        originalModeId = null
    }

    fun currentModeLabel(): String = display?.mode?.let {
        "${it.physicalWidth}x${it.physicalHeight} @ ${"%.3f".format(it.refreshRate)} Hz"
    } ?: "-"

    private fun applyMode(modeId: Int) {
        val window = activity.window
        if (originalModeId == null) originalModeId = window.attributes.preferredDisplayModeId
        window.attributes = window.attributes.apply { preferredDisplayModeId = modeId }
    }
}
