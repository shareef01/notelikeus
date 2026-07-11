package com.aus.notelikeus.ui.main.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aus.notelikeus.R
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.ui.main.NoteFilter
import com.aus.notelikeus.ui.theme.BrandMarkIcon

private val TopBarRowHeight = 56.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopAppBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    viewMode: NoteViewMode,
    onViewModeChange: (NoteViewMode) -> Unit,
    selectedCount: Int,
    allFilteredSelected: Boolean = false,
    onToggleSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onArchiveSelected: () -> Unit,
    onRestoreSelected: () -> Unit,
    selectionAllPinned: Boolean = false,
    onPinSelected: () -> Unit = {},
    currentFilter: NoteFilter,
    onMenuClick: () -> Unit,
    onProfileClick: () -> Unit,
    selectedColor: Int?,
    onColorSelect: (Int?) -> Unit,
    allLabels: List<Label>,
    selectedLabelId: Long?,
    onLabelSelect: (Long?) -> Unit,
    sortOrder: NoteSortOrder = NoteSortOrder.MANUAL,
    onSortOrderChange: (NoteSortOrder) -> Unit = {},
    recentSearches: List<String> = emptyList(),
    onRecentSearchClick: (String) -> Unit = {},
    onClearRecentSearches: () -> Unit = {},
    hasActiveFilters: Boolean = false,
    onClearFilters: () -> Unit = {},
    listScrolled: Boolean = false,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var isSearchFocused by remember { mutableStateOf(false) }

    val settingsContentDescription = stringResource(R.string.cd_open_settings)
    val searchPlaceholder = when (currentFilter) {
        NoteFilter.ACTIVE -> stringResource(R.string.search_notes)
        NoteFilter.ARCHIVED -> stringResource(R.string.search_archive)
        NoteFilter.TRASHED -> stringResource(R.string.search_trash)
    }
    val headerColor = MaterialTheme.colorScheme.surface

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = headerColor,
        tonalElevation = if (listScrolled) 2.dp else 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            AnimatedContent(
                targetState = selectedCount > 0,
                transitionSpec = {
                    (slideInVertically { height -> height } + fadeIn())
                        .togetherWith(slideOutVertically { height -> -height } + fadeOut())
                },
                label = "topbar"
            ) { isSelectionMode ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TopBarRowHeight)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = CircleShape,
                    color = if (isSelectionMode) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                    },
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    if (isSelectionMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onClearSelection()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_clear_selection))
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.selected_count, selectedCount),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                )
                                TextButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        onToggleSelectAll()
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (allFilteredSelected) R.string.deselect_all else R.string.select_all
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            if (currentFilter == NoteFilter.ACTIVE) {
                                IconButton(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    onPinSelected()
                                }) {
                                    Icon(
                                        if (selectionAllPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                        contentDescription = stringResource(
                                            if (selectionAllPinned) R.string.cd_unpin_notes else R.string.cd_pin_notes
                                        )
                                    )
                                }
                            }
                            if (currentFilter != NoteFilter.ACTIVE) {
                                IconButton(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    onRestoreSelected()
                                }) {
                                    Icon(Icons.Default.Restore, contentDescription = stringResource(R.string.cd_restore))
                                }
                            }
                            if (currentFilter == NoteFilter.ACTIVE) {
                                IconButton(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    onArchiveSelected()
                                }) {
                                    Icon(Icons.Default.Archive, contentDescription = stringResource(R.string.cd_archive))
                                }
                            }
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onDeleteSelected()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete))
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onMenuClick()
                            }) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = stringResource(R.string.cd_menu),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            BasicTextField(
                                value = searchQuery,
                                onValueChange = onSearchQueryChange,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .weight(1f)
                                    .onFocusChanged { isSearchFocused = it.isFocused }
                                    .semantics { contentDescription = searchPlaceholder },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    if (searchQuery.isNotBlank()) {
                                        onRecentSearchClick(searchQuery)
                                    }
                                    focusManager.clearFocus()
                                }),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                text = searchPlaceholder,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )

                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    onSearchQueryChange("")
                                }) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = stringResource(R.string.cd_clear_search),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            ViewModeMenu(
                                viewMode = viewMode,
                                onViewModeChange = {
                                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    onViewModeChange(it)
                                }
                            )

                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .semantics { contentDescription = settingsContentDescription }
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        onProfileClick()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                BrandMarkIcon(
                                    size = 40.dp,
                                    backgroundColor = MaterialTheme.colorScheme.onSurface,
                                    stripeColor = MaterialTheme.colorScheme.surface
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = selectedCount == 0 && isSearchFocused && recentSearches.isNotEmpty() && searchQuery.isEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                RecentSearchRow(
                    searches = recentSearches,
                    onSearchClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onRecentSearchClick(it)
                        focusManager.clearFocus()
                    },
                    onClearAll = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onClearRecentSearches()
                    }
                )
            }

            AnimatedVisibility(
                visible = selectedCount == 0,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                FilterRow(
                    selectedColor = selectedColor,
                    onColorSelect = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onColorSelect(it)
                    },
                    allLabels = allLabels,
                    selectedLabelId = selectedLabelId,
                    onLabelSelect = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onLabelSelect(it)
                    },
                    sortOrder = sortOrder,
                    onSortOrderChange = onSortOrderChange,
                    hasActiveFilters = hasActiveFilters,
                    onClearFilters = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onClearFilters()
                    }
                )
            }

            if (listScrolled) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    thickness = 1.dp
                )
            }
        }
    }
}

@Composable
private fun RecentSearchRow(
    searches: List<String>,
    onSearchClick: (String) -> Unit,
    onClearAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 16.dp, end = 8.dp).size(18.dp)
        )
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(searches) { query ->
                PrecisionFilterChip(
                    selected = false,
                    onClick = { onSearchClick(query) },
                    label = query
                )
            }
        }
        TextButton(
            onClick = onClearAll,
            modifier = Modifier.padding(end = 8.dp)
        ) {
            Text(
                stringResource(R.string.clear_recent_searches),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
