package com.logan.shieldmedia.plex.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.logan.shieldmedia.designsystem.Backdrop
import com.logan.shieldmedia.designsystem.MessageState
import com.logan.shieldmedia.designsystem.Motion
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.designsystem.Spacing
import com.logan.shieldmedia.plex.PlexItem
import kotlinx.coroutines.launch

/** Libraries that hold reference-quality copies get the showcase treatment. */
fun isPremiumLibrary(title: String): Boolean {
    val t = title.lowercase()
    return listOf("blu-ray", "bluray", "blu ray", "4k", "uhd", "remux").any { it in t }
}

private val Ice = Color(0xFFD8ECFF)

class PlexShowcaseViewModel(private val env: PlexEnv, private val sectionKey: String) : ViewModel() {
    var items by mutableStateOf<List<PlexItem>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var index by mutableIntStateOf(0)
        private set
    /** +1 when moving forward, -1 back; drives which way the title slides. */
    var direction by mutableIntStateOf(1)
        private set
    /** Full metadata (streams for HDR/audio badges, clear logos). */
    val details = mutableStateMapOf<String, PlexItem>()
    /** The stage takes all remote input; anyone (e.g. a dialog closing) can hand focus back. */
    val stageFocus = FocusRequester()
    /** Set by the screen; loads an image into Coil's cache ahead of time. */
    var prefetchImage: ((String) -> Unit)? = null

    init {
        viewModelScope.launch {
            runCatching { env.library.sectionItems(env.server, sectionKey, 0, 500, sort = "titleSort").metadata }
                .onSuccess {
                    items = it
                    prefetchAround(0)
                }
                .onFailure { error = it.message ?: "Unknown error" }
        }
    }

    fun move(delta: Int) {
        val list = items ?: return
        val next = (index + delta).coerceIn(0, list.lastIndex)
        if (next == index) return
        direction = if (next > index) 1 else -1
        index = next
        prefetchAround(next)
    }

    /** Details for this title and its neighbours, so logos/badges are there before you arrive. */
    private fun prefetchAround(i: Int) {
        val list = items ?: return
        (i - 1..i + 2).mapNotNull { list.getOrNull(it) }.forEach(::loadDetails)
        // Warm the image cache for the next poster so flipping shows it sharp immediately.
        list.getOrNull(i + 1)?.let { next -> env.posterLarge(next)?.let { prefetchImage?.invoke(it) } }
    }

    private fun loadDetails(item: PlexItem) {
        if (item.ratingKey in details) return
        viewModelScope.launch {
            runCatching { env.library.item(env.server, item.ratingKey) }.getOrNull()?.let { details[item.ratingKey] = it }
        }
    }
}

/**
 * "The vault": a cinematic showcase for small, high-quality collections. One title at a time,
 * full-bleed artwork, the logo, and only the specs that make these copies special.
 */
@Composable
fun PlexShowcaseScreen(env: PlexEnv, sectionKey: String, title: String, onOpen: (PlexItem) -> Unit) {
    val vm = viewModel(key = "showcase-$sectionKey-${env.server.id}") { PlexShowcaseViewModel(env, sectionKey) }
    val context = androidx.compose.ui.platform.LocalContext.current
    vm.prefetchImage = { url -> coil3.SingletonImageLoader.get(context).enqueue(coil3.request.ImageRequest.Builder(context).data(url).build()) }
    val items = vm.items
    val current = items?.getOrNull(vm.index)
    val detail = current?.let { vm.details[it.ratingKey] } ?: current

    Box(Modifier.fillMaxSize()) {
        Backdrop(env.backdrop(current))
        // The trailer plays silently over the artwork; the same scrims sit on top of it.
        TrailerBackdrop(env, current)
        TechOverlay()

        when {
            vm.error != null -> MessageState("Couldn't load $title", vm.error)
            items == null -> Unit
            items.isEmpty() -> MessageState("$title is empty", "Add some titles to this library in Plex.")
            else -> Stage(vm, items, onOpen) {
                Column(Modifier.fillMaxSize()) {
                    Header(title, vm.index, items.size)
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                            Feature(env, detail, vm.direction)
                            Spacer(Modifier.height(30.dp))
                            Hints()
                        }
                        Deck(env, items, vm.index, Modifier.fillMaxHeight().width(420.dp))
                    }
                    TickBar(vm.index, items.size, items.getOrNull(vm.index + 1)?.title)
                }
            }
        }
    }
}

/** One focusable surface for the whole screen: Left/Right flip titles, OK opens. */
@Composable
private fun Stage(vm: PlexShowcaseViewModel, items: List<PlexItem>, onOpen: (PlexItem) -> Unit, content: @Composable () -> Unit) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { vm.stageFocus.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(vm.stageFocus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> { vm.move(1); true }
                    Key.DirectionLeft -> { vm.move(-1); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { items.getOrNull(vm.index)?.let(onOpen); true }
                    Key.DirectionUp, Key.DirectionDown -> true // nothing else on screen to move to
                    else -> false
                }
            }
            .focusable(),
    ) { content() }
}

/** Fine scanlines and a vignette: gives the artwork a "screen in a dark room" feel. */
@Composable
private fun TechOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val step = 3.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(Color.Black.copy(alpha = 0.12f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
        drawRect(
            Brush.radialGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                center = Offset(size.width * 0.6f, size.height * 0.35f),
                radius = size.width * 0.75f,
            ),
        )
        // Extra shade under the text column so the title always reads.
        drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), endX = size.width * 0.55f))
    }
}

