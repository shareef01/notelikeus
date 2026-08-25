package com.aus.notelikeus.ui.components

// DateUtils removed
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.util.DateUtils
import com.aus.notelikeus.ui.theme.Spacing
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun NoteStaggeredGrid(
    notes: List<Note>,
    selectedNotes: Set<Long>,
    onNoteClick: (Note) -> Unit,
    onNoteLongClick: (Note) -> Unit,
    onSwipeToArchive: (Note) -> Unit,
    onSwipeToTrash: (Note) -> Unit,
    onMoveNote: (Int, Int) -> Unit,
    onReorderComplete: () -> Unit = {},
    onLabelClick: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
    searchQuery: String = "",
    listRevision: Int = 0,
    enableArchiveSwipe: Boolean = true,
    enableSwipe: Boolean = true,
    allowReorder: Boolean = true,
    /**
     * Called when someone drags a handle that an automatic sort has disabled.
     *
     * Non-null turns the handle back on as an *offer* rather than a control: the drag does not
     * move anything, it explains why it cannot and what would fix it. Null keeps the handle
     * hidden, which is the right answer when nothing the user can tap would help.
     */
    onReorderBlocked: (() -> Unit)? = null,
    columns: Int = 2,
    compact: Boolean = false,
    listStyle: Boolean = false,
    gridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    contentPadding: PaddingValues = PaddingValues(Spacing.sm)
) {
    val haptic = LocalHapticFeedback.current
    val pinnedSectionLabel = stringResource(Res.string.section_pinned)
    val todaySectionLabel = stringResource(Res.string.section_today)
    val yesterdaySectionLabel = stringResource(Res.string.section_yesterday)
    val reorderThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    val moveUpLabel = stringResource(Res.string.cd_move_up)
    val moveDownLabel = stringResource(Res.string.cd_move_down)
    val reorderBlockedLabel = stringResource(Res.string.cd_reorder_blocked)
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // Three states, not two. The layout either can show a handle or cannot; on top of that the
    // query either permits reordering or has an explanation for why it does not.
    val layoutAllowsReorder = columns == 1 && selectedNotes.isEmpty() && !compact
    val canReorder = layoutAllowsReorder && allowReorder
    val offerReorder = layoutAllowsReorder && !allowReorder && onReorderBlocked != null
    val swipeEnabled = enableSwipe && selectedNotes.isEmpty()
    val itemSpacing = Spacing.lg

    fun getDateHeader(timestamp: Long): String {
        return when {
            com.aus.notelikeus.util.DateUtils.isToday(timestamp) -> todaySectionLabel
            com.aus.notelikeus.util.DateUtils.isToday(timestamp + com.aus.notelikeus.util.DateUtils.DAY_IN_MILLIS) -> yesterdaySectionLabel
            else -> com.aus.notelikeus.util.DateUtils.formatDateTime(timestamp)
        }
    }

    LazyVerticalStaggeredGrid(
        state = gridState,
        columns = StaggeredGridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        verticalItemSpacing = itemSpacing
    ) {
        notes.forEachIndexed { index, note ->
            val prevNote = if (index > 0) notes[index - 1] else null
            
            // 1. Pinned Header
            if (note.isPinned && index == 0) {
                item(key = "header-pinned", span = StaggeredGridItemSpan.FullLine) {
                    NoteSectionHeader(title = pinnedSectionLabel)
                }
            }

            // 2. Date Section Headers (for unpinned notes)
            if (!note.isPinned) {
                val currentHeader = getDateHeader(note.timestamp)
                val prevHeader = prevNote?.let { if (it.isPinned) null else getDateHeader(it.timestamp) }

                if (currentHeader != prevHeader) {
                    item(key = "header-date-$index", span = StaggeredGridItemSpan.FullLine) {
                        NoteSectionHeader(title = currentHeader)
                    }
                }
            }

            item(
                key = note.id ?: note.timestamp,
                span = StaggeredGridItemSpan.SingleLane
            ) {
                val isBeingDragged = canReorder && draggingIndex == index
                val dragScale by animateFloatAsState(
                    targetValue = if (isBeingDragged) 1.03f else 1f,
                    label = "dragScale"
                )
                val dragElevation by animateDpAsState(
                    targetValue = if (isBeingDragged) Spacing.xs else Spacing.none,
                    label = "dragElevation"
                )
                val itemModifier = Modifier
                    .animateItem()
                    .scale(dragScale)
                    .shadow(dragElevation, MaterialTheme.shapes.large)
                if (columns == 1) {
                    val reorderDragModifier = if (canReorder) {
                        Modifier.pointerInput(index, notes.size) {
                            detectDragGestures(
                                onDragStart = {
                                    draggingIndex = index
                                    dragOffset = 0f
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { _, dragAmount ->
                                    if (draggingIndex != index) return@detectDragGestures
                                    dragOffset += dragAmount.y
                                    when {
                                        dragOffset > reorderThresholdPx && draggingIndex < notes.lastIndex -> {
                                            onMoveNote(draggingIndex, draggingIndex + 1)
                                            draggingIndex++
                                            dragOffset = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        dragOffset < -reorderThresholdPx && draggingIndex > 0 -> {
                                            onMoveNote(draggingIndex, draggingIndex - 1)
                                            draggingIndex--
                                            dragOffset = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    draggingIndex = -1
                                    dragOffset = 0f
                                    onReorderComplete()
                                },
                                onDragCancel = {
                                    draggingIndex = -1
                                    dragOffset = 0f
                                    onReorderComplete()
                                }
                            )
                        }
                            // A screen reader cannot produce a drag, so without these the handle
                            // announces a control its user has no way to operate. Same calls the
                            // drag makes, one step at a time; onReorderComplete persists.
                            .semantics {
                                customActions = buildList {
                                    if (index > 0) {
                                        add(
                                            CustomAccessibilityAction(moveUpLabel) {
                                                onMoveNote(index, index - 1)
                                                onReorderComplete()
                                                true
                                            }
                                        )
                                    }
                                    if (index < notes.lastIndex) {
                                        add(
                                            CustomAccessibilityAction(moveDownLabel) {
                                                onMoveNote(index, index + 1)
                                                onReorderComplete()
                                                true
                                            }
                                        )
                                    }
                                }
                            }
                    } else if (offerReorder) {
                        // Drag start only. The gesture is consumed and nothing moves, so the list
                        // stays exactly where it was while the dialog asks.
                        Modifier.pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { onReorderBlocked?.invoke() },
                                onDrag = { change, _ -> change.consume() }
                            )
                        }
                            // A custom action rather than onClick: the card's own
                            // combinedClickable merges these descendants, and its onClick (open
                            // the note) would win. Custom actions concatenate instead, so this
                            // lands in the card's actions menu next to the others.
                            .semantics {
                                contentDescription = reorderBlockedLabel
                                customActions = listOf(
                                    CustomAccessibilityAction(reorderBlockedLabel) {
                                        onReorderBlocked?.invoke()
                                        true
                                    }
                                )
                            }
                    } else {
                        Modifier
                    }

                    noteListCard(
                        note = note,
                        selectedNotes = selectedNotes,
                        searchQuery = searchQuery,
                        listRevision = listRevision,
                        enableArchiveSwipe = enableArchiveSwipe,
                        enableSwipe = swipeEnabled,
                        compact = compact,
                        listStyle = listStyle,
                        haptic = haptic,
                        onNoteClick = onNoteClick,
                        onNoteLongClick = onNoteLongClick,
                        onSwipeToArchive = onSwipeToArchive,
                        onSwipeToTrash = onSwipeToTrash,
                        onLabelClick = onLabelClick,
                        showReorderHandle = canReorder || offerReorder,
                        reorderDragModifier = reorderDragModifier,
                        modifier = itemModifier.fillMaxSize()
                    )
                } else {
                    SwipeableNoteCard(
                        note = note,
                        isSelected = selectedNotes.contains(note.id),
                        searchQuery = searchQuery,
                        onNoteClick = { onNoteClick(note) },
                        onNoteLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onNoteLongClick(note)
                        },
                        onSwipeToArchive = { onSwipeToArchive(note) },
                        onSwipeToTrash = { onSwipeToTrash(note) },
                        onLabelClick = onLabelClick,
                        listRevision = listRevision,
                        enableArchiveSwipe = enableArchiveSwipe,
                        enableSwipe = swipeEnabled,
                        compact = compact,
                        listStyle = listStyle,
                        modifier = itemModifier
                    )
                }
            }
        }
    }
}

@Composable
private fun noteListCard(
    note: Note,
    selectedNotes: Set<Long>,
    searchQuery: String,
    listRevision: Int,
    enableArchiveSwipe: Boolean,
    enableSwipe: Boolean,
    compact: Boolean,
    listStyle: Boolean,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onNoteClick: (Note) -> Unit,
    onNoteLongClick: (Note) -> Unit,
    onSwipeToArchive: (Note) -> Unit,
    onSwipeToTrash: (Note) -> Unit,
    onLabelClick: ((Long) -> Unit)?,
    showReorderHandle: Boolean = false,
    reorderDragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    SwipeableNoteCard(
        note = note,
        isSelected = selectedNotes.contains(note.id),
        searchQuery = searchQuery,
        onNoteClick = {
            if (selectedNotes.isNotEmpty()) {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            }
            onNoteClick(note)
        },
        onNoteLongClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onNoteLongClick(note)
        },
        onSwipeToArchive = { onSwipeToArchive(note) },
        onSwipeToTrash = { onSwipeToTrash(note) },
        onLabelClick = onLabelClick,
        listRevision = listRevision,
        enableArchiveSwipe = enableArchiveSwipe,
        enableSwipe = enableSwipe,
        compact = compact,
        listStyle = listStyle,
        showReorderHandle = showReorderHandle,
        reorderDragModifier = reorderDragModifier,
        modifier = modifier
    )
}
