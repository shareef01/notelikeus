package com.aus.notelikeus.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.ui.components.ConfirmDialog
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.account_delete
import notelikeus.composeapp.generated.resources.account_delete_accept_loss
import notelikeus.composeapp.generated.resources.account_delete_backup_complete
import notelikeus.composeapp.generated.resources.account_delete_backup_incomplete
import notelikeus.composeapp.generated.resources.account_delete_confirm_message
import notelikeus.composeapp.generated.resources.account_delete_confirm_title
import notelikeus.composeapp.generated.resources.account_delete_export_first
import notelikeus.composeapp.generated.resources.account_delete_in_progress
import notelikeus.composeapp.generated.resources.account_delete_type_to_confirm
import org.jetbrains.compose.resources.stringResource

/** Exact, and in capitals, so it cannot be typed by reflex. */
internal const val DELETE_ACCOUNT_CONFIRMATION = "DELETE"

internal fun isDeleteAccountConfirmed(typed: String): Boolean = typed == DELETE_ACCOUNT_CONFIRMATION

/**
 * Whether the delete button may be pressed.
 *
 * After a backup that left images out, the typed word is not enough: the user also has to accept that
 * those images go with the account (AUD-001). Without any backup the typed word is enough, because
 * the account is the user's to delete.
 */
internal fun isDeleteAccountArmed(
    typed: String,
    isDeleting: Boolean,
    lastExport: BackupTransferEvent.BundleExported?,
    acceptedLoss: Boolean,
): Boolean {
    val incomplete = lastExport != null && !lastExport.complete
    return isDeleteAccountConfirmed(typed) && !isDeleting && (!incomplete || acceptedLoss)
}

/**
 * The only thing between a stray tap and an irreversible delete. Stays open while the request runs
 * and after a failure, so a retry does not mean typing the word again; the caller closes it when the
 * account is gone.
 *
 * [lastExport] is the bundle exported since the dialog opened. Its completeness is shown as it is:
 * "complete" only when every image is in the file.
 */
@Composable
internal fun DeleteAccountDialog(
    isDeleting: Boolean,
    onExportBackup: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    lastExport: BackupTransferEvent.BundleExported? = null,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    var acceptedLoss by rememberSaveable { mutableStateOf(false) }
    // A newer export asks again: accepting one backup's gap is not accepting the next one's.
    LaunchedEffect(lastExport) { acceptedLoss = false }
    val missing = lastExport?.let { it.attachmentsTotal - it.attachmentsIncluded } ?: 0

    ConfirmDialog(
        title = stringResource(Res.string.account_delete_confirm_title),
        message = stringResource(Res.string.account_delete_confirm_message),
        confirmLabel = stringResource(
            if (isDeleting) Res.string.account_delete_in_progress else Res.string.account_delete
        ),
        destructive = true,
        confirmEnabled = isDeleteAccountArmed(typed, isDeleting, lastExport, acceptedLoss),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        extraContent = {
            Column {
                TextButton(onClick = onExportBackup, enabled = !isDeleting) {
                    Text(stringResource(Res.string.account_delete_export_first))
                }
                when {
                    lastExport == null -> Unit
                    lastExport.complete -> Text(
                        stringResource(Res.string.account_delete_backup_complete, lastExport.attachmentsTotal),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> {
                        Text(
                            stringResource(
                                Res.string.account_delete_backup_incomplete,
                                missing,
                                lastExport.attachmentsTotal,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .toggleable(
                                    value = acceptedLoss,
                                    role = Role.Checkbox,
                                    onValueChange = { acceptedLoss = it },
                                ),
                        ) {
                            Checkbox(checked = acceptedLoss, onCheckedChange = null)
                            Text(stringResource(Res.string.account_delete_accept_loss, missing))
                        }
                    }
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(Res.string.account_delete_type_to_confirm)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
