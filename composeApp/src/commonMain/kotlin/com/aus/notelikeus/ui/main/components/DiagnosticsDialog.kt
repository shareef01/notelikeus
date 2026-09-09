package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.ui.theme.NoteEmphasis
import com.aus.notelikeus.ui.theme.Spacing
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.action_cancel
import notelikeus.composeapp.generated.resources.diagnostics_copy
import notelikeus.composeapp.generated.resources.diagnostics_explainer
import notelikeus.composeapp.generated.resources.diagnostics_title
import notelikeus.composeapp.generated.resources.diagnostics_unavailable
import org.jetbrains.compose.resources.stringResource

/**
 * Shows the diagnostics report and offers to copy it.
 *
 * The whole report is on screen before it can be copied — deliberately. Asking someone to hand a
 * file to a maintainer without letting them read it first is the wrong shape for an app whose
 * selling point is that nothing leaves the device unasked.
 *
 * Three states, all of which have to render, because diagnostics run when something is already
 * broken: [report] is null while collection is in flight, blank when collection failed, and the
 * rendered text otherwise.
 */
@Composable
fun DiagnosticsDialog(
    report: String?,
    collectionFailed: Boolean,
    onCopied: () -> Unit,
    onDismiss: () -> Unit,
) {
    // LocalClipboardManager is deprecated in favour of LocalClipboard, and on Compose
    // Multiplatform 1.8.2 that replacement cannot be used from commonMain: `Clipboard.setClipEntry`
    // takes a `ClipEntry`, which is an `expect class` with no common factory on this line. Copying
    // a string would therefore need its own expect/actual pair, and the Compose version is pinned
    // by D18. The deprecated API works on both targets; revisit when the CMP line moves.
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.diagnostics_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(Res.string.diagnostics_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = NoteEmphasis.Secondary,
                    ),
                    modifier = Modifier.padding(bottom = Spacing.sm),
                )
                Text(
                    text = when {
                        report != null -> report
                        // Distinguishes "still working" from "cannot": a blank pane reads as a
                        // broken screen, which is the last thing a troubleshooting surface needs.
                        collectionFailed -> stringResource(Res.string.diagnostics_unavailable)
                        else -> "…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    // Fixed-shape technical text: it must not reflow into something a maintainer
                    // reading a pasted report cannot line up.
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = report != null,
                onClick = {
                    report?.let { clipboard.setText(AnnotatedString(it)) }
                    onCopied()
                },
            ) { Text(stringResource(Res.string.diagnostics_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}
