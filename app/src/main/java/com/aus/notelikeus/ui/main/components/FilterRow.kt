package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.R
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.ui.components.NoteColorSwatch
import com.aus.notelikeus.ui.theme.isNoteColorDarkTheme
import com.aus.notelikeus.ui.theme.noteColorsForTheme

@Composable
fun FilterRow(
    selectedColor: Int?,
    onColorSelect: (Int?) -> Unit,
    allLabels: List<Label>,
    selectedLabelId: Long?,
    onLabelSelect: (Long?) -> Unit,
    sortOrder: NoteSortOrder = NoteSortOrder.MANUAL,
    hasActiveFilters: Boolean = false,
    onClearFilters: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isDarkTheme = isNoteColorDarkTheme()
    val colors = noteColorsForTheme(isDarkTheme)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                PrecisionFilterChip(
                    selected = true,
                    onClick = {},
                    label = stringResource(sortOrderLabelRes(sortOrder)),
                    enabled = false
                )
            }
            if (hasActiveFilters) {
                item {
                    PrecisionFilterChip(
                        selected = true,
                        onClick = onClearFilters,
                        label = stringResource(R.string.filters_active)
                    )
                }
            }
            item {
                PrecisionFilterChip(
                    selected = selectedColor == null,
                    onClick = { onColorSelect(null) },
                    label = stringResource(R.string.all_colors)
                )
            }
            itemsIndexed(colors, key = { _, color -> color.toArgb() }) { _, color ->
                val colorArgb = color.toArgb()
                NoteColorSwatch(
                    color = color,
                    isSelected = selectedColor == colorArgb,
                    onClick = { onColorSelect(if (selectedColor == colorArgb) null else colorArgb) }
                )
            }
        }

        if (allLabels.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    PrecisionFilterChip(
                        selected = selectedLabelId == null,
                        onClick = { onLabelSelect(null) },
                        label = stringResource(R.string.all_labels)
                    )
                }
                items(allLabels, key = { it.id ?: it.name }) { label ->
                    PrecisionFilterChip(
                        selected = selectedLabelId == label.id,
                        onClick = { onLabelSelect(if (selectedLabelId == label.id) null else label.id) },
                        label = label.name
                    )
                }
            }
        }
    }
}

@Composable
internal fun PrecisionFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val selectedContainer = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)

    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        modifier = modifier.heightIn(max = 32.dp),
        shape = MaterialTheme.shapes.extraSmall,
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = borderColor,
            selectedBorderColor = borderColor,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp
        ),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = selectedContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = Color.Transparent,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            disabledSelectedContainerColor = selectedContainer.copy(alpha = 0.45f)
        )
    )
}
