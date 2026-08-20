package com.aus.notelikeus.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.ui.components.AppSnackbar
import com.aus.notelikeus.ui.components.NoteStaggeredGrid
import com.aus.notelikeus.ui.components.NotesEmptyState
import com.aus.notelikeus.ui.main.components.MainTopAppBar
import com.aus.notelikeus.ui.main.components.TrashBanner
import com.aus.notelikeus.ui.main.components.sortOrderLabelRes
import com.aus.notelikeus.util.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.ui.theme.Elevation
import com.aus.notelikeus.ui.theme.Size

/**
 * The notes list itself: top bar, FAB, empty states, trash banner and the staggered grid.
 * Extracted from MainScreen, which now owns only layout-mode wiring (drawers, panes, dialogs).
 */
/**
 * Comfortable reading measure for the single-column list modes on a wide window.
 *
 * ~720dp puts a 15sp body around 80 characters a line, near the top of the readable range while
 * still looking generous rather than cramped on a large display.
 */
private val SingleColumnMaxWidth = Size.readingMeasure

@Composable
internal fun MainScaffold(
    state: MainState,
    viewModel: MainViewModel,
    onNoteClick: (Long?) -> Unit,
    gridState: LazyStaggeredGridState,
    snackbarHostState: SnackbarHostState,
    showProfileSheet: Boolean,
    onShowProfileSheet: (Boolean) -> Unit,
    onShowDeleteConfirm: (Boolean) -> Unit,
    onShowEmptyTrashConfirm: (Boolean) -> Unit,
    onShowDrawer: () -> Unit,
    listScrolled: Boolean,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    showUndoSnackbar: (String) -> Unit,
    searchFocusRequester: FocusRequester,
    isExpanded: Boolean
) {
    val showFab = state.currentFilter == NoteFilter.ACTIVE && state.selectedNotes.isEmpty()
    val selectedNoteModels = remember(state.notes, state.selectedNotes) {
        state.notes.filter { it.id in state.selectedNotes }
    }
    val selectionAllPinned = remember(selectedNoteModels) {
        selectedNoteModels.isNotEmpty() && selectedNoteModels.all { it.isPinned }
    }
    val visibleNoteIds = remember(state.filteredNotes) {
        state.filteredNotes.mapNotNull { it.id }.toSet()
    }
    val allFilteredSelected = remember(visibleNoteIds, state.selectedNotes) {
        visibleNoteIds.isNotEmpty() && visibleNoteIds.all { it in state.selectedNotes }
    }
    val allowReorder = remember(state.query) { state.query.allowsManualReorder }

    Scaffold(
        containerColor = Color.Transparent, // Parent handles background
        snackbarHost = { AppSnackbar(hostState = snackbarHostState, aboveFab = showFab) },
        topBar = {
            MainTopAppBar(
                searchQuery = state.searchQuery,
                onSearchQueryChange = viewModel::onSearchQueryChange,
                viewMode = state.viewMode,
                onViewModeChange = viewModel::setViewMode,
                selectedCount = state.selectedNotes.size,
                allFilteredSelected = allFilteredSelected,
                onToggleSelectAll = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    viewModel.toggleSelectAll()
                },
                onClearSelection = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    viewModel.clearSelection()
                },
                onDeleteSelected = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onShowDeleteConfirm(true)
                },
                onArchiveSelected = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    viewModel.archiveSelectedNotes()
                    scope.launch {
                        showUndoSnackbar(getString(Res.string.note_archived))
                    }
                },
                onRestoreSelected = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    viewModel.restoreSelectedNotes()
                    scope.launch {
                        snackbarHostState.showSnackbar(getString(Res.string.notes_restored))
                    }
                },
                selectionAllPinned = selectionAllPinned,
                onPinSelected = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    val pin = !selectionAllPinned
                    viewModel.setSelectedNotesPinned(pin)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            getString(if (pin) Res.string.notes_pinned else Res.string.notes_unpinned)
                        )
                    }
                },
                currentFilter = state.currentFilter,
                onMenuClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onShowDrawer()
                },
                onProfileClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onShowProfileSheet(true)
                },
                accountEmail = state.cloudAccount.email,
                selectedColor = state.selectedColor,
                onColorSelect = viewModel::selectColorFilter,
                allLabels = state.allLabels,
                selectedLabelId = state.selectedLabelId,
                onLabelSelect = viewModel::selectLabelFilter,
                sortOrder = state.sortOrder,
                onSortOrderCycle = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    val next = state.sortOrder.next()
                    viewModel.setSortOrder(next)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            getString(
                                Res.string.sorted_by,
                                getString(sortOrderLabelRes(next))
                            )
                        )
                    }
                },
                recentSearches = state.recentSearches,
                onRecentSearchClick = {
                    viewModel.onSearchQueryChange(it)
                    viewModel.addRecentSearch(it)
                },
                onClearRecentSearches = viewModel::clearRecentSearches,
                hasActiveFilters = state.selectedColor != null || state.selectedLabelId != null,
                onClearFilters = viewModel::clearFilters,
                listScrolled = listScrolled,
                searchFocusRequester = searchFocusRequester,
                showMenuIcon = !isExpanded
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = showFab,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                FloatingActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onNoteClick(null)
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = Elevation.card,
                        pressedElevation = Elevation.dragging,
                        hoveredElevation = Elevation.hover,
                        focusedElevation = Elevation.hover
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.add_note))
                }
            }
        }
    ) { paddingValues ->
        val filteredNotes = state.filteredNotes
        val gridBottomPadding = paddingValues.calculateBottomPadding() + if (showFab) 80.dp else Spacing.lg

        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (filteredNotes.isEmpty()) {
            val hasActiveFilters = state.selectedColor != null || state.selectedLabelId != null
            val message: String
            val subtitle: String?
            val showCreate: Boolean
            val showClear: Boolean
            val emptyIcon: ImageVector?
            when {
                state.searchQuery.isNotEmpty() -> {
                    message = stringResource(Res.string.no_matching_notes)
                    subtitle = stringResource(Res.string.empty_search_subtitle)
                    showCreate = false
                    showClear = true
                    emptyIcon = Icons.Outlined.SearchOff
                }
                hasActiveFilters -> {
                    message = stringResource(Res.string.no_filter_matches)
                    subtitle = stringResource(Res.string.empty_filter_subtitle)
                    showCreate = false
                    showClear = true
                    emptyIcon = Icons.Outlined.FilterAltOff
                }
                state.currentFilter == NoteFilter.ARCHIVED -> {
                    message = stringResource(Res.string.no_archived_notes)
                    subtitle = null
                    showCreate = false
                    showClear = false
                    emptyIcon = Icons.Outlined.Archive
                }
                state.currentFilter == NoteFilter.TRASHED -> {
                    message = stringResource(Res.string.no_trashed_notes)
                    subtitle = stringResource(Res.string.empty_trash_subtitle)
                    showCreate = false
                    showClear = false
                    emptyIcon = Icons.Outlined.DeleteOutline
                }
                else -> {
                    message = stringResource(Res.string.empty_notes_hint)
                    subtitle = stringResource(Res.string.empty_notes_subtitle)
                    // FAB already provides create — avoid a second CTA on empty.
                    showCreate = false
                    showClear = false
                    emptyIcon = null
                }
            }
            NotesEmptyState(
                message = message,
                subtitle = subtitle,
                icon = emptyIcon,
                showCreateButton = showCreate,
                showClearFilters = showClear,
                recentSearches = state.recentSearches,
                onRecentSearchClick = {
                    viewModel.onSearchQueryChange(it)
                    viewModel.addRecentSearch(it)
                },
                onCreateClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onNoteClick(null)
                },
                onClearFilters = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    viewModel.clearFilters()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (state.currentFilter == NoteFilter.TRASHED && state.selectedNotes.isEmpty()) {
                    TrashBanner(
                        onEmptyTrash = {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onShowEmptyTrashConfirm(true)
                        }
                    )
                }
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    // Desktop owns the full window now, so mirror the web grid: ~300dp
                    // minimum cards and 2–4 columns depending on available width.
                    val adaptiveColumns =
                        if (AppConfig.isDesktop && state.viewMode == NoteViewMode.GRID_2) {
                            (maxWidth / Size.gridMinCardWidth).toInt().coerceIn(2, 4)
                        } else {
                            null
                        }
                    val resolvedColumns = adaptiveColumns
                        ?: if (isExpanded && state.viewMode.columns > 2) 2 else state.viewMode.columns

                    NoteStaggeredGrid(
                        notes = filteredNotes,
                        selectedNotes = state.selectedNotes,
                        searchQuery = state.searchQuery,
                        listRevision = state.listRevision,
                        gridState = gridState,
                        enableArchiveSwipe = state.currentFilter == NoteFilter.ACTIVE,
                        enableSwipe = state.selectedNotes.isEmpty(),
                        allowReorder = allowReorder,
                        onNoteClick = { note ->
                            if (state.selectedNotes.isNotEmpty()) {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                viewModel.toggleNoteSelection(note.id!!)
                            } else {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onNoteClick(note.id)
                            }
                        },
                        onNoteLongClick = { note ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.toggleNoteSelection(note.id!!)
                        },
                        onSwipeToArchive = { note ->
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            viewModel.archiveNote(note)
                            scope.launch {
                                showUndoSnackbar(getString(Res.string.note_archived))
                            }
                        },
                        onSwipeToTrash = { note ->
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            viewModel.trashNote(note)
                            scope.launch {
                                val message = if (state.currentFilter == NoteFilter.TRASHED) {
                                    getString(Res.string.note_deleted)
                                } else {
                                    getString(Res.string.note_trashed)
                                }
                                showUndoSnackbar(message)
                            }
                        },
                        onLabelClick = { labelId ->
                            viewModel.selectLabelFilter(labelId)
                        },
                        onMoveNote = viewModel::previewMoveNote,
                        onReorderComplete = viewModel::commitNoteOrder,
                        // In two-pane mode the list shares the window with the editor, so the
                        // wider grid choices leave cards too narrow to read. Cap the list pane
                        // at 2. Desktop has no detail pane, so it uses adaptive columns above.
                        columns = resolvedColumns,
                        compact = state.viewMode.compact,
                        listStyle = state.viewMode == NoteViewMode.LIST,
                        // The grid modes already spend width on more columns. The single-column
                        // modes had nothing to spend it on, so on a desktop window a note's body
                        // ran the full ~1140dp — several times a comfortable reading measure, and
                        // the widest thing on screen by far. Capping and centring them keeps long
                        // notes readable and makes the wide window look deliberate rather than
                        // stretched. It is a maximum, so nothing changes on a phone.
                        modifier = if (resolvedColumns == 1) {
                            Modifier
                                .fillMaxHeight()
                                .widthIn(max = SingleColumnMaxWidth)
                                .align(Alignment.TopCenter)
                        } else {
                            Modifier.fillMaxSize()
                        },
                        contentPadding = PaddingValues(
                            top = Spacing.md,
                            start = Spacing.md,
                            end = Spacing.md,
                            bottom = gridBottomPadding
                        )
                    )
                }
            }
        }
    }
}
