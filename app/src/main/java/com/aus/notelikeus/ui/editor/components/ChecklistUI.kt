package com.aus.notelikeus.ui.editor.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.R
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.ui.components.performItemToggleFeedback
import com.aus.notelikeus.ui.editor.MarkdownVisualTransformation
import com.aus.notelikeus.ui.theme.EditorBodyStyle
import com.aus.notelikeus.ui.theme.getContentColor

private const val MaxChecklistItemLines = 3

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
                    .padding(vertical = 4.dp)
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = item.isChecked,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onUpdate(itemId, item.text, it)
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = contentColor,
                        uncheckedColor = contentColor.copy(alpha = 0.6f),
                        checkmarkColor = contentColor.getContentColor()
                    )
                )

                BasicTextField(
                    value = item.text,
                    onValueChange = { onUpdate(itemId, it, item.isChecked) },
                    modifier = Modifier.weight(1f),
                    textStyle = EditorBodyStyle.copy(
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
                                text = stringResource(R.string.list_item_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = contentColor.copy(alpha = 0.4f)
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
                        contentDescription = stringResource(R.string.cd_remove_item),
                        tint = contentColor.copy(alpha = 0.6f)
                    )
                }
            }
        }

        TextButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                onAdd()
            },
            modifier = Modifier.padding(start = 4.dp)
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = contentColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.cd_add_list_item),
                color = contentColor,
                style = EditorBodyStyle
            )
        }

        item {
            TextButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onAdd()
                },
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.cd_add_list_item),
                    tint = contentColor
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.list_item_hint),
                    color = contentColor,
                    style = EditorBodyStyle
                )
            }

            if (onConvertToText != null && items.isNotEmpty()) {
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onConvertToText()
                    },
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.convert_to_text),
                        color = contentColor.copy(alpha = 0.8f),
                        style = EditorBodyStyle
                    )
                }
            }
        }
    }
}

@Composable
private fun ChecklistItemRow(
    item: ChecklistItem,
    itemId: Long,
    contentColor: Color,
    onUpdate: (Long, String, Boolean) -> Unit,
    onRemove: (Long) -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
            Checkbox(
                checked = item.isChecked,
                onCheckedChange = { checked ->
                    haptic.performItemToggleFeedback(checked)
                    onUpdate(itemId, item.text, checked)
                },
                colors = CheckboxDefaults.colors(
                    checkedColor = contentColor,
                    uncheckedColor = contentColor.copy(alpha = 0.6f),
                    checkmarkColor = if (contentColor == Color.White) Color.Black else Color.White
                )
            )
        }

        BasicTextField(
            value = item.text,
            onValueChange = { onUpdate(itemId, it, item.isChecked) },
            modifier = Modifier.weight(1f),
            maxLines = MaxChecklistItemLines,
            textStyle = EditorBodyStyle.copy(
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
                        text = stringResource(R.string.list_item_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                contentDescription = stringResource(R.string.cd_remove_item),
                tint = contentColor.copy(alpha = 0.6f)
            )
        }
    }
}
