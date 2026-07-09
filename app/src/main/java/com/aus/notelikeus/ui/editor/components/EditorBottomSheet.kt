package com.aus.notelikeus.ui.editor.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aus.notelikeus.R
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.ui.components.NoteColorSwatch
import com.aus.notelikeus.ui.theme.isNoteColorDarkTheme
import com.aus.notelikeus.ui.theme.noteColorsForTheme

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
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val isDarkTheme = isNoteColorDarkTheme()
    val colors = noteColorsForTheme(isDarkTheme)

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_note_title)) },
            text = { Text(stringResource(R.string.delete_editor_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteNote()
                    onDismiss()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
                .navigationBarsPadding()
        ) {
            // Color Selector
            SettingsSectionHeader(
                title = stringResource(R.string.section_color),
                isFirst = true
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(colors, key = { _, color -> color.toArgb() }) { _, color ->
                    val colorArgb = color.toArgb()
                    NoteColorSwatch(
                        color = color,
                        isSelected = selectedColor == colorArgb,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onColorSelect(colorArgb)
                        },
                        touchSize = 44.dp,
                        swatchSize = 32.dp
                    )
                }
            }

            // Label Selector
            SettingsSectionHeader(title = stringResource(R.string.section_labels))
            allLabels.forEach { label ->
                ListItem(
                    headlineContent = { Text(text = label.name) },
                    leadingContent = {
                        Checkbox(
                            checked = selectedLabels.any { it.id == label.id },
                            onCheckedChange = null
                        )
                    },
                    modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onLabelToggle(label)
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
                )
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
                    placeholder = { Text(stringResource(R.string.new_label_hint)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (newLabelName.isNotBlank()) {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onCreateLabel(newLabelName)
                            newLabelName = ""
                        }
                    },
                    enabled = newLabelName.isNotBlank()
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_create_label))
                }
            }

            // Actions
            SettingsSectionHeader(title = stringResource(R.string.section_actions))
            ListItem(
                headlineContent = {
                    Text(
                        if (isLocked) {
                            stringResource(R.string.unlock_note)
                        } else {
                            stringResource(R.string.lock_note)
                        }
                    )
                },
                leadingContent = { 
                    Icon(
                        if (isLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                        contentDescription = if (isLocked) {
                            stringResource(R.string.unlock)
                        } else {
                            stringResource(R.string.locked_note)
                        },
                        modifier = Modifier.size(24.dp)
                    )
                },
                modifier = Modifier.clickable { 
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onLockToggle()
                    onDismiss()
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
            )
            
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_delete)) },
                leadingContent = { 
                    Icon(
                        Icons.Default.Delete, 
                        contentDescription = stringResource(R.string.action_delete),
                        modifier = Modifier.size(24.dp)
                    ) 
                },
                modifier = Modifier.clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    showDeleteConfirm = true
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    isFirst: Boolean = false,
    modifier: Modifier = Modifier
) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        ),
        modifier = modifier.padding(
            start = 16.dp,
            end = 16.dp,
            top = if (isFirst) 16.dp else 24.dp,
            bottom = 8.dp
        )
    )
}
