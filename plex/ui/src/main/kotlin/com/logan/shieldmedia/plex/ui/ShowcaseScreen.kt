package com.logan.shieldmedia.plex.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.logan.shieldmedia.designsystem.Backdrop
import com.logan.shieldmedia.designsystem.FocusMemory
import com.logan.shieldmedia.designsystem.MessageState
import com.logan.shieldmedia.designsystem.Motion
import com.logan.shieldmedia.designsystem.PivotScroll
import com.logan.shieldmedia.designsystem.RestoreFocus
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.designsystem.Spacing
import com.logan.shieldmedia.designsystem.focusMemory
import com.logan.shieldmedia.plex.PlexItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** Full metadata (streams for HDR/audio badges, clear logos), fetched as titles get focus. */
    val details = mutableStateMapOf<String, PlexItem>()
    val focus = FocusMemory()
    private var detailJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { env.library.sectionItems(env.server, sectionKey, 0, 500, sort = "titleSort").metadata }
                .onSuccess {
                    items = it
                    it.firstOrNull()?.let(::loadDetails)
                }
                .onFailure { error = it.message ?: "Unknown error" }
        }
    }

    fun onFocus(i: Int) {
        index = i
        val item = items?.getOrNull(i) ?: return
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            delay(180) // don't fetch for every poster flicked past
            loadDetails(item)
        }
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
    val items = vm.items
    val current = items?.getOrNull(vm.index)
    val detail = current?.let { vm.details[it.ratingKey] } ?: current

    Box(Modifier.fillMaxSize()) {
        Backdrop(env.backdrop(current))
        TechOverlay()

        when {
            vm.error != null -> MessageState("Couldn't load $title", vm.error)
            items == null -> Unit
            items.isEmpty() -> MessageState("$title is empty", "Add some titles to this library in Plex.")
            else -> Column(Modifier.fillMaxSize()) {
                Header(title, vm.index, items.size)
                Spacer(Modifier.weight(1f))
                Feature(env, detail)
                Spacer(Modifier.height(28.dp))
                Carousel(env, items, vm, onOpen)
            }
        }
    }
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
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                center = Offset(size.width * 0.6f, size.height * 0.35f),
                radius = size.width * 0.75f,
            ),
        )
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
            Text(
                "%02d".format(index + 1),
                fontFamily = FontFamily.Monospace,
                fontSize = 40.sp,
                fontWeight = FontWeight.Light,
                color = Color.White,
            )
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
private fun Feature(env: PlexEnv, item: PlexItem?) {
    AnimatedContent(
        targetState = item,
        contentKey = { it?.ratingKey to (it?.media?.firstOrNull()?.parts?.firstOrNull()?.streams?.size ?: 0) },
        transitionSpec = {
            (fadeIn(tween(Motion.ENTER_MS + 80, easing = Motion.EaseOut)) + slideInVertically(tween(320, easing = Motion.EaseOut)) { it / 10 }) togetherWith
                fadeOut(tween(Motion.EXIT_MS))
        },
        modifier = Modifier.padding(horizontal = Spacing.ScreenH),
        label = "feature",
    ) { it ->
        if (it == null) return@AnimatedContent
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val logo = env.logo(it)
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    contentDescription = it.title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.heightIn(max = 110.dp).widthIn(max = 460.dp),
                )
            } else {
                Text(it.title, style = MaterialTheme.typography.displayMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val facts = listOfNotNull(it.year?.toString(), it.duration?.let(::formatDuration)).joinToString("   ")
                if (facts.isNotEmpty()) {
                    Text(facts, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge, color = ShieldColors.OnSurfaceMuted)
                    Spacer(Modifier.width(6.dp))
                }
                it.specBadges().filter { b -> b != it.contentRating && b != "CC" }.forEach { GlowBadge(it) }
            }
            it.tagline?.takeIf { t -> t.isNotBlank() }?.let { t ->
                Text(t, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Spec tag with a cool, luminous outline. */
@Composable
private fun GlowBadge(text: String) {
    Box(
        Modifier
            .drawWithContent {
                drawRoundRect(
                    Ice.copy(alpha = 0.10f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                )
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Carousel(env: PlexEnv, items: List<PlexItem>, vm: PlexShowcaseViewModel, onOpen: (PlexItem) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    PivotScroll(pivot = Spacing.ScreenH) {
        LazyRow(
            contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.ratingKey }) { i, item ->
                val focused = i == vm.index
                Column(Modifier.width(132.dp)) {
                    Card(
                        onClick = { onOpen(item) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .focusMemory(vm.focus, item.ratingKey, isDefault = i == 0)
                            .onFocusChanged { if (it.isFocused) vm.onFocus(i) }
                            .graphicsLayer { alpha = if (focused) 1f else 0.5f },
                        shape = CardDefaults.shape(shape),
                        scale = CardDefaults.scale(focusedScale = 1.12f),
                        border = CardDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, Ice), shape = shape),
                        ),
                        glow = CardDefaults.glow(focusedGlow = Glow(Ice.copy(alpha = 0.55f), 22.dp)),
                        colors = CardDefaults.colors(containerColor = ShieldColors.SurfaceHigh),
                    ) {
                        AsyncImage(env.poster(item), contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Reflection(env.poster(item), visible = focused)
                }
            }
        }
    }
    RestoreFocus(vm.focus, ready = true)
}

/** A faded mirror image under the focused poster, like a glossy floor. */
@Composable
private fun Reflection(url: String?, visible: Boolean) {
    Box(
        Modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(10.dp))
            .graphicsLayer {
                alpha = if (visible) 0.32f else 0.10f
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent)), blendMode = BlendMode.DstIn)
            },
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.BottomCenter,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .graphicsLayer { scaleY = -1f },
        )
    }
}
