package com.aus.notelikeus.ui.editor.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.domain.model.ChecklistItem

@Composable
fun ChecklistUI(
    items: List<ChecklistItem>,
    onUpdate: (Int, String, Boolean) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = item.isChecked,
                    onCheckedChange = { onUpdate(index, item.text, it) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = contentColor,
                        uncheckedColor = contentColor.copy(alpha = 0.6f),
                        checkmarkColor = if (contentColor == Color.White) Color.Black else Color.White
                    )
                )
                
                BasicTextField(
                    value = item.text,
                    onValueChange = { onUpdate(index, it, item.isChecked) },
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = contentColor,
                        textDecoration = if (item.isChecked) TextDecoration.LineThrough else TextDecoration.None
                    ),
                    cursorBrush = SolidColor(contentColor),
                    decorationBox = { innerTextField ->
                        if (item.text.isEmpty()) {
                            Text(
                                text = "List item",
                                style = MaterialTheme.typography.bodyLarge,
                                color = contentColor.copy(alpha = 0.4f)
                            )
                        }
                        innerTextField()
                    }
                )

                IconButton(onClick = { onRemove(index) }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove item",
                        tint = contentColor.copy(alpha = 0.6f)
                    )
                }
            }
        }
        
        TextButton(
            onClick = onAdd,
            modifier = Modifier.padding(start = 4.dp)
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = contentColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "List item",
                color = contentColor,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
