package com.logan.shieldmedia.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Whether a screen may move focus into itself right now. False while the user is in the
 * navigation drawer, so content that finishes loading doesn't yank focus away from them.
 */
val LocalContentFocusGate = staticCompositionLocalOf<() -> Boolean> { { true } }

/**
 * Remembers which item had focus on a screen so that coming back (from details, the player,
 * or the drawer) lands on the same card. Keep one per screen in its ViewModel so it survives
 * navigation. Deliberately not snapshot state: focus moves must not recompose every card.
 */
class FocusMemory {
    var key: Any? = null
        private set
    internal val requester = FocusRequester()

    internal fun remember(key: Any) {
        this.key = key
    }

    /** Put focus back on the remembered item (e.g. after a dialog closes). */
    fun restore(): Boolean = runCatching { requester.requestFocus() }.getOrDefault(false)

    /** Start over at the default item (e.g. after the list was re-sorted). */
    fun forget() {
        key = null
    }
}

/**
 * Tags a focusable item. [isDefault] marks the item that gets focus when nothing is remembered
 * yet (usually the first item on the screen).
 */
fun Modifier.focusMemory(memory: FocusMemory, key: Any, isDefault: Boolean = false): Modifier {
    val target = memory.key == key || (memory.key == null && isDefault)
    return this
        .then(if (target) Modifier.focusRequester(memory.requester) else Modifier)
        .onFocusChanged { if (it.isFocused) memory.remember(key) }
}

/** Moves focus to the remembered (or default) item once [ready] becomes true. */
@Composable
fun RestoreFocus(memory: FocusMemory, ready: Boolean) {
    val gate = LocalContentFocusGate.current
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        withFrameNanos { } // the target item has to be laid out first
        if (gate()) runCatching { memory.requester.requestFocus() }
    }
}
