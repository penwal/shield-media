package com.logan.shieldmedia.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

private val CardShape = RoundedCornerShape(8.dp)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(CardShape),
        scale = CardDefaults.scale(focusedScale = 1.06f, pressedScale = 1.03f),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, ShieldColors.FocusOutline), shape = CardShape),
        ),
        glow = CardDefaults.glow(focusedGlow = Glow(Color.Black.copy(alpha = 0.7f), 18.dp)),
        colors = CardDefaults.colors(containerColor = ShieldColors.SurfaceHigh),
    ) {
        Box(Modifier.fillMaxSize(), content = content)
    }
}

/** Shown while an image loads or when there is none: the title, quietly, instead of a blank tile. */
@Composable
private fun BoxScope.ImageOrTitle(imageUrl: String?, title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = ShieldColors.OnSurfaceFaint,
        textAlign = TextAlign.Center,
        maxLines = 3,
        modifier = Modifier.align(Alignment.Center).padding(12.dp),
    )
    if (imageUrl != null) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun BoxScope.ProgressAndBadge(progress: Float?, badge: String?) {
    if (progress != null && progress > 0f) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(4.dp)
                .background(Color.Black.copy(alpha = 0.55f)),
        ) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(ShieldColors.Accent))
        }
    }
    if (badge != null) {
        Text(
            text = badge,
            style = MaterialTheme.typography.labelMedium,
            color = ShieldColors.OnAccent,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .background(ShieldColors.Accent, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** 2:3 poster for movies, shows and seasons. */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
    badge: String? = null,
    width: Dp = 136.dp,
    focusModifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    Column(modifier.width(width)) {
        FocusCard(
            onClick = onClick,
            onLongClick = onLongClick,
            onFocused = onFocused,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).then(focusModifier),
        ) {
            ImageOrTitle(imageUrl, title)
            ProgressAndBadge(progress, badge)
        }
        CardCaption(title, subtitle)
    }
}

/** 16:9 thumbnail for episodes, continue watching and channels. */
@Composable
fun LandscapeCard(
    title: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
    badge: String? = null,
    width: Dp = 248.dp,
    focusModifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    Column(modifier.width(width)) {
        FocusCard(
            onClick = onClick,
            onLongClick = onLongClick,
            onFocused = onFocused,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).then(focusModifier),
        ) {
            ImageOrTitle(imageUrl, title)
            ProgressAndBadge(progress, badge)
        }
        CardCaption(title, subtitle)
    }
}

@Composable
private fun CardCaption(title: String, subtitle: String?) {
    Column(Modifier.padding(top = 12.dp, start = 2.dp, end = 2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = ShieldColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = ShieldColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Keeps the focused item at a fixed position while the list scrolls under it (the "pivot"
 * behaviour of Plex and Apple TV), instead of the list only scrolling at the edges.
 * With [onlyWhenHidden], items already fully on screen don't move the list at all.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PivotScroll(pivot: Dp, onlyWhenHidden: Boolean = false, content: @Composable () -> Unit) {
    val pivotPx = with(LocalDensity.current) { pivot.toPx() }
    val spec = remember(pivotPx, onlyWhenHidden) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                if (onlyWhenHidden && offset >= 0f && offset + size <= containerSize) 0f else offset - pivotPx
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** Titled horizontal row; the building block for home, details and library screens. */
@Composable
fun ContentRow(
    title: String,
    modifier: Modifier = Modifier,
    /** Left/right margin; 0 when the row sits inside something already padded. */
    inset: Dp = Spacing.ScreenH,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = ShieldColors.OnSurface,
            modifier = Modifier.padding(start = inset, bottom = 4.dp),
        )
        PivotScroll(pivot = inset) {
            LazyRow(
                // Vertical padding leaves room for the focused card's scale and glow.
                contentPadding = PaddingValues(horizontal = inset, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.CardGap),
                content = content,
            )
        }
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = Spacing.ScreenH, end = Spacing.ScreenH, top = Spacing.ScreenV, bottom = 16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = ShieldColors.OnSurface)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = ShieldColors.OnSurfaceMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        scale = ButtonDefaults.scale(focusedScale = 1.05f, pressedScale = 0.97f),
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = ShieldColors.OnSurface,
            focusedContainerColor = ShieldColors.Accent,
            focusedContentColor = ShieldColors.OnAccent,
            pressedContainerColor = ShieldColors.Accent,
            pressedContentColor = ShieldColors.OnAccent,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    ) { ButtonContent(text, icon) }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        scale = ButtonDefaults.scale(focusedScale = 1.05f, pressedScale = 0.97f),
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = ShieldColors.OnSurface,
            focusedContainerColor = ShieldColors.OnSurface,
            focusedContentColor = ShieldColors.Background,
            pressedContainerColor = ShieldColors.OnSurface,
            pressedContentColor = ShieldColors.Background,
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    ) { ButtonContent(text, icon) }
}

@Composable
private fun RowScope.ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Box(Modifier.width(8.dp))
    }
    Text(text, style = MaterialTheme.typography.titleSmall)
}

/** Outlined spec tag: 4K, DOLBY VISION, ATMOS, PG-13. */
@Composable
fun SpecBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = ShieldColors.OnSurface.copy(alpha = 0.9f),
        modifier = modifier
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
fun MessageState(
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(Spacing.ScreenH),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = ShieldColors.OnSurface, textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = ShieldColors.OnSurfaceMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp).widthIn(max = 560.dp),
            )
        }
        if (action != null) {
            Box(Modifier.padding(top = 24.dp)) { action() }
        }
    }
}

/** Placeholder row while content loads; a slow, gentle pulse rather than a spinner. */
@Composable
fun SkeletonRow(landscape: Boolean = false, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    Column(modifier) {
        Box(
            Modifier
                .padding(start = Spacing.ScreenH, bottom = 18.dp)
                .size(width = 180.dp, height = 18.dp)
                .graphicsLayer { alpha = pulse }
                .background(ShieldColors.SurfaceHigh, RoundedCornerShape(4.dp)),
        )
        Row(Modifier.padding(start = Spacing.ScreenH), horizontalArrangement = Arrangement.spacedBy(Spacing.CardGap)) {
            repeat(7) {
                Box(
                    Modifier
                        .width(if (landscape) 248.dp else 136.dp)
                        .aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                        .graphicsLayer { alpha = pulse }
                        .clip(CardShape)
                        .background(ShieldColors.SurfaceHigh),
                )
            }
        }
    }
}

/** Gradient that keeps text legible over artwork: dark on the left and bottom, clear top-right. */
fun Modifier.backdropScrim(): Modifier = this
    .background(
        Brush.horizontalGradient(
            0f to ShieldColors.Background,
            0.3f to ShieldColors.Background.copy(alpha = 0.9f),
            0.55f to ShieldColors.Background.copy(alpha = 0.6f),
            0.8f to ShieldColors.Background.copy(alpha = 0.15f),
            1f to Color.Transparent,
        ),
    )
    .background(
        Brush.verticalGradient(
            0.35f to Color.Transparent,
            0.75f to ShieldColors.Background.copy(alpha = 0.7f),
            1f to ShieldColors.Background,
        ),
    )
