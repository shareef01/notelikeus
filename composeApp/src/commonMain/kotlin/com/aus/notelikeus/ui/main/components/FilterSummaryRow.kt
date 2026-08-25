package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.NoteQuery
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.ui.components.AppFilterChip
import com.aus.notelikeus.ui.theme.Chrome
import com.aus.notelikeus.ui.theme.Size
import com.aus.notelikeus.ui.theme.Spacing
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.cd_remove_filter
import notelikeus.composeapp.generated.resources.clear_all
import notelikeus.composeapp.generated.resources.filters
import notelikeus.composeapp.generated.resources.filters_with_count
import org.jetbrains.compose.resources.stringResource

/**
 * The filter chrome, in one line.
 *
 * It was two rows: a sort chip, an "ALL COLORS" rail with a horizontally-scrolling strip of nine
 * unlabeled colour dots, then a second row of label chips — around a tenth of a phone's viewport,
 * held above the notes before a single one was visible, and growing with every label the user
 * created.
 *
 * What replaces it says what is *on* rather than offering everything that could be: a Filters
 * button carrying a count, the sort control, the view control, then a chip per active filter, each
 * removable on its own, ending in Clear all. Everything the two rows used to expose lives in the
 * sheet the Filters button opens, where a colour can have its name next to it.
 */
@Composable
fun FilterSummaryRow(
    query: NoteQuery,
    allLabels: List<Label>,
    onOpenFilters: () -> Unit,
    onQueryChange: ((NoteQuery) -> NoteQuery) -> Unit,
    onClearFilters: () -> Unit,
    onViewModeChange: (NoteViewMode) -> Unit,
    onSortOrderCycle: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState()
) {
    val filters = activeFilters(query, allLabels)
    val removeLabel = stringResource(Res.string.cd_remove_filter, "")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Size.chipHeightCompact + Spacing.sm)
            .horizontalScroll(scrollState)
            .wheelHorizontalScroll(scrollState)
            .padding(horizontal = Spacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        AppFilterChip(
            selected = filters.isNotEmpty(),
            onClick = onOpenFilters,
            label = if (filters.isEmpty()) {
                stringResource(Res.string.filters)
            } else {
                stringResource(Res.string.filters_with_count, filters.size)
            },
            compact = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = null,
                    modifier = Modifier.size(Size.iconTiny)
                )
            }
        )

        AppFilterChip(
            selected = false,
            onClick = onSortOrderCycle,
            label = stringResource(sortOrderLabelRes(query.sort)),
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

        ViewModeMenu(viewMode = query.view, onViewModeChange = onViewModeChange)

        if (filters.isNotEmpty()) {
            // Separates the controls from what is currently on, so the row reads as two groups
            // rather than one long strip of chips.
            Box(
                modifier = Modifier
                    .width(Spacing.hairline)
                    .height(Size.iconLarge)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = Chrome.Divider))
            )
            filters.forEach { filter ->
                AppFilterChip(
                    selected = true,
                    onClick = { onQueryChange(filter.remove) },
                    label = filter.label,
                    compact = true,
                    // The chip removes the filter, so it is described by what tapping it does
                    // rather than by the value it shows -- otherwise a screen reader announces
                    // "Work, selected" and gives no hint that activating it clears anything.
                    modifier = Modifier.semantics {
                        contentDescription = removeLabel.trim() + " " + filter.label
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(Size.iconTiny)
                        )
                    }
                )
            }
            AppFilterChip(
                selected = false,
                onClick = onClearFilters,
                label = stringResource(Res.string.clear_all),
                compact = true
            )
        }
    }
}
