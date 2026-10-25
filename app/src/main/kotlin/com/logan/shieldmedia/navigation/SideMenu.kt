package com.logan.shieldmedia.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.designsystem.ShieldIcon

private val OpenWidth = 300.dp
private val ItemShape = RoundedCornerShape(50)

/**
 * The app's navigation menu. Closed, it's a quiet icon rail. Open, it's a solid panel with the
 * brand at the top, grouped sections, and the current profile at the bottom. Focus is a bright
 * white pill (readable from across a room); the current section carries a small gold dot.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NavigationDrawerScope.SideMenu(
    drawerValue: DrawerValue,
    currentSection: String?,
    libraries: List<MenuEntry>,
    profileName: String?,
    profileImage: String?,
    currentFocus: FocusRequester,
    modifier: Modifier = Modifier,
    onSelect: (MenuEntry) -> Unit,
    onProfile: () -> Unit,
) {
    val open = drawerValue == DrawerValue.Open
    Column(
        modifier
            .fillMaxHeight()
            .then(if (open) Modifier.width(OpenWidth) else Modifier)
            .drawWithContent {
                if (open) {
                    // Deep slate panel with a faint warm glow behind the brand; solid, never see-through.
                    drawRect(Brush.verticalGradient(listOf(Color(0xFF1A1D25), Color(0xFF0E1015))))
                    drawRect(
                        Brush.radialGradient(
                            listOf(ShieldColors.Accent.copy(alpha = 0.10f), Color.Transparent),
                            center = Offset(0f, 0f),
                            radius = size.width * 1.1f,
                        ),
                    )
                }
                drawContent()
                if (open) {
                    drawRect(ShieldColors.Hairline, topLeft = Offset(size.width - 1.dp.toPx(), 0f), size = Size(1.dp.toPx(), size.height))
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent),
                            startX = size.width,
                            endX = size.width + 32.dp.toPx(),
                        ),
                        topLeft = Offset(size.width, 0f),
                        size = Size(32.dp.toPx(), size.height),
                    )
                }
            }
            .padding(horizontal = 14.dp, vertical = 22.dp),
    ) {
        Brand(open)
        Spacer(Modifier.height(18.dp))

        MenuItem(Destination.Search.asEntry(), currentSection, currentFocus, onSelect)
        MenuItem(Destination.Home.asEntry(), currentSection, currentFocus, onSelect)

        // One entry per Plex library (Blu-Ray, Movies, TV Shows...), straight from the server.
        if (libraries.isNotEmpty()) {
            Section(open, "Library")
            libraries.forEach { MenuItem(it, currentSection, currentFocus, onSelect) }
        }

        Section(open, "Live TV")
        MenuItem(Destination.LiveTv.asEntry(), currentSection, currentFocus, onSelect)
        MenuItem(Destination.IptvVod.asEntry(), currentSection, currentFocus, onSelect)

        Spacer(Modifier.weight(1f))
        Section(open, "Account")
        ProfileItem(profileName, profileImage, onProfile)
        MenuItem(Destination.Settings.asEntry(), currentSection, currentFocus, onSelect)
    }
}

@Composable
private fun Brand(open: Boolean) {
    Row(
        Modifier.padding(start = 14.dp).height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Canvas(Modifier.size(width = 22.dp, height = 24.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.12f, size.height * 0.08f)
                lineTo(size.width * 0.12f, size.height * 0.92f)
                lineTo(size.width * 0.98f, size.height * 0.5f)
                close()
            }
            drawPath(path, ShieldColors.Accent)
        }
        AnimatedVisibility(open, enter = fadeIn(tween(220, delayMillis = 60)), exit = fadeOut(tween(100))) {
            Column {
                Text("Shield Media", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ShieldColors.OnSurface)
                Text("PLEX · LIVE TV", style = MaterialTheme.typography.labelSmall, color = ShieldColors.OnSurfaceFaint)
            }
        }
    }
}

@Composable
private fun ColumnScope.Section(open: Boolean, title: String?) {
    // Closed rail: a short hairline divider between groups. Open: a small caps label.
    if (title == null) return
    Box(Modifier.height(34.dp).padding(start = 18.dp), contentAlignment = Alignment.BottomStart) {
        if (open) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = ShieldColors.OnSurfaceFaint,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        } else {
            Box(Modifier.padding(bottom = 12.dp).size(width = 20.dp, height = 1.dp).background(ShieldColors.Hairline))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavigationDrawerScope.MenuItem(
    dest: MenuEntry,
    currentSection: String?,
    currentFocus: FocusRequester,
    onSelect: (MenuEntry) -> Unit,
) {
    val selected = currentSection == dest.id
    NavigationDrawerItem(
        selected = selected,
        onClick = { onSelect(dest) },
        modifier = Modifier
            .then(if (selected) Modifier.focusRequester(currentFocus) else Modifier)
            // Current section: a small gold dot just outside the pill.
            .drawBehind {
                if (selected) drawCircle(ShieldColors.Accent, radius = 3.dp.toPx(), center = Offset(-9.dp.toPx(), size.height / 2))
            },
        shape = NavigationDrawerItemDefaults.shape(ItemShape),
        scale = NavigationDrawerItemDefaults.scale(focusedScale = 1.03f),
        glow = NavigationDrawerItemDefaults.glow(),
        colors = menuColors(),
        leadingContent = { ShieldIcon(dest.icon, contentDescription = dest.label, size = 22.dp) },
    ) {
        Text(dest.label, style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavigationDrawerScope.ProfileItem(name: String?, image: String?, onClick: () -> Unit) {
    if (name == null) return
    NavigationDrawerItem(
        selected = false,
        onClick = onClick,
        shape = NavigationDrawerItemDefaults.shape(ItemShape),
        scale = NavigationDrawerItemDefaults.scale(focusedScale = 1.03f),
        colors = menuColors(),
        leadingContent = {
            Box(
                Modifier
                    .size(26.dp)
                    .background(Brush.linearGradient(listOf(ShieldColors.Accent, Color(0xFFD9822B))), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.take(1).uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ShieldColors.OnAccent)
                if (!image.isNullOrBlank()) {
                    coil3.compose.AsyncImage(
                        model = image,
                        contentDescription = name,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                }
            }
        },
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun menuColors() = NavigationDrawerItemDefaults.colors(
    containerColor = Color.Transparent,
    contentColor = ShieldColors.OnSurfaceMuted,
    inactiveContentColor = ShieldColors.OnSurfaceFaint,
    selectedContainerColor = Color.Transparent,
    selectedContentColor = ShieldColors.OnSurface,
    focusedContainerColor = ShieldColors.OnSurface,
    focusedContentColor = ShieldColors.Background,
    focusedSelectedContainerColor = ShieldColors.OnSurface,
    focusedSelectedContentColor = ShieldColors.Background,
    pressedContainerColor = ShieldColors.OnSurface.copy(alpha = 0.85f),
    pressedContentColor = ShieldColors.Background,
    pressedSelectedContainerColor = ShieldColors.OnSurface.copy(alpha = 0.85f),
    pressedSelectedContentColor = ShieldColors.Background,
)
