package com.aus.notelikeus.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.R
import com.aus.notelikeus.domain.model.Note

private sealed interface StaggeredGridEntry {
    val key: String

    data class Header(override val key: String, val title: String) : StaggeredGridEntry
    data class NoteItem(override val key: String, val note: Note, val index: Int) : StaggeredGridEntry
}

private fun noteStableKey(note: Note): String =
    note.id?.let { "note-$it" } ?: "note-cloud-${note.cloudId}"

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
    columns: Int = 2,
    compact: Boolean = false,
    gridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    contentPadding: PaddingValues = PaddingValues(16.dp)
) {
    val haptic = LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val pinnedSectionLabel = stringResource(R.string.section_pinned)
    val todaySectionLabel = stringResource(R.string.section_today)
    val yesterdaySectionLabel = stringResource(R.string.section_yesterday)
    val reorderThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val canReorder = columns == 1 && selectedNotes.isEmpty() && !compact && allowReorder
    val swipeEnabled = enableSwipe && selectedNotes.isEmpty()
    val itemSpacing = 12.dp // Fixed Arrangement Space

    val onNoteClickState by rememberUpdatedState(onNoteClick)
    val onNoteLongClickState by rememberUpdatedState(onNoteLongClick)
    val onSwipeToArchiveState by rememberUpdatedState(onSwipeToArchive)
    val onSwipeToTrashState by rememberUpdatedState(onSwipeToTrash)
    val onMoveNoteState by rememberUpdatedState(onMoveNote)
    val onReorderCompleteState by rememberUpdatedState(onReorderComplete)
    val onLabelClickState by rememberUpdatedState(onLabelClick)

    fun formatDateHeader(timestamp: Long): String {
        return when {
            DateUtils.isToday(timestamp) -> todaySectionLabel
            DateUtils.isToday(timestamp + DateUtils.DAY_IN_MILLIS) -> yesterdaySectionLabel
            else -> DateUtils.formatDateTime(
                context,
                timestamp,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH
            )
        }
    }

    val gridEntries = remember(
        notes,
        pinnedSectionLabel,
        todaySectionLabel,
        yesterdaySectionLabel,
        context,
    ) {
        buildList {
            notes.forEachIndexed { index, note ->
                val prevNote = if (index > 0) notes[index - 1] else null

                if (note.isPinned && index == 0) {
                    add(StaggeredGridEntry.Header(key = "header-pinned", title = pinnedSectionLabel))
                }

                if (!note.isPinned) {
                    val currentHeader = formatDateHeader(note.timestamp)
                    val prevHeader = prevNote?.let { if (it.isPinned) null else formatDateHeader(it.timestamp) }
                    if (currentHeader != prevHeader) {
                        add(
                            StaggeredGridEntry.Header(
                                key = "header-date-$currentHeader-${note.timestamp}",
                                title = currentHeader,
                            )
                        )
                    }
                }

                add(
                    StaggeredGridEntry.NoteItem(
                        key = noteStableKey(note),
                        note = note,
                        index = index,
                    )
                )
            }
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
        gridEntries.forEach { entry ->
            when (entry) {
                is StaggeredGridEntry.Header -> {
                    item(key = entry.key, span = StaggeredGridItemSpan.FullLine) {
                        NoteSectionHeader(title = entry.title)
                    }
                }

                is StaggeredGridEntry.NoteItem -> {
                    val note = entry.note
                    val index = entry.index
                    item(
                        key = entry.key,
                        span = StaggeredGridItemSpan.SingleLane,
                        contentType = "note",
                    ) {
                        val itemModifier = Modifier.animateItem()
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
                                                    onMoveNoteState(draggingIndex, draggingIndex + 1)
                                                    draggingIndex++
                                                    dragOffset = 0f
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                                dragOffset < -reorderThresholdPx && draggingIndex > 0 -> {
                                                    onMoveNoteState(draggingIndex, draggingIndex - 1)
                                                    draggingIndex--
                                                    dragOffset = 0f
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            draggingIndex = -1
                                            dragOffset = 0f
                                            onReorderCompleteState()
                                        },
                                        onDragCancel = {
                                            draggingIndex = -1
                                            dragOffset = 0f
                                            onReorderCompleteState()
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
                                onNoteClick = onNoteClickState,
                                onNoteLongClick = onNoteLongClickState,
                                onSwipeToArchive = onSwipeToArchiveState,
                                onSwipeToTrash = onSwipeToTrashState,
                                onLabelClick = onLabelClickState,
                                showReorderHandle = canReorder,
                                reorderDragModifier = reorderDragModifier,
                                modifier = itemModifier.fillMaxSize()
                            )
                        } else {
                            SwipeableNoteCard(
                                note = note,
                                isSelected = selectedNotes.contains(note.id),
                                searchQuery = searchQuery,
                                onNoteClick = { onNoteClickState(note) },
                                onNoteLongClick = { onNoteLongClickState(note) },
                                onSwipeToArchive = { onSwipeToArchiveState(note) },
                                onSwipeToTrash = { onSwipeToTrashState(note) },
                                onLabelClick = onLabelClickState,
                                listRevision = listRevision,
                                enableArchiveSwipe = enableArchiveSwipe,
                                enableSwipe = swipeEnabled,
                                compact = compact,
                                modifier = itemModifier
                            )
                        }
                    }
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
        onNoteClick = { onNoteClick(note) },
        onNoteLongClick = { onNoteLongClick(note) },
        onSwipeToArchive = { onSwipeToArchive(note) },
        onSwipeToTrash = { onSwipeToTrash(note) },
        onLabelClick = onLabelClick,
        listRevision = listRevision,
        enableArchiveSwipe = enableArchiveSwipe,
        enableSwipe = enableSwipe,
        compact = compact,
        showReorderHandle = showReorderHandle,
        reorderDragModifier = reorderDragModifier,
        modifier = modifier
    )
}
