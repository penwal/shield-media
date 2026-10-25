package com.logan.shieldmedia.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults

private val RowShape = RoundedCornerShape(12.dp)
private val ChipShape = RoundedCornerShape(50)

/**
 * The one list row used everywhere (settings, sources, side panels, channel lists).
 * Resting rows sit on the background; focus lifts the row into a bright, high-contrast slab
 * (tvOS-style); "selected" is a quiet tint, so an always-selected row never looks focused.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ShieldListItem(
    selected: Boolean,
    onClick: () -> Unit,
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable BoxScope.() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        headlineContent = headlineContent,
        supportingContent = supportingContent,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        modifier = modifier,
        shape = ListItemDefaults.shape(RowShape),
        scale = ListItemDefaults.scale(focusedScale = 1.02f, pressedScale = 1f),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = ShieldColors.OnSurface,
            selectedContainerColor = Color.White.copy(alpha = 0.06f),
            selectedContentColor = ShieldColors.OnSurface,
            focusedContainerColor = ShieldColors.OnSurface,
            focusedContentColor = ShieldColors.Background,
            focusedSelectedContainerColor = ShieldColors.OnSurface,
            focusedSelectedContentColor = ShieldColors.Background,
            pressedContainerColor = ShieldColors.OnSurface.copy(alpha = 0.85f),
            pressedContentColor = ShieldColors.Background,
            pressedSelectedContainerColor = ShieldColors.OnSurface.copy(alpha = 0.85f),
            pressedSelectedContentColor = ShieldColors.Background,
        ),
    )
}

/** Pill filter (library tabs, categories, seasons): hairline at rest, tinted when chosen, white when focused. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ShieldChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val hairline = Border(BorderStroke(1.dp, ShieldColors.Hairline), shape = ChipShape)
    val none = Border(BorderStroke(1.dp, Color.Transparent), shape = ChipShape)
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        shape = FilterChipDefaults.shape(ChipShape),
        scale = FilterChipDefaults.scale(focusedScale = 1.04f),
        colors = FilterChipDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = ShieldColors.OnSurfaceMuted,
            focusedContainerColor = ShieldColors.OnSurface,
            focusedContentColor = ShieldColors.Background,
            pressedContainerColor = ShieldColors.OnSurface,
            pressedContentColor = ShieldColors.Background,
            selectedContainerColor = Color.White.copy(alpha = 0.14f),
            selectedContentColor = ShieldColors.OnSurface,
            focusedSelectedContainerColor = ShieldColors.OnSurface,
            focusedSelectedContentColor = ShieldColors.Background,
            pressedSelectedContainerColor = ShieldColors.OnSurface,
            pressedSelectedContentColor = ShieldColors.Background,
        ),
        border = FilterChipDefaults.border(
            border = hairline,
            focusedBorder = none,
            pressedBorder = none,
            selectedBorder = none,
            focusedSelectedBorder = none,
            pressedSelectedBorder = none,
        ),
        content = content,
    )
}

/**
 * Fades content out over [top]/[bottom] at the list's edges, so rows scrolling under a title
 * dissolve instead of being sliced in half.
 */
fun Modifier.fadingEdges(top: androidx.compose.ui.unit.Dp = 28.dp, bottom: androidx.compose.ui.unit.Dp = 28.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val t = top.toPx() / size.height
        val b = 1f - bottom.toPx() / size.height
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                t to Color.Black,
                b to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
