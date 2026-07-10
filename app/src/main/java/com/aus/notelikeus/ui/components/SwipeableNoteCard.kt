package com.aus.notelikeus.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.R
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.ui.theme.SwipeArchiveDark
import com.aus.notelikeus.ui.theme.SwipeArchiveLight
import com.aus.notelikeus.ui.theme.SwipeDeleteDark
import com.aus.notelikeus.ui.theme.SwipeDeleteLight
import com.aus.notelikeus.ui.theme.isNoteColorDarkTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableNoteCard(
    note: Note,
    isSelected: Boolean,
    onNoteClick: () -> Unit,
    onNoteLongClick: () -> Unit,
    onSwipeToArchive: () -> Unit,
    onSwipeToTrash: () -> Unit,
    onLabelClick: ((Long) -> Unit)? = null,
    searchQuery: String = "",
    listRevision: Int = 0,
    enableArchiveSwipe: Boolean = true,
    enableSwipe: Boolean = true,
    compact: Boolean = false,
    showReorderHandle: Boolean = false,
    reorderDragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    val archiveLabel = stringResource(R.string.cd_archive)
    val deleteLabel = stringResource(R.string.cd_delete)
    val haptic = LocalHapticFeedback.current
    val isDark = isNoteColorDarkTheme()
    val archiveColor = if (isDark) SwipeArchiveDark else SwipeArchiveLight
    val deleteColor = if (isDark) SwipeDeleteDark else SwipeDeleteLight
    val archiveIconTint = if (isDark) Color.Black else Color.White
    val deleteIconTint = Color.White
    val canArchiveSwipe = enableSwipe && enableArchiveSwipe
    val canTrashSwipe = enableSwipe

    key(note.id ?: note.timestamp, listRevision) {
        val dismissState = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> {
                        if (canArchiveSwipe) {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onSwipeToArchive()
                        }
                        false
                    }
                    SwipeToDismissBoxValue.EndToStart -> {
                        if (canTrashSwipe) {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onSwipeToTrash()
                        }
                        false
                    }
                    else -> false
                }
            }
        )

        SwipeToDismissBox(
            state = dismissState,
            modifier = modifier,
            enableDismissFromStartToEnd = canArchiveSwipe,
            enableDismissFromEndToStart = canTrashSwipe,
            backgroundContent = {
                val direction = dismissState.dismissDirection
                val isActive = dismissState.targetValue != SwipeToDismissBoxValue.Settled
                val color by animateColorAsState(
                    when (dismissState.targetValue) {
                        SwipeToDismissBoxValue.StartToEnd -> archiveColor
                        SwipeToDismissBoxValue.EndToStart -> deleteColor
                        else -> Color.Transparent
                    },
                    label = "swipe_color"
                )

                val icon = when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Archive
                    SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                    else -> null
                }

                val iconTint = when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.StartToEnd -> archiveIconTint
                    SwipeToDismissBoxValue.EndToStart -> deleteIconTint
                    else -> Color.White
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isActive) color else Color.Transparent,
                            MaterialTheme.shapes.large
                        )
                        .padding(horizontal = 24.dp),
                    contentAlignment = when (direction) {
                        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                        else -> Alignment.Center
                    }
                ) {
                    if (isActive) {
                        icon?.let {
                            Icon(
                                it,
                                contentDescription = if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd) {
                                    archiveLabel
                                } else {
                                    deleteLabel
                                },
                                tint = iconTint
                            )
                        }
                    }
                }
            }
        ) {
            NoteCard(
                note = note,
                isSelected = isSelected,
                searchQuery = searchQuery,
                compact = compact,
                onClick = onNoteClick,
                onLongClick = onNoteLongClick,
                onLabelClick = onLabelClick,
                showReorderHandle = showReorderHandle,
                reorderDragModifier = reorderDragModifier
            )
        }
    }
}
