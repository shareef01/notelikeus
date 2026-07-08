package com.aus.notelikeus.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
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
    modifier: Modifier = Modifier,
    searchQuery: String = "",
    columns: Int = 2,
    contentPadding: PaddingValues = PaddingValues(8.dp)
) {
    val haptic = LocalHapticFeedback.current
    val gridState = rememberLazyStaggeredGridState()

    LazyVerticalStaggeredGrid(
        state = gridState,
        columns = StaggeredGridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp
    ) {
        items(notes, key = { it.id ?: it.timestamp }) { note ->
            val itemModifier = Modifier
                .animateItem()
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDrag = { change, _ ->
                            change.consume()
                        }
                    )
                }

            if (columns == 1) {
                SwipeableNoteCard(
                    note = note,
                    isSelected = selectedNotes.contains(note.id),
                    onNoteClick = { 
                        if (selectedNotes.isNotEmpty()) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        onNoteClick(note) 
                    },
                    onNoteLongClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onNoteLongClick(note) 
                    },
                    onSwipeToArchive = { onSwipeToArchive(note) },
                    onSwipeToTrash = { onSwipeToTrash(note) },
                    modifier = itemModifier
                )
            } else {
                NoteCard(
                    note = note,
                    isSelected = selectedNotes.contains(note.id),
                    searchQuery = searchQuery,
                    onClick = { 
                        if (selectedNotes.isNotEmpty()) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        onNoteClick(note) 
                    },
                    onLongClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onNoteLongClick(note) 
                    },
                    modifier = itemModifier
                )
            }
        }
    }
}
