package com.aus.notelikeus.ui.components

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aus.notelikeus.ui.theme.Spacing

/**
 * The app's snackbar host.
 *
 * There were two kinds of snackbar before this: the main screen's, styled inline with an inverse
 * surface and lifted clear of the FAB, and the editor's, which was a bare `SnackbarHost` on
 * library defaults. So "reminder set" and "note archived" — messages a user sees minutes apart in
 * the same session — did not look like they came from the same app, and the editor's could sit
 * under the system navigation bar.
 *
 * [aboveFab] lifts the snackbar clear of a floating action button. It is a parameter rather than
 * something inferred, because only the caller knows whether a FAB is currently on screen; the
 * main list hides its FAB in selection mode and in Trash.
 */
@Composable
fun AppSnackbar(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    aboveFab: Boolean = false
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier.navigationBarsPadding()
    ) { data ->
        Snackbar(
            snackbarData = data,
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            actionColor = MaterialTheme.colorScheme.inversePrimary,
            modifier = Modifier.padding(
                start = Spacing.gutter,
                end = Spacing.gutter,
                bottom = if (aboveFab) Spacing.snackbarAboveFab else Spacing.gutter
            )
        )
    }
}