@Composable
private fun Header(title: String, index: Int, count: Int) {
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.ScreenH, end = Spacing.ScreenH, top = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${title.uppercase()}  ·  COLLECTION",
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.32.em),
                color = ShieldColors.Accent,
            )
            // A thin rail with a slow light sweeping along it.
            Canvas(Modifier.width(360.dp).height(2.dp)) {
                drawRect(Color.White.copy(alpha = 0.12f))
                val x = size.width * sweep
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Ice.copy(alpha = 0.9f), Color.Transparent),
                        startX = x - 70.dp.toPx(),
                        endX = x + 70.dp.toPx(),
                    ),
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%02d".format(index + 1), fontFamily = FontFamily.Monospace, fontSize = 40.sp, fontWeight = FontWeight.Light, color = Color.White)
            Text(
                " / %02d".format(count),
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                color = ShieldColors.OnSurfaceMuted,
                modifier = Modifier.padding(bottom = 7.dp),
            )
        }
    }
}

@Composable
private fun Feature(env: PlexEnv, item: PlexItem?, direction: Int) {
    AnimatedContent(
        targetState = item,
        // Only a new title animates; its logo and badges arriving later update in place (no bounce).
        contentKey = { it?.ratingKey },
        transitionSpec = {
            val dx = direction
            (fadeIn(tween(Motion.ENTER_MS + 120, easing = Motion.EaseOut)) +
                slideInHorizontally(tween(420, easing = Motion.EaseOut)) { w -> dx * w / 8 }) togetherWith
                (fadeOut(tween(Motion.EXIT_MS)) + slideOutHorizontally(tween(260, easing = Motion.EaseOut)) { w -> -dx * w / 12 })
        },
        contentAlignment = Alignment.BottomStart,
        modifier = Modifier.padding(start = Spacing.ScreenH).height(290.dp),
        label = "feature",
    ) { shown ->
        if (shown == null) return@AnimatedContent
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom)) {
            val logo = env.logo(shown)
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    contentDescription = shown.title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier.height(130.dp).widthIn(max = 520.dp),
                )
            } else {
                Text(shown.title, style = MaterialTheme.typography.displayMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.height(32.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val facts = listOfNotNull(shown.year?.toString(), shown.duration?.let(::formatDuration)).joinToString("   ")
                if (facts.isNotEmpty()) {
                    Text(facts, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge, color = ShieldColors.OnSurfaceMuted)
                    Spacer(Modifier.width(6.dp))
                }
                shown.specBadges().filter { b -> b != shown.contentRating && b != "CC" }.forEach { GlowBadge(it) }
            }
            Text(
                shown.tagline?.takeIf { t -> t.isNotBlank() } ?: " ",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Spec tag with a cool, luminous outline. */
@Composable
private fun GlowBadge(text: String) {
    Box(
        Modifier
            .drawWithContent {
                drawRoundRect(Ice.copy(alpha = 0.10f), cornerRadius = CornerRadius(6.dp.toPx()))
                drawContent()
            }
            .border(1.dp, Brush.horizontalGradient(listOf(Ice.copy(alpha = 0.9f), Ice.copy(alpha = 0.35f))), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.14.em),
            fontWeight = FontWeight.SemiBold,
            color = Ice,
        )
    }
}

/**
 * Just the current poster, so the artwork behind stays clear. Flipping cross-fades and slides
 * the poster in the direction of travel.
 */
@Composable
private fun Deck(env: PlexEnv, items: List<PlexItem>, index: Int, modifier: Modifier) {
    Box(modifier.padding(end = Spacing.ScreenH), contentAlignment = Alignment.CenterEnd) {
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                val dx = if (targetState > initialState) 1 else -1
                (fadeIn(tween(380, easing = Motion.EaseOut)) + slideInHorizontally(tween(420, easing = Motion.EaseOut)) { w -> dx * w / 5 }) togetherWith
                    (fadeOut(tween(200)) + slideOutHorizontally(tween(300, easing = Motion.EaseOut)) { w -> -dx * w / 5 })
            },
            label = "poster",
        ) { i ->
            val item = items.getOrNull(i) ?: return@AnimatedContent
            Box(
                Modifier
                    .width(250.dp)
                    .aspectRatio(2f / 3f)
                    .shadow(40.dp, RoundedCornerShape(12.dp), ambientColor = Ice, spotColor = Ice)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Ice.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
            ) {
                AsyncImage(env.posterLarge(item), contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** A slim row of ticks showing position, and what's next on the right. */
@Composable
private fun TickBar(index: Int, count: Int, nextTitle: String?) {
    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.ScreenH, end = Spacing.ScreenH, bottom = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val active = i == index
            val w by animateFloatAsState(if (active) 46f else 16f, tween(300, easing = Motion.EaseOut), label = "w")
            Box(
                Modifier
                    .width(w.dp)
                    .height(3.dp)
                    .background(if (active) Ice else Color.White.copy(alpha = 0.22f), RoundedCornerShape(2.dp)),
            )
        }
        Spacer(Modifier.weight(1f))
        if (nextTitle != null) {
            Text("NEXT", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.3.em), color = ShieldColors.Accent)
            Spacer(Modifier.width(8.dp))
            Text(nextTitle, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Hints() {
    Row(
        Modifier.padding(start = Spacing.ScreenH, bottom = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("◀ ▶" to "BROWSE", "OK" to "OPEN").forEach { (keyLabel, label) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    keyLabel,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Ice,
                    modifier = Modifier
                        .border(1.dp, Ice.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
                Text(label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.em), color = ShieldColors.OnSurfaceMuted)
            }
        }
    }
}
