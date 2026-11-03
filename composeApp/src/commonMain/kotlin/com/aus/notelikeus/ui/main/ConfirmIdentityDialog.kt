package com.aus.notelikeus.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.aus.notelikeus.ui.auth.EMAIL_CODE_LENGTH
import com.aus.notelikeus.ui.components.ConfirmDialog
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.identity_check_code_label
import notelikeus.composeapp.generated.resources.identity_check_confirm
import notelikeus.composeapp.generated.resources.identity_check_message_delete_account
import notelikeus.composeapp.generated.resources.identity_check_message_wipe
import notelikeus.composeapp.generated.resources.identity_check_resend_code
import notelikeus.composeapp.generated.resources.identity_check_send_code
import notelikeus.composeapp.generated.resources.identity_check_title
import org.jetbrains.compose.resources.stringResource

/** Whether [code] is complete enough to send for checking. */
internal fun isIdentityCodeComplete(code: String): Boolean =
    code.length == EMAIL_CODE_LENGTH && code.all(Char::isDigit)

/**
 * "Confirm it's you" before an irreversible action the server refused on a stale session (AUD-005).
 *
 * The code goes to the signed-in account's own address ([email]); entering it starts a fresh session
 * for the same account, and the caller then retries [check]. Nothing on the device changes: it is not
 * a sign-out. Errors arrive through the screen's usual cloud-sync messages.
 */
@Composable
internal fun ConfirmIdentityDialog(
    check: IdentityCheck,
    email: String,
    codeSent: Boolean,
    isBusy: Boolean,
    onSendCode: () -> Unit,
    onConfirm: (code: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    ConfirmDialog(
        title = stringResource(Res.string.identity_check_title),
        message = stringResource(
            when (check) {
                IdentityCheck.DeleteAccount -> Res.string.identity_check_message_delete_account
                IdentityCheck.WipeCloudData -> Res.string.identity_check_message_wipe
            },
            email,
        ),
        confirmLabel = stringResource(
            if (codeSent) Res.string.identity_check_confirm else Res.string.identity_check_send_code,
        ),
        destructive = codeSent,
        confirmEnabled = !isBusy && (!codeSent || isIdentityCodeComplete(code)),
        onConfirm = { if (codeSent) onConfirm(code) else onSendCode() },
        onDismiss = { if (!isBusy) onDismiss() },
        extraContent = if (!codeSent) {
            null
        } else {
            {
                Column {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { input -> code = input.filter(Char::isDigit).take(EMAIL_CODE_LENGTH) },
                        label = { Text(stringResource(Res.string.identity_check_code_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = onSendCode, enabled = !isBusy) {
                        Text(stringResource(Res.string.identity_check_resend_code))
                    }
                }
            }
        },
    )
}
