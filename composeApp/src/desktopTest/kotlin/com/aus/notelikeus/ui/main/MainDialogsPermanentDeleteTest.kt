package com.aus.notelikeus.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * NL-11: the only selection dialog left is the one for deleting from Trash, which is permanent.
 * Moving to Trash no longer asks, so the dialog has no "moved to trash" wording left to show.
 */
@OptIn(ExperimentalTestApi::class)
class MainDialogsPermanentDeleteTest {

    private fun dialogs(
        show: Boolean = true,
        selectedCount: Int = 1,
        onConfirm: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ): @Composable () -> Unit = {
        MaterialTheme {
            MainDialogs(
                showCloudSignOutConfirm = false,
                showCloudRestoreConfirm = false,
                showEmptyTrashConfirm = false,
                showPermanentDeleteConfirm = show,
                selectedCount = selectedCount,
                onCloudSignOutConfirmDismiss = {},
                onCloudSignOut = {},
                onCloudRestoreConfirmDismiss = {},
                onConfirmCloudRestore = {},
                onUploadLocalNotesAfterWipe = {},
                onEmptyTrashConfirmDismiss = {},
                onConfirmEmptyTrash = {},
                onPermanentDeleteConfirmDismiss = onDismiss,
                onConfirmPermanentDelete = onConfirm,
            )
        }
    }

    @Test
    fun `a permanent delete still asks, and says it is permanent`() = runComposeUiTest {
        setContent(dialogs(selectedCount = 2))

        onNodeWithText("Delete 2 notes?").assertExists()
        onNodeWithText("These notes will be permanently deleted.").assertExists()
        onNodeWithText("moved to trash", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun `confirming deletes, cancelling does not`() = runComposeUiTest {
        var confirms = 0
        var dismissals = 0
        setContent(dialogs(onConfirm = { confirms++ }, onDismiss = { dismissals++ }))

        onNodeWithText("Cancel").performClick()
        assertEquals(0, confirms, "Cancel deleted the notes")
        assertEquals(1, dismissals)

        onNodeWithText("Delete").performClick()
        assertEquals(1, confirms, "Delete did not reach the permanent delete")
    }

    @Test
    fun `nothing is shown unless a permanent delete was asked for`() = runComposeUiTest {
        setContent(dialogs(show = false))

        onNodeWithText("Delete note?").assertDoesNotExist()
    }
}
