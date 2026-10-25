package com.logan.shieldmedia.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.logan.shieldmedia.designsystem.ShieldColors
import com.logan.shieldmedia.designsystem.ShieldIcon
import com.logan.shieldmedia.designsystem.ShieldIcons

/** One row in a side panel: a section heading or a selectable option. */
sealed interface PanelRow {
    data class Header(val text: String) : PanelRow
    data class Option(val title: String, val detail: String?, val selected: Boolean, val onClick: () -> Unit) : PanelRow
}


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OptionsPanel(title: String, rows: List<PanelRow>, emptyText: String) {
    val firstFocus = remember { FocusRequester() }
    val options = rows.filterIsInstance<PanelRow.Option>()
    val focusTarget = options.firstOrNull { it.selected } ?: options.firstOrNull()
    LaunchedEffect(title) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxHeight()
            .width(440.dp)
            .background(ShieldColors.Surface.copy(alpha = 0.96f))
            .padding(vertical = 40.dp, horizontal = 20.dp)
            // Modal: D-pad must not wander into the OSD behind the panel. Back closes it.
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = ShieldColors.OnSurface,
            modifier = Modifier.padding(start = 12.dp, bottom = 16.dp),
        )
        if (options.isEmpty()) {
            Text(emptyText, color = ShieldColors.OnSurfaceMuted, modifier = Modifier.padding(12.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(rows) { _, row ->
                when (row) {
                    is PanelRow.Header -> Text(
                        row.text.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = ShieldColors.OnSurfaceFaint,
                        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp),
                    )
                    is PanelRow.Option -> ListItem(
                        selected = row.selected,
                        onClick = row.onClick,
                        headlineContent = { Text(row.title) },
                        supportingContent = row.detail?.let { { Text(it) } },
                        trailingContent = if (row.selected) {
                            { ShieldIcon(ShieldIcons.Check, contentDescription = "Selected", size = 20.dp) }
                        } else {
                            null
                        },
                        modifier = if (row == focusTarget) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
    }
}
