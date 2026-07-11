package com.aus.notelikeus.ui.main.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.aus.notelikeus.R

@Composable
fun BackupImportDialog(
    fileName: String,
    notesImported: Int,
    labelsCreated: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val hasChanges = notesImported > 0 || labelsCreated > 0
    val summary = when {
        notesImported > 0 && labelsCreated > 0 ->
            stringResource(R.string.backup_import_summary_notes_labels, notesImported, labelsCreated)
        notesImported > 0 ->
            stringResource(R.string.backup_import_summary_notes, notesImported)
        labelsCreated > 0 ->
            stringResource(R.string.backup_import_summary_labels, labelsCreated)
        else -> ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = {
            Text(
                stringResource(R.string.backup_import_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Text(
                if (hasChanges) {
                    stringResource(R.string.backup_import_message, summary)
                } else {
                    stringResource(R.string.backup_import_empty)
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = hasChanges) {
                Text(stringResource(R.string.action_ok), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
