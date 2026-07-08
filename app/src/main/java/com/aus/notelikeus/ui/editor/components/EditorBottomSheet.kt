package com.aus.notelikeus.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorBottomSheet(
    selectedColor: Int,
    onColorSelect: (Int) -> Unit,
    allLabels: List<Label>,
    selectedLabels: List<Label>,
    onLabelToggle: (Label) -> Unit,
    onCreateLabel: (String) -> Unit,
    isLocked: Boolean,
    onLockToggle: () -> Unit,
    onDeleteNote: () -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var newLabelName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Color Selector
            Text(
                text = "Color",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val colors = listOf(
                    BackgroundLight, NoteRedLight, NoteOrangeLight, NoteYellowLight,
                    NoteGreenLight, NoteTealLight, NoteBlueLight, NoteDarkBlueLight,
                    NotePurpleLight, NotePinkLight, NoteBrownLight, NoteGrayLight
                )
                items(colors) { color ->
                    val colorArgb = color.toArgb()
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable { 
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onColorSelect(colorArgb) 
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedColor == colorArgb) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = if (color == BackgroundLight) Color.Black else Color.White
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Label Selector
            Text(
                text = "Labels",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            allLabels.forEach { label ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLabelToggle(label)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selectedLabels.any { it.id == label.id },
                        onCheckedChange = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = label.name)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newLabelName,
                    onValueChange = { newLabelName = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("New label") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (newLabelName.isNotBlank()) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCreateLabel(newLabelName)
                            newLabelName = ""
                        }
                    },
                    enabled = newLabelName.isNotBlank()
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Create label")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Actions
            ListItem(
                headlineContent = { Text(if (isLocked) "Unlock Note" else "Lock Note") },
                leadingContent = { 
                    Icon(
                        if (isLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                        contentDescription = null
                    )
                },
                modifier = Modifier.clickable { 
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLockToggle()
                    onDismiss()
                }
            )
            
            ListItem(
                headlineContent = { Text("Delete") },
                leadingContent = { Icon(Icons.Default.Delete, contentDescription = null) },
                modifier = Modifier.clickable { 
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDeleteNote()
                    onDismiss()
                }
            )
        }
    }
}
