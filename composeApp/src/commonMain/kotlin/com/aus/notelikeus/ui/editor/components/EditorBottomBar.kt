package com.aus.notelikeus.ui.editor.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.util.DateUtils
import com.aus.notelikeus.ui.theme.NoteEmphasis

@Composable
fun EditorBottomBar(
    timestamp: Long,
    reminderTimestamp: Long? = null,
    onMoreClick: () -> Unit,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val editedLabel = stringResource(
        Res.string.edited_at,
        DateUtils.formatTime(timestamp),
        "" // Placeholder for second arg if any
    )
    val reminderLabel = reminderTimestamp?.let {
        stringResource(
            Res.string.reminder_at,
            DateUtils.formatDateTime(it),
            DateUtils.formatTime(it)
        )
    }

    BottomAppBar(
        modifier = modifier,
        containerColor = Color.Transparent,
        contentPadding = PaddingValues(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        windowInsets = WindowInsets.navigationBars
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(
                    text = editedLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = NoteEmphasis.Secondary),
                    textAlign = TextAlign.Center
                )
                if (reminderLabel != null) {
                    Text(
                        text = reminderLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = NoteEmphasis.Secondary),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        IconButton(onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            onMoreClick()
        }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(Res.string.cd_more_options),
                tint = contentColor
            )
        }
    }
}
