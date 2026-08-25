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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.ui.theme.Chrome

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
    /** False greys out the confirm button -- for a dialog whose input is not yet valid. */
    confirmEnabled: Boolean = true,
    dismissLabel: String = stringResource(Res.string.action_cancel),
    extraContent: (@Composable () -> Unit)? = null
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(title) },
        // The message and the extra content stack rather than replace each other. They used to
        // be alternatives, which meant a caller that needed both had to pass `message` for the
        // sake of the parameter and then repeat it by hand inside `extraContent` -- two copies of
        // one string, one of which was never displayed.
        text = {
            Column {
                if (message.isNotBlank()) {
                    Text(message)
                }
                if (extraContent != null) {
                    if (message.isNotBlank()) {
                        Spacer(modifier = Modifier.height(Spacing.md))
                    }
                    extraContent()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(
                    text = confirmLabel,
                    // The colour has to follow `enabled` too. TextButton greys its own content
                    // when disabled, but naming a colour here overrides that -- which left the
                    // button inert and looking exactly as tappable as before, the precise failure
                    // this codebase keeps hunting down elsewhere.
                    color = when {
                        !confirmEnabled ->
                            MaterialTheme.colorScheme.onSurface.copy(alpha = Chrome.Disabled)
                        destructive -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
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
