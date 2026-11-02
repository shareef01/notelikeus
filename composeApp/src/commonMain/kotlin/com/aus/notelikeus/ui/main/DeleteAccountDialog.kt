package com.aus.notelikeus.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aus.notelikeus.ui.components.ConfirmDialog
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.account_delete
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
 * The only thing between a stray tap and an irreversible delete. Stays open while the request runs
 * and after a failure, so a retry does not mean typing the word again; the caller closes it when the
 * account is gone.
 */
@Composable
internal fun DeleteAccountDialog(
    isDeleting: Boolean,
    onExportBackup: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    ConfirmDialog(
        title = stringResource(Res.string.account_delete_confirm_title),
        message = stringResource(Res.string.account_delete_confirm_message),
        confirmLabel = stringResource(
            if (isDeleting) Res.string.account_delete_in_progress else Res.string.account_delete
        ),
        destructive = true,
        confirmEnabled = isDeleteAccountConfirmed(typed) && !isDeleting,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        extraContent = {
            Column {
                TextButton(onClick = onExportBackup) {
                    Text(stringResource(Res.string.account_delete_export_first))
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
