package com.aus.notelikeus.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.aus.notelikeus.ui.components.ConfirmDialog
import com.aus.notelikeus.ui.theme.Spacing

/**
 * The confirmation dialogs the main screen can show: cloud sign-out (with the
 * delete-cloud-data escalation), cloud restore, empty trash, and delete/trash selection.
 * Extracted from MainScreen; that screen owns the visibility flags and passes callbacks.
 */
@Composable
internal fun MainDialogs(
    showCloudSignOutConfirm: Boolean,
    showCloudRestoreConfirm: Boolean,
    showEmptyTrashConfirm: Boolean,
    showDeleteConfirm: Boolean,
    selectedCount: Int,
    isTrashedFilter: Boolean,
    onCloudSignOutConfirmDismiss: () -> Unit,
    onCloudSignOut: (deleteCloudData: Boolean) -> Unit,
    onCloudRestoreConfirmDismiss: () -> Unit,
    onConfirmCloudRestore: () -> Unit,
    onEmptyTrashConfirmDismiss: () -> Unit,
    onConfirmEmptyTrash: () -> Unit,
    onDeleteConfirmDismiss: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    if (showCloudSignOutConfirm) {
        ConfirmDialog(
            title = stringResource(Res.string.cloud_sign_out_confirm_title),
            message = stringResource(Res.string.cloud_sign_out_confirm_message),
            confirmLabel = stringResource(Res.string.cloud_sign_out),
            onConfirm = { onCloudSignOut(false) },
            onDismiss = onCloudSignOutConfirmDismiss,
            // Signing out is reversible; deleting the cloud copy on the way out is not, so that
            // is the destructive action and it lives in the body rather than on the confirm
            // button, where it would be one mis-tap away.
            extraContent = {
                Column {
                    Text(stringResource(Res.string.cloud_sign_out_confirm_message))
                    Spacer(modifier = Modifier.height(Spacing.md))
                    TextButton(
                        onClick = { onCloudSignOut(true) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(
                            stringResource(Res.string.cloud_sign_out_delete_data),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        )
    }

    // Restore is a merge, not a download, and it can remove notes: a note this device has
    // already synced that is now absent from the cloud is treated as deleted elsewhere and
    // deleted here too. That is correct behaviour, but it is not what "Restore from cloud"
    // sounds like, and it used to run on a single unconfirmed tap. It is marked destructive
    // for the same reason -- it looked like the safest dialog of the four and is not.
    if (showCloudRestoreConfirm) {
        ConfirmDialog(
            title = stringResource(Res.string.cloud_restore_confirm_title),
            message = stringResource(Res.string.cloud_restore_confirm_message),
            confirmLabel = stringResource(Res.string.cloud_restore_confirm_action),
            destructive = true,
            onConfirm = onConfirmCloudRestore,
            onDismiss = onCloudRestoreConfirmDismiss
        )
    }

    if (showEmptyTrashConfirm) {
        ConfirmDialog(
            title = stringResource(Res.string.empty_trash_title),
            message = stringResource(Res.string.empty_trash_message),
            confirmLabel = stringResource(Res.string.empty_trash),
            destructive = true,
            onConfirm = onConfirmEmptyTrash,
            onDismiss = onEmptyTrashConfirmDismiss
        )
    }

    if (showDeleteConfirm) {
        val count = selectedCount
        ConfirmDialog(
            title = if (count == 1) {
                stringResource(Res.string.delete_note_title)
            } else {
                stringResource(Res.string.delete_notes_title, count)
            },
            message = if (isTrashedFilter) {
                stringResource(Res.string.delete_permanent_message)
            } else {
                stringResource(Res.string.delete_to_trash_message)
            },
            confirmLabel = stringResource(Res.string.action_delete),
            // Only permanent deletion is irreversible; moving to trash is undoable, and marking
            // it destructive would make the two look equally final.
            destructive = isTrashedFilter,
            onConfirm = onConfirmDelete,
            onDismiss = onDeleteConfirmDismiss
        )
    }
}
