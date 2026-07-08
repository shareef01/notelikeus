package com.aus.notelikeus.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun RichTextToolbar(
    onBoldClick: () -> Unit,
    onItalicClick: () -> Unit,
    onListClick: () -> Unit,
    onChecklistClick: () -> Unit,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onBoldClick) {
                Icon(Icons.Default.FormatBold, contentDescription = "Bold", tint = contentColor)
            }
            IconButton(onClick = onItalicClick) {
                Icon(Icons.Default.FormatItalic, contentDescription = "Italic", tint = contentColor)
            }
            VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))
            IconButton(onClick = onListClick) {
                Icon(Icons.Default.FormatListBulleted, contentDescription = "List", tint = contentColor)
            }
            IconButton(onClick = onChecklistClick) {
                Icon(Icons.Default.Checklist, contentDescription = "Checklist", tint = contentColor)
            }
        }
    }
}
