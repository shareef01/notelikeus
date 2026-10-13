package com.aus.notelikeus.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The "upload this device's notes" action added with F-13, in the dialog it lives in.
 *
 * It is the only way a user can answer a wipe on Android or Windows, and `NoteSyncEngine` refuses
 * every upload until they do — so if this button is missing, mislabelled or wired to the wrong
 * callback, that device is stuck with no way out. The engine's half of that has its own tests;
 * this is the half that decides whether a user can reach it.
 *
 * Writing these is what found the label bug: the strings were the only ones in
 * `composeResources/values/strings.xml` written with an Android-style `\'`, and this Compose
 * resource loader does not unescape it, so the button read `Upload this device's notes` with a
 * visible backslash. The repo's other eleven apostrophes are typographic (U+2019), which needs no
 * escaping. Assertions here are written against the real resource text for that reason, and one
 * of them refuses a stray backslash outright.
 */
@OptIn(ExperimentalTestApi::class)
class MainDialogsCloudWipeTest {

    /** As rendered, including the typographic apostrophe the resource file uses. */
    private fun label() = "Upload this device\u2019s notes"

    /** Every callback is required, so the ones a case does not care about are no-ops. */
    private fun dialogs(
        showCloudRestoreConfirm: Boolean = true,
        onUploadLocalNotesAfterWipe: () -> Unit = {},
        onConfirmCloudRestore: () -> Unit = {},
    ): @androidx.compose.runtime.Composable () -> Unit = {
        MaterialTheme {
            MainDialogs(
                showCloudSignOutConfirm = false,
                showCloudRestoreConfirm = showCloudRestoreConfirm,
                showEmptyTrashConfirm = false,
                showDeleteConfirm = false,
                selectedCount = 0,
                isTrashedFilter = false,
                onCloudSignOutConfirmDismiss = {},
                onCloudSignOut = {},
                onCloudRestoreConfirmDismiss = {},
                onConfirmCloudRestore = onConfirmCloudRestore,
                onUploadLocalNotesAfterWipe = onUploadLocalNotesAfterWipe,
                onEmptyTrashConfirmDismiss = {},
                onConfirmEmptyTrash = {},
                onDeleteConfirmDismiss = {},
                onConfirmDelete = {},
            )
        }
    }

    @Test
    fun `the cloud dialog offers the wipe answer next to restore`() = runComposeUiTest {
        setContent(dialogs())

        onNodeWithText(label()).assertExists()
        onNodeWithText("Restore").assertExists()
    }

    @Test
    fun `no user-facing string in the dialog leaks an escape`() = runComposeUiTest {
        setContent(dialogs())

        // Written after the label bug above: a backslash that survives the resource loader reaches
        // the user's screen, and nothing else in the suite would notice.
        onNodeWithText("\\", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the wipe answer is explained, not just labelled`() = runComposeUiTest {
        setContent(dialogs())

        // The label alone does not say why the notes are not already in the cloud, and this is the
        // one place the user learns it.
        onNodeWithText(
            "Deleted your cloud notes from another device? This device still has its notes, and " +
                "they are not uploaded on their own. Uploading them again puts them back in your " +
                "cloud account as new notes.",
        ).assertExists()
    }

    @Test
    fun `pressing it calls the wipe answer, not restore`() = runComposeUiTest {
        var uploads = 0
        var restores = 0
        setContent(
            dialogs(
                onUploadLocalNotesAfterWipe = { uploads++ },
                onConfirmCloudRestore = { restores++ },
            ),
        )

        onNodeWithText(label()).performClick()

        assertEquals(1, uploads, "the button did not reach the wipe answer")
        assertEquals(0, restores, "the button ran a cloud restore instead")
    }
}
