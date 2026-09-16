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
import com.aus.notelikeus.ui.theme.Spacing

import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import com.aus.notelikeus.ui.main.CloudSyncStatus

@Composable
fun EditorBottomBar(
    timestamp: Long,
    reminderTimestamp: Long? = null,
    isSaving: Boolean = false,
    saveFailed: Boolean = false,
    isSavedLocally: Boolean = false,
    cloudSyncStatus: CloudSyncStatus = CloudSyncStatus.Unknown,
    isGuest: Boolean = true,
    attachmentSyncPending: Boolean = false,
    onRetrySave: (() -> Unit)? = null,
    onMoreClick: () -> Unit,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val editedTime = DateUtils.formatTime(timestamp)
    val statusText = when {
        saveFailed -> stringResource(Res.string.local_save_failed)
        isSaving -> stringResource(Res.string.saving_locally)
        // Every branch below claims the note is on disk, so the flag that says whether it
        // actually is has to be read before any of them. A new note sits here with nothing
        // saving and nothing failed, and reporting it as "Saved locally" was the editor
        // promising durability it did not have yet.
        !isSavedLocally -> stringResource(Res.string.not_saved_yet)
        isGuest || cloudSyncStatus == CloudSyncStatus.Unknown -> {
            stringResource(Res.string.saved_locally_edited, editedTime)
        }
        attachmentSyncPending -> {
            stringResource(Res.string.saved_locally_sync_pending)
        }
        cloudSyncStatus == CloudSyncStatus.Syncing -> {
            stringResource(Res.string.saved_locally_syncing)
        }
        cloudSyncStatus == CloudSyncStatus.Offline -> {
            stringResource(Res.string.saved_locally_offline)
        }
        cloudSyncStatus == CloudSyncStatus.Error -> {
            stringResource(Res.string.saved_locally_sync_failed)
        }
        else -> {
            stringResource(Res.string.saved_locally_synced)
        }
    }
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
        contentPadding = PaddingValues(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
        windowInsets = WindowInsets.navigationBars
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.lg)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center)
            ) {
                if (saveFailed && onRetrySave != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.clickable { onRetrySave() }
                    ) {
                        Text(
                            text = "$statusText • ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(Res.string.tap_to_retry),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (saveFailed) MaterialTheme.colorScheme.error else contentColor.copy(alpha = NoteEmphasis.Secondary),
                        textAlign = TextAlign.Center
                    )
                }
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
