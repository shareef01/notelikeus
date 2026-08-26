package com.aus.notelikeus.ui.main.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A settings toggle has to report whether it is on.
 *
 * The row put `onClick` on itself *and* a live `Switch` in its trailing slot — one action wearing
 * two hit targets — so the merged node announced "Pure black, button". That is the state of the
 * setting withheld from precisely the person who cannot see the switch.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsRowSemanticsTest {

    @Test
    fun `a toggle row announces as a switch carrying its state`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    SettingsToggleListItem(
                        icon = Icons.Outlined.Settings,
                        title = "Pure black",
                        subtitle = "For OLED screens",
                        checked = true,
                        onCheckedChange = {}
                    )
                    SettingsToggleListItem(
                        icon = Icons.Outlined.Settings,
                        title = "Auto sync",
                        subtitle = "Sync in the background",
                        checked = false,
                        onCheckedChange = {}
                    )
                }
            }
        }

        val on = onNodeWithText("Pure black").fetchSemanticsNode()
        val off = onNodeWithText("Auto sync").fetchSemanticsNode()

        assertEquals(Role.Switch, on.config.getOrNull(SemanticsProperties.Role))
        assertEquals(ToggleableState.On, on.config.getOrNull(SemanticsProperties.ToggleableState))
        assertEquals(ToggleableState.Off, off.config.getOrNull(SemanticsProperties.ToggleableState))
    }

    @Test
    fun `tapping a toggle row flips it exactly once`() = runComposeUiTest {
        val changes = mutableListOf<Boolean>()
        setContent {
            MaterialTheme {
                SettingsToggleListItem(
                    icon = Icons.Outlined.Settings,
                    title = "Pure black",
                    subtitle = "For OLED screens",
                    checked = false,
                    onCheckedChange = { changes += it }
                )
            }
        }

        onNodeWithText("Pure black").performClick()

        assertEquals(listOf(true), changes, "a single tap must toggle exactly once")
    }

    /** A row that opens something is a button, and the chevron beside it is decoration. */
    @Test
    fun `a cycle row announces as a button`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsCycleListItem(
                    icon = Icons.Outlined.Settings,
                    title = "Theme",
                    subtitle = "System",
                    onClick = {}
                )
            }
        }

        assertEquals(
            Role.Button,
            onNodeWithText("Theme").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role)
        )
    }

    /**
     * The leading icon used to carry the title as its description while the title sat visibly on
     * the next line, so every setting announced itself twice.
     */
    @Test
    fun `a settings row names itself once`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsCycleListItem(
                    icon = Icons.Outlined.Settings,
                    title = "Theme",
                    subtitle = "System",
                    onClick = {}
                )
            }
        }

        assertNull(
            onNodeWithText("Theme").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.ContentDescription),
            "the visible title is the description; the icon should add nothing"
        )
    }
}
