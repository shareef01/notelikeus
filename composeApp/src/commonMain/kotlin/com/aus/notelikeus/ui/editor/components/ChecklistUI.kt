package com.aus.notelikeus.ui.editor.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.ui.editor.MarkdownVisualTransformation
import com.aus.notelikeus.ui.theme.getContentColor
import com.aus.notelikeus.ui.theme.AppType
import com.aus.notelikeus.ui.theme.NoteEmphasis
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.ui.theme.Size
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

@Composable
fun ChecklistUI(
    items: List<ChecklistItem>,
    onUpdate: (Long, String, Boolean) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Long) -> Unit,
    onConvertToText: (() -> Unit)? = null,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Column(modifier = modifier) {
        items.forEach { item ->
            val itemId = item.id ?: return@forEach
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xs)
                    .heightIn(min = Size.touchTarget),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // What the checkbox is checking. Its label lives in a separate text field node,
                // so without this it announced "checkbox, checked" and nothing else -- identical
                // for every row, and useless on a list of them.
                val itemLabel = item.text.ifBlank { stringResource(Res.string.cd_untitled_item) }

                Checkbox(
                    checked = item.isChecked,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onUpdate(itemId, item.text, it)
                    },
                    modifier = Modifier.semantics { contentDescription = itemLabel },
                    colors = CheckboxDefaults.colors(
                        checkedColor = contentColor,
                        uncheckedColor = contentColor.copy(alpha = NoteEmphasis.Icon),
                        checkmarkColor = contentColor.getContentColor()
                    )
                )

                BasicTextField(
                    value = item.text,
                    onValueChange = { onUpdate(itemId, it, item.isChecked) },
                    modifier = Modifier.weight(1f),
                    textStyle = AppType.editorBody.copy(
                        color = contentColor,
                        textDecoration = if (item.isChecked) TextDecoration.LineThrough else TextDecoration.None
                    ),
                    visualTransformation = remember(contentColor) {
                        MarkdownVisualTransformation(contentColor)
                    },
                    cursorBrush = SolidColor(contentColor),
                    decorationBox = { innerTextField ->
                        if (item.text.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.list_item_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = contentColor.copy(alpha = NoteEmphasis.Secondary)
                            )
                        }
                        innerTextField()
                    }
                )

                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onRemove(itemId)
                }) {
                    Icon(
                        Icons.Default.Close,
                        // Named, because "Remove item" repeated down a list identifies nothing.
                        contentDescription = stringResource(
                            Res.string.cd_remove_named_item,
                            itemLabel
                        ),
                        tint = contentColor.copy(alpha = NoteEmphasis.Icon)
                    )
                }
            }
        }

        TextButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                onAdd()
            },
            modifier = Modifier.padding(start = Spacing.xs)
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = contentColor
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text(
                text = stringResource(Res.string.cd_add_list_item),
                color = contentColor,
                style = AppType.editorBody
            )
        }

        if (onConvertToText != null && items.isNotEmpty()) {
            TextButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onConvertToText()
                },
                modifier = Modifier.padding(start = Spacing.xs)
            ) {
                Text(
                    text = stringResource(Res.string.convert_to_text),
                    color = contentColor.copy(alpha = NoteEmphasis.Secondary),
                    style = AppType.editorBody
                )
            }
        }
    }
}
