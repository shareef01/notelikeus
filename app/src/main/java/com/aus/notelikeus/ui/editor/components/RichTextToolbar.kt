package com.aus.notelikeus.ui.editor.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.R

@Composable
fun RichTextToolbar(
    onBoldClick: () -> Unit,
    onItalicClick: () -> Unit,
    onListClick: () -> Unit,
    onChecklistClick: () -> Unit,
    onLinkClick: () -> Unit,
    contentColor: Color,
    surfaceColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = surfaceColor.copy(alpha = 0.95f),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            contentColor.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onBoldClick) {
                Icon(Icons.Default.FormatBold, contentDescription = stringResource(R.string.format_bold), tint = contentColor)
            }
            IconButton(onClick = onItalicClick) {
                Icon(Icons.Default.FormatItalic, contentDescription = stringResource(R.string.format_italic), tint = contentColor)
            }
            IconButton(onClick = onLinkClick) {
                Icon(Icons.Default.Link, contentDescription = stringResource(R.string.format_link), tint = contentColor)
            }
            VerticalDivider(
                modifier = Modifier.height(24.dp).padding(horizontal = 4.dp),
                color = contentColor.copy(alpha = 0.2f)
            )
            IconButton(onClick = onListClick) {
                Icon(Icons.Default.FormatListBulleted, contentDescription = stringResource(R.string.format_list), tint = contentColor)
            }
            IconButton(onClick = onChecklistClick) {
                Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.format_checklist), tint = contentColor)
            }
        }
    }
}
