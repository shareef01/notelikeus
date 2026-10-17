package com.aus.notelikeus.ui.main.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.domain.model.ThemePreference
import kotlin.test.Test

/**
 * Settings' About section lists only things that do something. It used to open with a
 * "Premium Subscription — Coming soon" row: not tappable, leading nowhere, and absent on web.
 */
@OptIn(ExperimentalTestApi::class)
class ProfileSheetAboutTest {

    @Test
    fun `about offers no dead coming-soon row`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ProfileSheet(
                    onDismiss = {},
                    noteCount = 3,
                    viewMode = NoteViewMode.GRID_2,
                    sortOrder = NoteSortOrder.NEWEST,
                    themePreference = ThemePreference(),
                    isAppLockEnabled = false,
                    onViewModeChange = {},
                    onSortOrderChange = {},
                    onThemeBaseChange = {},
                    onAccentChange = {},
                    onAmoledChange = {},
                    onAppLockChange = {},
                    onExportClick = {},
                    onImportClick = {},
                )
            }
        }
        onNodeWithText("Privacy policy", useUnmergedTree = true).performScrollTo().assertExists()
        onNodeWithText("Premium Subscription", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Coming soon", useUnmergedTree = true).assertDoesNotExist()
    }
}
