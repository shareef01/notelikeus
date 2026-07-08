package com.aus.notelikeus.ui.main.components

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.ui.main.NoteFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopAppBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isListView: Boolean,
    onToggleLayout: () -> Unit,
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onArchiveSelected: () -> Unit,
    onRestoreSelected: () -> Unit,
    currentFilter: NoteFilter,
    onMenuClick: () -> Unit,
    onProfileClick: () -> Unit,
    selectedColor: Int?,
    onColorSelect: (Int?) -> Unit,
    allLabels: List<Label>,
    selectedLabelId: Long?,
    onLabelSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Column(modifier = modifier.statusBarsPadding()) {
        AnimatedContent(
            targetState = selectedCount > 0,
            transitionSpec = {
                (slideInVertically { height -> height } + fadeIn())
                    .togetherWith(slideOutVertically { height -> -height } + fadeOut())
            },
            label = "topbar"
        ) { isSelectionMode ->
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text(text = "$selectedCount selected") },
                    navigationIcon = {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClearSelection()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        if (currentFilter != NoteFilter.ACTIVE) {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onRestoreSelected()
                            }) {
                                Icon(Icons.Default.Restore, contentDescription = "Restore")
                            }
                        }
                        if (currentFilter != NoteFilter.ARCHIVED) {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onArchiveSelected()
                            }) {
                                Icon(Icons.Default.Archive, contentDescription = "Archive")
                            }
                        }
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDeleteSelected()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            } else {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 16.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onMenuClick()
                        }) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = "Menu",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = when(currentFilter) {
                                        NoteFilter.ACTIVE -> "Search your notes"
                                        NoteFilter.ARCHIVED -> "Search archive"
                                        NoteFilter.TRASHED -> "Search trash"
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = onSearchQueryChange,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { 
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSearchQueryChange("") 
                            }) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleLayout()
                        }) {
                            Icon(
                                if (isListView) Icons.Default.GridView else Icons.Default.ViewHeadline,
                                contentDescription = "Toggle layout",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(4.dp))
                        
                        Surface(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable { 
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onProfileClick() 
                                },
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            // Profile placeholder
                        }
                    }
                }
            }
        }
        
        // Step 4: Filter Row
        AnimatedVisibility(
            visible = selectedCount == 0,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            FilterRow(
                selectedColor = selectedColor,
                onColorSelect = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onColorSelect(it)
                },
                allLabels = allLabels,
                selectedLabelId = selectedLabelId,
                onLabelSelect = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLabelSelect(it)
                }
            )
        }
    }
}
