package com.aus.notelikeus.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.domain.model.Note

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableNoteCard(
    note: Note,
    isSelected: Boolean,
    onNoteClick: () -> Unit,
    onNoteLongClick: () -> Unit,
    onSwipeToArchive: () -> Unit,
    onSwipeToTrash: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onSwipeToArchive()
                    true
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onSwipeToTrash()
                    true
                }
                else -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.padding(vertical = 4.dp),
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color by animateColorAsState(
                when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.StartToEnd -> Color(0xFF4CAF50) // Green
                    SwipeToDismissBoxValue.EndToStart -> Color(0xFFF44336) // Red
                    else -> Color.Transparent
                }, label = "swipe_color"
            )
            
            val icon = when (dismissState.targetValue) {
                SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Archive
                SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                else -> null
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color, MaterialTheme.shapes.medium)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) 
                    Alignment.CenterStart else Alignment.CenterEnd
            ) {
                icon?.let {
                    Icon(
                        it,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
            }
        }
    ) {
        NoteCard(
            note = note,
            isSelected = isSelected,
            onClick = onNoteClick,
            onLongClick = onNoteLongClick
        )
    }
}
