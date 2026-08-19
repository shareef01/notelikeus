package com.aus.notelikeus.ui.editor.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.ui.theme.NoteEmphasis

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
    // The surface is used exactly as given. It used to be `surfaceColor.copy(alpha = 0.95f)`,
    // which quietly turned the editor's Color.Transparent — RGB(0,0,0) with alpha 0 — into 95%
    // opaque black, so a slab sat over every coloured note instead of the intended tint.
    val isTransparent = surfaceColor.alpha == 0f
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = surfaceColor,
        tonalElevation = if (isTransparent) 0.dp else 1.dp,
        shadowElevation = if (isTransparent) 0.dp else 2.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            contentColor.copy(alpha = NoteEmphasis.Decorative)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onBoldClick) {
                Icon(Icons.Default.FormatBold, contentDescription = stringResource(Res.string.format_bold), tint = contentColor)
            }
            IconButton(onClick = onItalicClick) {
                Icon(Icons.Default.FormatItalic, contentDescription = stringResource(Res.string.format_italic), tint = contentColor)
            }
            IconButton(onClick = onLinkClick) {
                Icon(Icons.Default.Link, contentDescription = stringResource(Res.string.format_link), tint = contentColor)
            }
            VerticalDivider(
                modifier = Modifier.height(24.dp).padding(horizontal = 4.dp),
                color = contentColor.copy(alpha = NoteEmphasis.Decorative)
            )
            IconButton(onClick = onListClick) {
                Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = stringResource(Res.string.format_list), tint = contentColor)
            }
            IconButton(onClick = onChecklistClick) {
                Icon(Icons.Default.Checklist, contentDescription = stringResource(Res.string.format_checklist), tint = contentColor)
            }
        }
    }
}
