package com.aus.notelikeus.ui.main.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.domain.model.ThemePreference
import com.aus.notelikeus.ui.main.CloudAccount
import com.aus.notelikeus.ui.main.CloudSyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Settings opens with the account, shows the view and sort options instead of cycling through
 * them, and keeps Sign out last.
 */
@OptIn(ExperimentalTestApi::class)
class ProfileSheetLayoutTest {

    private var viewChange: NoteViewMode? = null
    private var sortChange: NoteSortOrder? = null

    private fun ComposeUiTest.sheet(
        viewMode: NoteViewMode = NoteViewMode.GRID_2,
        account: CloudAccount = CloudAccount(isOfflineMode = true),
        status: CloudSyncStatus = CloudSyncStatus.Unknown,
    ) {
        setContent {
            MaterialTheme {
                ProfileSheet(
                    onDismiss = {},
                    noteCount = 3,
                    viewMode = viewMode,
                    sortOrder = NoteSortOrder.NEWEST,
                    themePreference = ThemePreference(),
                    isAppLockEnabled = false,
                    cloudSyncStatus = status,
                    cloudAccount = account,
                    onViewModeChange = { viewChange = it },
                    onSortOrderChange = { sortChange = it },
                    onThemeBaseChange = {},
                    onAccentChange = {},
                    onAmoledChange = {},
                    onAppLockChange = {},
                    onExportClick = {},
                    onImportClick = {},
                )
            }
        }
    }

    @Test
    fun `the account row carries the status and note count, and Insights is gone`() = runComposeUiTest {
        sheet()

        onNodeWithText("Not signed in", useUnmergedTree = true).assertExists()
        onNodeWithText("Notes stay on this device · 3 notes", useUnmergedTree = true).assertExists()
        onNodeWithText("INSIGHTS", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Total Notes", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Sign out", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a signed-in account shows its sync state, and Sign out comes last`() = runComposeUiTest {
        sheet(account = CloudAccount(email = "a@example.com", isSignedIn = true), status = CloudSyncStatus.Synced)

        onNodeWithText("a@example.com", useUnmergedTree = true).assertExists()
        onNodeWithText("Up to date · 3 notes", useUnmergedTree = true).assertExists()
        onNodeWithText("Sign out", useUnmergedTree = true).performScrollTo().assertExists()
    }

    @Test
    fun `view and sort are chosen directly`() = runComposeUiTest {
        sheet()

        onNodeWithText("List", useUnmergedTree = true).performScrollTo().performClick()
        onNodeWithText("Oldest first", useUnmergedTree = true).performScrollTo().performClick()

        assertEquals(NoteViewMode.LIST, viewChange)
        assertEquals(NoteSortOrder.OLDEST, sortChange)
    }

    @Test
    fun `choosing Grid keeps a column count picked elsewhere`() = runComposeUiTest {
        sheet(viewMode = NoteViewMode.GRID_4)

        onNodeWithText("Grid", useUnmergedTree = true).performScrollTo().performClick()

        assertNull(viewChange)
    }
}
