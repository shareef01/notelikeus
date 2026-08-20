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
import com.aus.notelikeus.ui.components.AppFilterChip
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.ui.theme.Size
import com.aus.notelikeus.ui.theme.Radius

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
private val ColorSwatchTouchSize = Size.touchTarget

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
    val railShape = RoundedCornerShape(Radius.pill)
    val allSelected = selectedColor == null
    val filterRowScroll = rememberScrollState()
    val labelRowScroll = rememberScrollState()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Size.chipHeight)
                .horizontalScroll(filterRowScroll)
                .wheelHorizontalScroll(filterRowScroll)
                .padding(horizontal = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            AppFilterChip(
                selected = false,
                onClick = onSortOrderCycle,
                label = stringResource(sortOrderLabelRes(sortOrder)),
                compact = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = null,
                        modifier = Modifier
                            .size(Size.iconTiny)
                            .alpha(0.8f)
                    )
                }
            )
            if (hasActiveFilters) {
                AppFilterChip(
                    selected = true,
                    onClick = onClearFilters,
                    label = stringResource(Res.string.clear_filters_short),
                    compact = true
                )
            }
            Row(
                    modifier = Modifier
                        .height(Size.controlHeight)
                        .clip(railShape)
                        .border(
                            width = Spacing.hairline,
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
                            .clip(RoundedCornerShape(Radius.pill))
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
                            .width(Spacing.hairline)
                            .height(Size.iconSmall)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = Chrome.SelectedBorder))
                    )
                    NoteColorSwatch(
                        color = Color.Transparent,
                        isSelected = selectedColor == 0,
                        onClick = { onColorSelect(if (selectedColor == 0) null else 0) },
                        touchSize = ColorSwatchTouchSize,
                        swatchSize = Size.swatch,
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
                            swatchSize = Size.swatch,
                            contentDescription = noteColorName(color)
                        )
                    }
                }
        }

        if (allLabels.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Size.chipHeightCompact)
                    .horizontalScroll(labelRowScroll)
                    .wheelHorizontalScroll(labelRowScroll)
                    .padding(horizontal = Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                AppFilterChip(
                    selected = selectedLabelId == null,
                    onClick = { onLabelSelect(null) },
                    label = stringResource(Res.string.all_labels),
                    compact = true
                )
                allLabels.forEach { label ->
                    AppFilterChip(
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
