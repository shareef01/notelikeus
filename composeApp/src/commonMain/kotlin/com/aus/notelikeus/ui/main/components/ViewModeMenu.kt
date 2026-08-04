package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode

@Composable
fun ViewModeMenu(
    viewMode: NoteViewMode,
    onViewModeChange: (NoteViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    IconButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            expanded = true
        },
        modifier = modifier
    ) {
        Icon(
            imageVector = viewModeIcon(viewMode),
            contentDescription = stringResource(Res.string.cd_view_mode),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier.widthIn(min = 220.dp),
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp
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
                            .size(20.dp)
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
                                .size(18.dp)
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
    NoteViewMode.LIST -> Icons.Default.ViewHeadline
    NoteViewMode.COMPACT -> Icons.Default.ViewAgenda
}

fun viewModeLabelRes(mode: NoteViewMode): StringResource = when (mode) {
    NoteViewMode.GRID_2 -> Res.string.view_mode_grid_2
    NoteViewMode.GRID_3 -> Res.string.view_mode_grid_3
    NoteViewMode.LIST -> Res.string.view_mode_list
    NoteViewMode.COMPACT -> Res.string.view_mode_compact
}

fun sortOrderLabelRes(order: NoteSortOrder): StringResource = when (order) {
    NoteSortOrder.MANUAL -> Res.string.sort_manual
    NoteSortOrder.NEWEST -> Res.string.sort_newest
    NoteSortOrder.OLDEST -> Res.string.sort_oldest
}
