package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.aus.notelikeus.domain.model.DateField
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.LabelMatch
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.NoteFlag
import com.aus.notelikeus.domain.model.NoteQuery
import com.aus.notelikeus.domain.model.NoteScope
import com.aus.notelikeus.ui.components.AppFilterChip
import com.aus.notelikeus.ui.theme.AppType
import com.aus.notelikeus.ui.theme.Chrome
import com.aus.notelikeus.ui.theme.Radius
import com.aus.notelikeus.ui.theme.Size
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.ui.theme.getContentColor
import com.aus.notelikeus.ui.theme.isNoteColorDarkTheme
import com.aus.notelikeus.ui.theme.noteColorName
import com.aus.notelikeus.ui.theme.noteColorsForTheme
import com.aus.notelikeus.util.DateUtils
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.clear_all
import notelikeus.composeapp.generated.resources.filter_label_search
import notelikeus.composeapp.generated.resources.filter_labels_all
import notelikeus.composeapp.generated.resources.filter_labels_any
import notelikeus.composeapp.generated.resources.filter_section_colors
import notelikeus.composeapp.generated.resources.filter_section_date
import notelikeus.composeapp.generated.resources.filter_section_labels
import notelikeus.composeapp.generated.resources.filter_section_scope
import notelikeus.composeapp.generated.resources.filter_section_status
import notelikeus.composeapp.generated.resources.filters
import notelikeus.composeapp.generated.resources.no_color
import notelikeus.composeapp.generated.resources.showing_all
import notelikeus.composeapp.generated.resources.showing_count
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Every filter in one place, with the count each choice would produce.
 *
 * The two rows this replaces could only ever offer colour and label, because that is all that fits
 * across a phone. A sheet has room to say what a colour is *called* and to expose the dimensions
 * the query model has had all along.
 *
 * Two things here matter more than the controls. The **live count** lets someone see that a filter
 * is about to empty the screen before they tap it, and **options that would return nothing are
 * disabled**, so the sheet never invites a dead end. Both come from running the real matcher over
 * the loaded notes: a count that disagreed with the list would be worse than no count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltersSheet(
    query: NoteQuery,
    notes: List<Note>,
    allLabels: List<Label>,
    onQueryChange: ((NoteQuery) -> NoteQuery) -> Unit,
    onClearFilters: () -> Unit,
    onDismiss: () -> Unit
) {
    val now = remember { DateUtils.currentTimeMillis() }
    val isDark = isNoteColorDarkTheme()

    // One matcher pass per candidate option, memoised on the query and the loaded notes so the
    // sheet does not recount on every recomposition.
    //
    // Only the loaded scope is counted. Switching scope resubscribes to a different DAO flow, and
    // previewing a list this screen has not loaded would mean loading all of them to answer a
    // question nobody has asked yet.
    val counts = remember(query, notes, now) { FilterCounts(query, notes, now) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xxl)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Res.string.filters),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (query.hasActiveFilters) {
                        pluralStringResource(
                            Res.plurals.showing_count,
                            counts.current,
                            counts.current,
                            notes.size
                        )
                    } else {
                        pluralStringResource(Res.plurals.showing_all, notes.size, notes.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FilterSection(stringResource(Res.string.filter_section_scope)) {
                NoteScope.entries.forEach { scope ->
                    AppFilterChip(
                        selected = query.scope == scope,
                        onClick = { onQueryChange { it.copy(scope = scope) } },
                        label = stringResource(scopeLabelRes(scope)),
                        compact = true
                    )
                }
            }

            FilterSection(stringResource(Res.string.filter_section_colors)) {
                noteColorsForTheme(isDark).forEachIndexed { index, color ->
                    val argb = if (color == Color.Transparent) 0 else color.toArgb()
                    val selected = argb in query.colors
                    ColorFilterChip(
                        color = color,
                        name = if (index == 0) {
                            stringResource(Res.string.no_color)
                        } else {
                            noteColorName(color)
                        },
                        selected = selected,
                        // A selected option always stays tappable, or there would be no way to
                        // undo a choice that emptied the list.
                        enabled = selected || counts.forColor(argb) > 0,
                        onClick = { onQueryChange { it.toggleColor(argb) } }
                    )
                }
            }

            if (allLabels.isNotEmpty()) {
                LabelFilterSection(query, allLabels, counts, onQueryChange)
            }

            FilterSection(stringResource(Res.string.filter_section_status)) {
                NoteFlag.entries.forEach { flag ->
                    val selected = flag in query.flags
                    AppFilterChip(
                        selected = selected,
                        onClick = { onQueryChange { it.toggleFlag(flag) } },
                        label = stringResource(flagLabelRes(flag)),
                        enabled = selected || counts.forFlag(flag) > 0,
                        compact = true
                    )
                }
            }

            FilterSection(stringResource(Res.string.filter_section_date)) {
                DateField.entries.forEach { field ->
                    AppFilterChip(
                        selected = query.dateField == field,
                        onClick = { onQueryChange { it.copy(dateField = field) } },
                        label = stringResource(dateFieldLabelRes(field)),
                        compact = true
                    )
                }
            }
            FilterSection(label = null) {
                val todayStart = remember(now) { DateUtils.startOfDay(now) }
                DatePreset.entries.forEach { preset ->
                    val range = preset.range(todayStart)
                    AppFilterChip(
                        selected = query.dateRange == range,
                        onClick = { onQueryChange { it.copy(dateRange = range) } },
                        label = stringResource(preset.labelRes),
                        compact = true
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onClearFilters, enabled = query.hasActiveFilters) {
                    Text(stringResource(Res.string.clear_all))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(label: String?, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.md)) {
        if (label != null) {
            Text(
                text = label.uppercase(),
                style = AppType.chromeLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.sm)
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            content()
        }
    }
}

/**
 * Labels, searchable, most-used first.
 *
 * The old label row listed every label in creation order and scrolled sideways forever. Ordering
 * by how many of the visible notes carry each one puts the useful ones first without the user
 * having to say so, and the search field means a long list stays reachable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelFilterSection(
    query: NoteQuery,
    allLabels: List<Label>,
    counts: FilterCounts,
    onQueryChange: ((NoteQuery) -> NoteQuery) -> Unit
) {
    var search by remember { mutableStateOf("") }

    val ordered = remember(allLabels, query, search) {
        allLabels
            .filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }
            .sortedWith(
                compareByDescending<Label> { it.id != null && it.id in query.labels }
                    .thenByDescending { it.id?.let(counts::forLabel) ?: 0 }
                    .thenBy { it.name.lowercase() }
            )
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.filter_section_labels).uppercase(),
                style = AppType.chromeLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            // ANY/ALL only means something once two labels are chosen, so it appears then.
            if (query.labels.size > 1) {
                AppFilterChip(
                    selected = query.labelMatch == LabelMatch.ALL,
                    onClick = {
                        onQueryChange {
                            it.copy(
                                labelMatch = if (it.labelMatch == LabelMatch.ALL) {
                                    LabelMatch.ANY
                                } else {
                                    LabelMatch.ALL
                                }
                            )
                        }
                    },
                    label = stringResource(
                        if (query.labelMatch == LabelMatch.ALL) {
                            Res.string.filter_labels_all
                        } else {
                            Res.string.filter_labels_any
                        }
                    ),
                    compact = true
                )
            }
        }

        if (allLabels.size > LABEL_SEARCH_THRESHOLD) {
            LabelSearchField(value = search, onValueChange = { search = it })
        }

        Spacer(modifier = Modifier.padding(top = Spacing.sm))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            ordered.forEach { label ->
                val id = label.id ?: return@forEach
                val selected = id in query.labels
                AppFilterChip(
                    selected = selected,
                    onClick = { onQueryChange { it.toggleLabel(id) } },
                    label = label.name,
                    enabled = selected || counts.forLabel(id) > 0,
                    compact = true
                )
            }
        }
    }
}

/** Below this many labels a search field is more chrome than help. */
private const val LABEL_SEARCH_THRESHOLD = 8

@Composable
private fun LabelSearchField(value: String, onValueChange: (String) -> Unit) {
    val hint = stringResource(Res.string.filter_label_search)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm)
            .heightIn(min = Size.chipHeightCompact)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                width = Spacing.hairline,
                color = MaterialTheme.colorScheme.outline.copy(alpha = Chrome.ChipBorder),
                shape = CircleShape
            )
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(Size.iconMedium),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = hint }
            )
        }
    }
}

