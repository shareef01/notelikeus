package com.aus.notelikeus.ui.main.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aus.notelikeus.domain.model.NoteViewMode
import org.jetbrains.compose.resources.stringResource

/** Grid widths the grid segment cycles through, in order. */
private val GridModes = listOf(
    NoteViewMode.GRID_2,
    NoteViewMode.GRID_3,
    NoteViewMode.GRID_4,
    NoteViewMode.GRID_5
)

/**
 * The three families the toggle shows. [NoteViewMode] carries six values because the grid
 * supports four widths; collapsing them into one segment keeps the control to three buttons
 * without making any mode unreachable.
 */
private enum class Segment { LIST, GRID, COMPACT }

private fun segmentOf(mode: NoteViewMode): Segment = when (mode) {
    NoteViewMode.LIST -> Segment.LIST
    NoteViewMode.COMPACT -> Segment.COMPACT
    NoteViewMode.GRID_2, NoteViewMode.GRID_3, NoteViewMode.GRID_4, NoteViewMode.GRID_5 -> Segment.GRID
}

/**
 * Segmented control matching the web app's view-mode toggle.
 *
 * List and Compact select their mode directly. The grid segment selects [NoteViewMode.GRID_2]
 * from any non-grid mode, and advances to the next width when it is already selected — so the
 * grid widths that [ProfileSheet] can reach stay reachable here, and the highlighted segment
 * always reflects the real mode rather than defaulting to the first one.
 */
@Composable
fun ViewModeToggle(
    viewMode: NoteViewMode,
    onViewModeChange: (NoteViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val containerShape = RoundedCornerShape(999.dp)
    val activeSegment = segmentOf(viewMode)

    Row(
        modifier = modifier
            .height(40.dp)
            .clip(containerShape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = containerShape
            )
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        SegmentButton(
            icon = Icons.Default.ViewHeadline,
            label = stringResource(viewModeLabelRes(NoteViewMode.LIST)),
            selected = activeSegment == Segment.LIST,
            onClick = { onViewModeChange(NoteViewMode.LIST) }
        )
        SegmentButton(
            // GRID_2 reads as a grid; the wider modes are columns, matching the old menu's icons.
            icon = if (viewMode == NoteViewMode.GRID_2 || activeSegment != Segment.GRID) {
                Icons.Default.GridView
            } else {
                Icons.Default.ViewColumn
            },
            label = stringResource(
                viewModeLabelRes(if (activeSegment == Segment.GRID) viewMode else NoteViewMode.GRID_2)
            ),
            selected = activeSegment == Segment.GRID,
            // Only annotate once past the default width — a "2" on every grid is noise.
            badge = viewMode.columns.takeIf { activeSegment == Segment.GRID && it > 2 },
            onClick = {
                val next = if (activeSegment == Segment.GRID) {
                    GridModes[(GridModes.indexOf(viewMode) + 1) % GridModes.size]
                } else {
                    NoteViewMode.GRID_2
                }
                onViewModeChange(next)
            }
        )
        SegmentButton(
            icon = Icons.Default.ViewAgenda,
            label = stringResource(viewModeLabelRes(NoteViewMode.COMPACT)),
            selected = activeSegment == Segment.COMPACT,
            onClick = { onViewModeChange(NoteViewMode.COMPACT) }
        )
    }
}

@Composable
private fun SegmentButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: Int? = null
) {
    val haptic = LocalHapticFeedback.current
    val bgColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        } else {
            Color.Transparent
        },
        label = "toggle_bg"
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        },
        label = "toggle_icon"
    )

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bgColor)
            .semantics { contentDescription = label }
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = iconTint
        )
        if (badge != null) {
            Text(
                text = badge.toString(),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = iconTint,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-3).dp, y = 3.dp)
            )
        }
    }
}
