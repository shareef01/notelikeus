package com.aus.notelikeus.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.action_cancel
import org.jetbrains.compose.resources.stringResource

/**
 * One confirmation, used for every action that cannot be undone by tapping again.
 *
 * The four confirmations on the main screen were four hand-built `AlertDialog`s that agreed on
 * most things and disagreed on the rest: two coloured the confirm action `error` and made it
 * SemiBold, two left it as a plain text button, and only some named the consequence. Which is
 * exactly backwards — "empty the trash" and "restore from cloud" are both destructive, and the
 * one that looked safest (restore) is the one that can silently remove notes.
 *
 * [destructive] is therefore a parameter rather than a styling choice made per call site: the
 * caller states whether the action destroys something, and this decides what that looks like.
 *
 * A dialog rather than the bottom sheet the design brief names. This composable is shared with
 * the Windows build, where a sheet sliding up from the bottom of a desktop window is not a
 * convention anyone expects, and a modal confirmation is. See DECISIONS.md D7.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    dismissLabel: String = stringResource(Res.string.action_cancel),
    extraContent: (@Composable () -> Unit)? = null
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(title) },
        text = {
            if (extraContent != null) {
                extraContent()
            } else {
                Text(message)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    fontWeight = if (destructive) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        }
    )
}
