package com.logan.shieldmedia.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Poster/thumbnail tile with scale + accent border on focus. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MediaCard(
    title: String,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 2f / 3f,
    width: Dp = 150.dp,
    onClick: () -> Unit = {},
) {
    Column(modifier = modifier.width(width)) {
        Card(
            onClick = onClick,
            modifier = Modifier.aspectRatio(aspectRatio),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1.08f),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(3.dp, ShieldColors.Accent),
                    shape = RoundedCornerShape(10.dp),
                ),
            ),
            colors = CardDefaults.colors(containerColor = ShieldColors.SurfaceHigh),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = title.take(2).uppercase(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = ShieldColors.OnSurfaceMuted,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = ShieldColors.OnSurface,
            maxLines = 1,
            modifier = Modifier.padding(top = 16.dp, start = 2.dp),
        )
    }
}

/** Titled horizontal row of items; the standard building block for home/library screens. */
@Composable
fun <T> ContentRow(
    title: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = ShieldColors.OnSurface,
            modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(items) { itemContent(it) }
        }
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 48.dp, vertical = 24.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = ShieldColors.OnSurface,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = ShieldColors.OnSurfaceMuted,
            )
        }
    }
}