/**
 * A colour with its name next to it.
 *
 * The old picker was a strip of bare circles: nothing said which one was "teal", the crossed-out
 * one meant "no colour" only if you guessed, and a screen reader had eight identical buttons.
 * Naming them is the entire point of moving colour into a sheet, where there is room for words.
 */
@Composable
private fun ColorFilterChip(
    color: Color,
    name: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val isNoColor = color == Color.Transparent
    val swatch = if (isNoColor) MaterialTheme.colorScheme.surfaceVariant else color
    val alpha = if (enabled) 1f else 0.38f

    Row(
        modifier = Modifier
            .heightIn(min = Size.chipHeightCompact)
            .clip(MaterialTheme.shapes.large)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = Chrome.SelectedWash)
                } else {
                    Color.Transparent
                }
            )
            .border(
                width = Spacing.hairline,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = Chrome.SelectedBorder)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = Chrome.ChipBorder)
                },
                shape = MaterialTheme.shapes.large
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .semantics {
                contentDescription = name
                this.selected = selected
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .size(Size.iconMedium)
                .clip(CircleShape)
                .background(swatch.copy(alpha = swatch.alpha * alpha))
                .border(
                    width = Spacing.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = Chrome.ChipBorder),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                selected -> Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(Size.iconTiny),
                    tint = if (isNoColor) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        color.getContentColor()
                    }
                )
                isNoColor -> Icon(
                    imageVector = Icons.Default.Block,
                    contentDescription = null,
                    modifier = Modifier.size(Size.iconTiny),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            }
        )
    }
}
