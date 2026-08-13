package com.aus.notelikeus.ui.main.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The collapsed side drawer is the one piece of the audit follow-up no unit test could reach:
 * it is pure rendering. These exercise the rail mode directly — label and count badge must
 * disappear (they used to overflow the 64dp rail), the icon must stay accessible, and clicks
 * must keep working.
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
        onNodeWithContentDescription("Archive").assertExists()
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
