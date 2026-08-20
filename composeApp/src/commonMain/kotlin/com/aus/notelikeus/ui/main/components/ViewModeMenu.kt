package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.util.AppConfig
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.ui.theme.Size
import com.aus.notelikeus.ui.theme.Elevation

/** The three web-equivalent view options shown as an icon-only segmented control on desktop. */
private data class ViewModeSegment(
    val mode: NoteViewMode,
    val icon: ImageVector,
    val label: StringResource
)

private val webViewModeSegments = listOf(
    ViewModeSegment(NoteViewMode.LIST, Icons.Default.ViewHeadline, Res.string.view_mode_list),
    ViewModeSegment(NoteViewMode.GRID_2, Icons.Default.GridView, Res.string.view_mode_grid_2),
    ViewModeSegment(NoteViewMode.COMPACT, Icons.Default.ViewAgenda, Res.string.view_mode_compact)
)

/** Any grid column count (2–5) maps to the single web "Grid" segment. */
private fun isSegmentSelected(viewMode: NoteViewMode, segmentMode: NoteViewMode): Boolean = when (segmentMode) {
    NoteViewMode.LIST -> viewMode == NoteViewMode.LIST
    NoteViewMode.COMPACT -> viewMode == NoteViewMode.COMPACT
    else -> viewMode != NoteViewMode.LIST && viewMode != NoteViewMode.COMPACT
}

@Composable
fun ViewModeMenu(
    viewMode: NoteViewMode,
    onViewModeChange: (NoteViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    if (AppConfig.isDesktop) {
        // Web-style segmented control: three icon-only options (List / Grid / Compact),
        // selected = solid inverted pill, matching web's ViewModeToggle.
        Row(
            modifier = modifier
                .height(Size.controlHeight)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                .border(
                    width = Spacing.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    shape = CircleShape
                )
                .padding(Spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            webViewModeSegments.forEach { segment ->
                val isSelected = isSegmentSelected(viewMode, segment.mode)
                val segmentLabel = stringResource(segment.label)
                Box(
                    modifier = Modifier
                        .size(Size.iconLarge)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent
                        )
                        .semantics {
                            contentDescription = segmentLabel
                            selected = isSelected
                        }
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onViewModeChange(segment.mode)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = segment.icon,
                        contentDescription = null,
                        modifier = Modifier.size(Size.iconMedium),
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
        return
    }

    var expanded by remember { mutableStateOf(false) }

    // Icon-only pill trigger, matching the web header controls: visible border + surface fill
    // so it reads as a control against the search bar background.
    Box(
        modifier = modifier
            .size(Size.controlHeight)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .border(
                width = Spacing.hairline,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape = CircleShape
            )
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                expanded = true
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = viewModeIcon(viewMode),
            contentDescription = stringResource(Res.string.cd_view_mode),
            modifier = Modifier.size(Size.icon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier.widthIn(min = 220.dp),
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = Elevation.card,
        shadowElevation = Elevation.overlay
    ) {
        NoteViewMode.entries.forEach { mode ->
            val isSelected = mode == viewMode
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(viewModeLabelRes(mode)),
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = viewModeIcon(mode),
                        contentDescription = null,
                        modifier = Modifier
                            .size(Size.icon)
                            .semantics { hideFromAccessibility() },
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                },
                trailingIcon = {
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier
                                .size(Size.iconMedium)
                                .semantics { hideFromAccessibility() },
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onViewModeChange(mode)
                    expanded = false
                }
            )
        }
    }
}

private fun viewModeIcon(mode: NoteViewMode) = when (mode) {
    NoteViewMode.GRID_2 -> Icons.Default.GridView
    NoteViewMode.GRID_3 -> Icons.Default.ViewColumn
    NoteViewMode.GRID_4 -> Icons.Default.ViewColumn // Or another icon
    NoteViewMode.GRID_5 -> Icons.Default.ViewColumn
    NoteViewMode.LIST -> Icons.Default.ViewHeadline
    NoteViewMode.COMPACT -> Icons.Default.ViewAgenda
}

fun viewModeLabelRes(mode: NoteViewMode): StringResource = when (mode) {
    NoteViewMode.GRID_2 -> Res.string.view_mode_grid_2
    NoteViewMode.GRID_3 -> Res.string.view_mode_grid_3
    NoteViewMode.GRID_4 -> Res.string.view_mode_grid_4
    NoteViewMode.GRID_5 -> Res.string.view_mode_grid_5
    NoteViewMode.LIST -> Res.string.view_mode_list
    NoteViewMode.COMPACT -> Res.string.view_mode_compact
}

fun sortOrderLabelRes(order: NoteSortOrder): StringResource = when (order) {
    NoteSortOrder.MANUAL -> Res.string.sort_manual
    NoteSortOrder.NEWEST -> Res.string.sort_newest
    NoteSortOrder.OLDEST -> Res.string.sort_oldest
}
