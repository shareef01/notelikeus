package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.compose.resources.stringResource
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.ui.components.NoteColorSwatch
import com.aus.notelikeus.ui.theme.noteColorName
import com.aus.notelikeus.ui.theme.Chrome
import com.aus.notelikeus.ui.theme.isNoteColorDarkTheme
import com.aus.notelikeus.ui.theme.noteColorsForTheme
import com.aus.notelikeus.ui.theme.AppType

/**
 * Desktop-only: adds mouse-wheel support to a horizontal scrollable, so the filter rows scroll
 * horizontally instead of letting the wheel fall through to the notes grid below. No-op on
 * Android (touch drag is the primary input there).
 */
expect fun Modifier.wheelHorizontalScroll(state: ScrollState): Modifier

/**
 * Touch target for the colour swatches. 48dp is the accessibility minimum, and the swatch itself
 * stays 26dp, so the extra is invisible padding rather than a bigger dot.
 *
 * It was 36dp because this row used to be pinned above the notes permanently, where every dp of
 * height was worth saving. It is not pinned any more -- it folds away as soon as the list scrolls
 * -- so the height costs far less than it did, while being under the minimum costs the same.
 */
private val ColorSwatchTouchSize = 48.dp

@Composable
fun FilterRow(
    selectedColor: Int?,
    onColorSelect: (Int?) -> Unit,
    allLabels: List<Label>,
    selectedLabelId: Long?,
    onLabelSelect: (Long?) -> Unit,
    sortOrder: NoteSortOrder = NoteSortOrder.MANUAL,
    onSortOrderCycle: () -> Unit = {},
    hasActiveFilters: Boolean = false,
    onClearFilters: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isDarkTheme = isNoteColorDarkTheme()
    val colors = noteColorsForTheme(isDarkTheme).filter { it != Color.Transparent }
    val railShape = RoundedCornerShape(999.dp)
    val allSelected = selectedColor == null
    val filterRowScroll = rememberScrollState()
    val labelRowScroll = rememberScrollState()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .horizontalScroll(filterRowScroll)
                .wheelHorizontalScroll(filterRowScroll)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PrecisionFilterChip(
                selected = false,
                onClick = onSortOrderCycle,
                label = stringResource(sortOrderLabelRes(sortOrder)),
                compact = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = null,
                        modifier = Modifier
                            .size(14.dp)
                            .alpha(0.8f)
                    )
                }
            )
            if (hasActiveFilters) {
                PrecisionFilterChip(
                    selected = true,
                    onClick = onClearFilters,
                    label = stringResource(Res.string.clear_filters_short),
                    compact = true
                )
            }
            Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(railShape)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = Chrome.ChipBorder),
                            shape = railShape
                        )
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.all_colors).uppercase(),
                        style = AppType.chromeLabel,
                        color = if (allSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (allSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = Chrome.SelectedWash)
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable { onColorSelect(null) }
                            .padding(horizontal = 10.dp)
                            .wrapContentHeight(Alignment.CenterVertically)
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(16.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = Chrome.SelectedBorder))
                    )
                    NoteColorSwatch(
                        color = Color.Transparent,
                        isSelected = selectedColor == 0,
                        onClick = { onColorSelect(if (selectedColor == 0) null else 0) },
                        touchSize = ColorSwatchTouchSize,
                        swatchSize = 26.dp,
                        contentDescription = stringResource(Res.string.no_color)
                    )
                    colors.forEach { color ->
                        val colorArgb = color.toArgb()
                        NoteColorSwatch(
                            color = color,
                            isSelected = selectedColor == colorArgb,
                            onClick = {
                                onColorSelect(if (selectedColor == colorArgb) null else colorArgb)
                            },
                            touchSize = ColorSwatchTouchSize,
                            swatchSize = 26.dp,
                            contentDescription = noteColorName(color)
                        )
                    }
                }
        }

        if (allLabels.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .horizontalScroll(labelRowScroll)
                    .wheelHorizontalScroll(labelRowScroll)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PrecisionFilterChip(
                    selected = selectedLabelId == null,
                    onClick = { onLabelSelect(null) },
                    label = stringResource(Res.string.all_labels),
                    compact = true
                )
                allLabels.forEach { label ->
                    PrecisionFilterChip(
                        selected = selectedLabelId == label.id,
                        onClick = { onLabelSelect(if (selectedLabelId == label.id) null else label.id) },
                        label = label.name,
                        compact = true
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
    compact: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
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
        modifier = modifier.heightIn(min = if (compact) 40.dp else 44.dp),
        shape = CircleShape,
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = borderColor,
            selectedBorderColor = borderColor,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp
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
