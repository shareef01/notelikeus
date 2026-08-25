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
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.ui.components.NoteColorSwatch
import com.aus.notelikeus.ui.theme.noteColorName
import com.aus.notelikeus.ui.main.components.SettingsSectionHeader
import com.aus.notelikeus.ui.theme.isNoteColorDarkTheme
import com.aus.notelikeus.ui.theme.noteColorsForTheme
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.ui.theme.Size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorBottomSheet(
    selectedColor: Int,
    onColorSelect: (Int) -> Unit,
    allLabels: List<Label>,
    selectedLabels: List<Label>,
    onLabelToggle: (Label) -> Unit,
    onCreateLabel: (String) -> Unit,
    onDeleteNote: () -> Unit,
    onDismiss: () -> Unit,
    isChecklist: Boolean = false,
    onConvertToChecklist: () -> Unit = {},
    onConvertToText: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    var newLabelName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val isDarkTheme = isNoteColorDarkTheme()
    val colors = noteColorsForTheme(isDarkTheme)

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(Res.string.delete_note_title)) },
            text = { Text(stringResource(Res.string.delete_editor_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteNote()
                    onDismiss()
                }) {
                    Text(
                        stringResource(Res.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xxxl)
                .navigationBarsPadding()
        ) {
            // Color Selector
            SettingsSectionHeader(
                title = stringResource(Res.string.section_color),
                isFirst = true
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
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
                        // 48.dp is the accessibility minimum for a touch target; this sheet has
                        // the room for it, so there is no reason to sit under it.
                        touchSize = Size.touchTarget,
                        swatchSize = Spacing.xxxl,
                        contentDescription = noteColorName(color)
                    )
                }
            }

            // Label Selector
            SettingsSectionHeader(title = stringResource(Res.string.section_labels))
            if (allLabels.isEmpty()) {
                Text(
                    text = stringResource(Res.string.empty_labels_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )
            } else {
                allLabels.forEachIndexed { index, label ->
                    val isChecked = selectedLabels.any { it.id == label.id }
                    ListItem(
                        headlineContent = { Text(text = label.name) },
                        leadingContent = {
                            // Null, so the checkbox is a picture of the state rather than a second
                            // control. The row owns the interaction; a live checkbox inside a
                            // clickable row is one action wearing two hit targets, and it left the
                            // row announcing as a button with no checked state at all.
                            Checkbox(checked = isChecked, onCheckedChange = null)
                        },
                        modifier = Modifier.toggleable(
                            value = isChecked,
                            role = Role.Checkbox,
                            onValueChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onLabelToggle(label)
                            }
                        ),
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                    if (index < allLabels.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newLabelName,
                    onValueChange = { newLabelName = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(Res.string.new_label_hint)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
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
                    Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.cd_create_label))
                }
            }

            // Actions
            SettingsSectionHeader(title = stringResource(Res.string.section_actions))

            ListItem(
                headlineContent = {
                    Text(
                        if (isChecklist) stringResource(Res.string.convert_to_text) 
                        else stringResource(Res.string.format_checklist)
                    )
                },
                leadingContent = {
                    Icon(
                        if (isChecklist) Icons.Default.TextFields else Icons.Default.Checklist,
                        contentDescription = null,
                        modifier = Modifier.size(Size.iconLarge)
                    )
                },
                modifier = Modifier.clickable(role = Role.Button) {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    if (isChecklist) onConvertToText() else onConvertToChecklist()
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
            )
            
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(Res.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                },
                leadingContent = { 
                    Icon(
                        Icons.Default.Delete,
                        // The row's own text says "Delete"; describing the icon too says it twice.
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(Size.iconLarge)
                    ) 
                },
                modifier = Modifier.clickable(role = Role.Button) {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    showDeleteConfirm = true
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    }
}
