package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The side drawer is the one piece of the audit follow-up no unit test could reach: it is pure
 * rendering. These exercise it directly — the collapsed rail must drop the label and count badge
 * (they used to overflow the 64dp rail) while keeping the icon reachable, clicks must keep working,
 * and the row has to say which destination is current.
 */
@OptIn(ExperimentalTestApi::class)
class SideDrawerNavItemTest {

    @Test
    fun `collapsed item shows only the icon - no label, no count badge`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SideDrawerNavItem(
                    label = "Notes",
                    icon = Icons.Outlined.Lightbulb,
                    selected = false,
                    onClick = {},
                    count = 42,
                    collapsed = true
                )
            }
        }

        // Collapsed there is no visible text, so the icon has to carry the name.
        onNodeWithContentDescription("Notes").assertExists()
        onNodeWithText("Notes").assertDoesNotExist()
        onNodeWithText("42").assertDoesNotExist()
    }

    @Test
    fun `expanded item shows label and count badge`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SideDrawerNavItem(
                    label = "Archive",
                    icon = Icons.Outlined.Archive,
                    selected = false,
                    onClick = {},
                    count = 7,
                    collapsed = false
                )
            }
        }

        onNodeWithText("Archive").assertExists()
        onNodeWithText("7").assertExists()
    }

    /**
     * Expanded, the label sits visibly beside the icon, so describing the icon as well made an
     * open drawer read every destination twice. This asserted that duplication until it was
     * recognised as the bug it was.
     */
    @Test
    fun `an expanded item names its destination once`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SideDrawerNavItem(
                    label = "Archive",
                    icon = Icons.Outlined.Archive,
                    selected = false,
                    collapsed = false,
                    onClick = {}
                )
            }
        }

        assertNull(
            onNodeWithText("Archive").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.ContentDescription),
            "the visible label is the description; the icon should add nothing"
        )
    }

    /**
     * The current destination is marked by a wash and an accent bar, both invisible to anything
     * not looking at the screen. Without this the row announced "Notes, button" either way, so the
     * one piece of state the drawer exists to convey was the one piece it did not.
     */
    @Test
    fun `the current destination reports itself selected, and the others do not`() =
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    Column {
                        SideDrawerNavItem(
                            label = "Notes",
                            icon = Icons.Outlined.Lightbulb,
                            selected = true,
                            onClick = {}
                        )
                        SideDrawerNavItem(
                            label = "Archive",
                            icon = Icons.Outlined.Archive,
                            selected = false,
                            onClick = {}
                        )
                    }
                }
            }

            assertEquals(
                true,
                onNodeWithText("Notes").fetchSemanticsNode()
                    .config.getOrNull(SemanticsProperties.Selected)
            )
            assertEquals(
                false,
                onNodeWithText("Archive").fetchSemanticsNode()
                    .config.getOrNull(SemanticsProperties.Selected)
            )
        }

    @Test
    fun `clicking a collapsed item still fires the action`() = runComposeUiTest {
        var clicks = 0
        setContent {
            MaterialTheme {
                SideDrawerNavItem(
                    label = "Trash",
                    icon = Icons.Outlined.Delete,
                    selected = false,
                    onClick = { clicks++ },
                    collapsed = true
                )
            }
        }

        onNodeWithContentDescription("Trash").performClick()
        assertEquals(1, clicks)
    }
}
