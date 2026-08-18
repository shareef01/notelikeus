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
        AlertDialog(
            onDismissRequest = onCloudSignOutConfirmDismiss,
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(Res.string.cloud_sign_out_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(Res.string.cloud_sign_out_confirm_message))
                    Spacer(modifier = Modifier.height(12.dp))
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
            },
            confirmButton = {
                TextButton(onClick = { onCloudSignOut(false) }) {
                    Text(stringResource(Res.string.cloud_sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = onCloudSignOutConfirmDismiss) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        )
    }

    // Restore is a merge, not a download, and it can remove notes: a note this device has
    // already synced that is now absent from the cloud is treated as deleted elsewhere and
    // deleted here too. That is correct behaviour, but it is not what "Restore from cloud"
    // sounds like, and it used to run on a single unconfirmed tap.
    if (showCloudRestoreConfirm) {
        AlertDialog(
            onDismissRequest = onCloudRestoreConfirmDismiss,
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(Res.string.cloud_restore_confirm_title)) },
            text = { Text(stringResource(Res.string.cloud_restore_confirm_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmCloudRestore) {
                    Text(stringResource(Res.string.cloud_restore_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = onCloudRestoreConfirmDismiss) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        )
    }

    if (showEmptyTrashConfirm) {
        AlertDialog(
            onDismissRequest = onEmptyTrashConfirmDismiss,
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(Res.string.empty_trash_title)) },
            text = { Text(stringResource(Res.string.empty_trash_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmEmptyTrash) {
                    Text(
                        stringResource(Res.string.empty_trash),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onEmptyTrashConfirmDismiss) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        )
    }

    if (showDeleteConfirm) {
        val count = selectedCount
        AlertDialog(
            onDismissRequest = onDeleteConfirmDismiss,
            shape = MaterialTheme.shapes.large,
            title = {
                Text(
                    if (count == 1) {
                        stringResource(Res.string.delete_note_title)
                    } else {
                        stringResource(Res.string.delete_notes_title, count)
                    }
                )
            },
            text = {
                Text(
                    if (isTrashedFilter) {
                        stringResource(Res.string.delete_permanent_message)
                    } else {
                        stringResource(Res.string.delete_to_trash_message)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) {
                    Text(
                        stringResource(Res.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDeleteConfirmDismiss) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        )
    }
}
