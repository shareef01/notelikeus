package com.aus.notelikeus.ui.editor.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.text.format.DateFormat
import com.aus.notelikeus.R
import java.util.Date

// Mirrors Firestore security rules
private const val MAX_CONTENT = 500_000
private const val CHAR_WARN = 450_000

private fun formatCount(n: Int): String = when {
    n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000f)}M"
    n >= 1_000 -> "${"%.1f".format(n / 1_000f)}K"
    else -> n.toString()
}

@Composable
fun EditorBottomBar(
    timestamp: Long,
    contentLength: Int = 0,
    reminderTimestamp: Long? = null,
    isSaving: Boolean = false,
    onMoreClick: () -> Unit,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val timeFormat = remember { DateFormat.getTimeFormat(context) }
    val dateFormat = remember { DateFormat.getDateFormat(context) }
    val statusLabel = if (isSaving) {
        stringResource(R.string.saving)
    } else {
        stringResource(R.string.edited_at, timeFormat.format(Date(timestamp)))
    }
    val reminderLabel = reminderTimestamp?.let {
        stringResource(
            R.string.reminder_at,
            dateFormat.format(Date(it)),
            timeFormat.format(Date(it))
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
                    color = contentColor.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                if (reminderLabel != null) {
                    Text(
                        text = reminderLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                }
            }
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            if (contentLength > 0) {
                val nearLimit = contentLength >= CHAR_WARN
                val atLimit = contentLength >= MAX_CONTENT
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${formatCount(contentLength)} / ${formatCount(MAX_CONTENT)}" +
                        if (atLimit) " — limit reached" else "",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (atLimit) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = contentColor.copy(
                        alpha = when {
                            atLimit -> 0.95f
                            nearLimit -> 0.75f
                            else -> 0.4f
                        }
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }

        IconButton(onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            onMoreClick()
        }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.cd_more_options),
                tint = contentColor
            )
        }
    }
}
