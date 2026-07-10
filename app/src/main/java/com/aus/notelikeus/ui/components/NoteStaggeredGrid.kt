package com.aus.notelikeus.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.R
import com.aus.notelikeus.domain.model.Note

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
    contentPadding: PaddingValues = PaddingValues(8.dp)
) {
    val haptic = LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val reorderLabel = stringResource(R.string.cd_reorder)
    val pinnedSectionLabel = stringResource(R.string.section_pinned)
    val reorderThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val canReorder = columns == 1 && selectedNotes.isEmpty() && !compact && allowReorder
    val swipeEnabled = enableSwipe && selectedNotes.isEmpty()
    val dragHandleSize = 24.dp
    val dragHandleLeadingPadding = 16.dp // Disciplined 16.dp Grid
    val itemSpacing = 12.dp // Fixed Arrangement Space

    fun getDateHeader(timestamp: Long): String {
        return when {
            DateUtils.isToday(timestamp) -> "Today"
            DateUtils.isToday(timestamp + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
            else -> DateUtils.formatDateTime(
                context,
                timestamp,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH
            )
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
                val itemModifier = Modifier.animateItem()
                if (columns == 1) {
                    if (canReorder) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = itemModifier
                        ) {
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = reorderLabel,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                                modifier = Modifier
                                    .padding(start = dragHandleLeadingPadding)
                                    .size(dragHandleSize)
                                    .pointerInput(index, notes.size) {
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
                            )
                            noteListCard(
                                note = note,
                                selectedNotes = selectedNotes,
                                searchQuery = searchQuery,
                                listRevision = listRevision,
                                enableArchiveSwipe = enableArchiveSwipe,
                                enableSwipe = swipeEnabled,
                                compact = compact,
                                haptic = haptic,
                                onNoteClick = onNoteClick,
                                onNoteLongClick = onNoteLongClick,
                                onSwipeToArchive = onSwipeToArchive,
                                onSwipeToTrash = onSwipeToTrash,
                                onLabelClick = onLabelClick,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        noteListCard(
                            note = note,
                            selectedNotes = selectedNotes,
                            searchQuery = searchQuery,
                            listRevision = listRevision,
                            enableArchiveSwipe = enableArchiveSwipe,
                            enableSwipe = swipeEnabled,
                            compact = compact,
                            haptic = haptic,
                            onNoteClick = onNoteClick,
                            onNoteLongClick = onNoteLongClick,
                            onSwipeToArchive = onSwipeToArchive,
                            onSwipeToTrash = onSwipeToTrash,
                            onLabelClick = onLabelClick,
                            modifier = itemModifier.fillMaxSize()
                        )
                    }
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
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onNoteClick: (Note) -> Unit,
    onNoteLongClick: (Note) -> Unit,
    onSwipeToArchive: (Note) -> Unit,
    onSwipeToTrash: (Note) -> Unit,
    onLabelClick: ((Long) -> Unit)?,
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
        modifier = modifier
    )
}
