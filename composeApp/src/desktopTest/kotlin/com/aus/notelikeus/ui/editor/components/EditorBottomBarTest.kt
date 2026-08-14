package com.aus.notelikeus.ui.editor.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor's bottom bar is where a crash slipped through to `main`.
 *
 * It formats the note's timestamp during composition, unconditionally, so anything that throws in
 * [com.aus.notelikeus.util.DateUtils] takes the whole editor down the instant it opens — which is
 * exactly what happened on Android, where `formatTime` passed a null Context to the platform
 * formatter and every attempt to open a note killed the process.
 *
 * These render the composable for real rather than asserting on formatted strings: the failure
 * mode is an exception during composition, so *composing at all* is most of the test. The bug
 * itself is guarded on the Android side by DateUtilsAndroidTest; this guards the composition that
 * calls it, on every platform that shares this code.
 */
@OptIn(ExperimentalTestApi::class)
class EditorBottomBarTest {

    private val timestamp = 1_700_000_000_000L

    @Test
    fun `composes with only a timestamp`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                EditorBottomBar(
                    timestamp = timestamp,
                    onMoreClick = {},
                    contentColor = Color.White
                )
            }
        }
        // Reaching an assertion means composition completed without throwing.
        onNodeWithContentDescription("More options").assertExists()
    }

    @Test
    fun `composes with a reminder, which formats both a date and a time`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                EditorBottomBar(
                    timestamp = timestamp,
                    // The reminder path calls formatDateTime *and* formatTime, so it touches
                    // strictly more of DateUtils than the default path does.
                    reminderTimestamp = timestamp + 86_400_000L,
                    onMoreClick = {},
                    contentColor = Color.White
                )
            }
        }
        onNodeWithContentDescription("More options").assertExists()
    }

    @Test
    fun `composes for an epoch timestamp`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                EditorBottomBar(
                    timestamp = 0L,
                    onMoreClick = {},
                    contentColor = Color.White
                )
            }
        }
        onNodeWithContentDescription("More options").assertExists()
    }

    @Test
    fun `the overflow action reports clicks`() = runComposeUiTest {
        var clicks = 0
        setContent {
            MaterialTheme {
                EditorBottomBar(
                    timestamp = timestamp,
                    onMoreClick = { clicks++ },
                    contentColor = Color.White
                )
            }
        }

        onNodeWithContentDescription("More options").performClick()

        assertEquals(1, clicks)
        assertTrue(clicks > 0)
    }
}
