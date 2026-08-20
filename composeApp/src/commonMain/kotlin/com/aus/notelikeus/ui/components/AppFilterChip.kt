package com.aus.notelikeus.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aus.notelikeus.ui.theme.Chrome
import com.aus.notelikeus.ui.theme.Size
import com.aus.notelikeus.ui.theme.Spacing

/**
 * The app's filter chip.
 *
 * Promoted out of `ui/main/components/FilterRow.kt`, where it was `internal` and screen-specific
 * despite already being used by the empty state — which meant `ui/components/NotesEmptyState`, a
 * shared component, imported from `ui/main/components`, a screen package. Shared components
 * should not depend on the screens that happen to use them; it lives here now.
 *
 * Compact is the row variant (40dp); the default is a standalone chip at the 44dp comfortable
 * height. Neither drops below the [Size.touchTarget] minimum once the chip's own tap padding is
 * counted.
 */
@Composable
fun AppFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    enabled: Boolean = true,
    compact: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    /** Used by the active-filter chips for their remove affordance. */
    trailingIcon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = Chrome.SelectedBorder)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = Chrome.ChipBorder)
    }
    val selectedContainer = MaterialTheme.colorScheme.primary.copy(alpha = Chrome.SelectedWash)
    val inactiveContainer = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)

    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = if (compact) 13.sp else 14.sp,
                    letterSpacing = (-0.15).sp
                ),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        },
        modifier = modifier.heightIn(min = if (compact) Size.chipHeightCompact else Size.chipHeight),
        shape = CircleShape,
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = borderColor,
            selectedBorderColor = borderColor,
            borderWidth = Spacing.hairline,
            selectedBorderWidth = Spacing.hairline
        ),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = inactiveContainer,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = selectedContainer,
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = Color.Transparent,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            disabledSelectedContainerColor = selectedContainer.copy(alpha = 0.45f)
        )
    )
}
