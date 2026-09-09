package com.aus.notelikeus.ui.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.aus.notelikeus.domain.reminder.ReminderTime
import com.aus.notelikeus.util.DateUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reminder dialog's two jobs: keep the quick presets one tap, and let a user reach an exact
 * moment without leaving the dialog.
 *
 * The presets are the reason this is asserted rather than eyeballed — an earlier revision of this
 * dialog had an OK button that called `onDismiss`, so the confirming action was a second Cancel
 * (F15). Every control here is checked for the action it actually performs.
 */
@OptIn(ExperimentalTestApi::class)
class ReminderDialogTest {

    private val oneHour = ReminderTime.ONE_HOUR_MS

    @Test
    fun `presets stay one tap and confirm directly`() = runComposeUiTest {
        var confirmed: Long? = null
        setContent {
            MaterialTheme {
                ReminderDialog(
                    initialTimestamp = DateUtils.currentTimeMillis() + oneHour,
                    onConfirm = { confirmed = it },
                    onRemove = null,
                    onDismiss = {},
                )
            }
        }

        val before = DateUtils.currentTimeMillis()
        onNodeWithText("In 1 hour").performClick()
        val chosen = assertNotNull(confirmed, "a preset must confirm on the first tap")
        assertTrue(chosen >= before + oneHour, "In 1 hour must be an hour out, not now")
    }

    @Test
    fun `the exact picker is offered alongside the presets`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ReminderDialog(
                    initialTimestamp = DateUtils.currentTimeMillis() + oneHour,
                    onConfirm = {},
                    onRemove = null,
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("In 1 hour").assertIsDisplayed()
        onNodeWithText("Tomorrow 9:00").assertIsDisplayed()
        onNodeWithText("Next week").assertIsDisplayed()

        val exact = onNodeWithText("Choose date & time").fetchSemanticsNode()
        assertEquals(
            Role.Button,
            exact.config.getOrNull(SemanticsProperties.Role),
            "the exact-picker row must announce as a button, not as plain text",
        )
    }

    @Test
    fun `choosing an exact time walks date then time and confirms an instant`() = runComposeUiTest {
        var confirmed: Long? = null
        setContent {
            MaterialTheme {
                ReminderDialog(
                    initialTimestamp = DateUtils.currentTimeMillis() + oneHour,
                    onConfirm = { confirmed = it },
                    onRemove = null,
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Choose date & time").performClick()
        onNodeWithText("Pick a date").assertIsDisplayed()
        // The picker opens on the next whole hour, so a date is already selected and Next is live.
        onNodeWithText("Next").assertIsEnabled().performClick()

        onNodeWithText("Pick a time").assertIsDisplayed()
        onNodeWithText("Back").assertIsDisplayed()
        onNodeWithText("OK").performClick()

        val chosen = assertNotNull(confirmed, "OK on the time step must confirm a reminder")
        // Pre-populated from the next whole hour, so confirming without touching either picker
        // reproduces exactly that instant.
        assertEquals(
            ReminderTime.nextWholeHour(DateUtils.currentTimeMillis()),
            chosen,
            "confirming an untouched picker must not move the reminder",
        )
    }

    @Test
    fun `back returns to the date step without confirming`() = runComposeUiTest {
        var confirmed: Long? = null
        var dismissed = false
        setContent {
            MaterialTheme {
                ReminderDialog(
                    initialTimestamp = DateUtils.currentTimeMillis() + oneHour,
                    onConfirm = { confirmed = it },
                    onRemove = null,
                    onDismiss = { dismissed = true },
                )
            }
        }

        onNodeWithText("Choose date & time").performClick()
        onNodeWithText("Next").performClick()
        onNodeWithText("Back").performClick()

        onNodeWithText("Pick a date").assertIsDisplayed()
        assertEquals(null, confirmed, "stepping back must not set a reminder")
        assertTrue(!dismissed, "stepping back must not close the dialog")
    }

    @Test
    fun `an existing reminder is shown and can be removed`() = runComposeUiTest {
        var removed = false
        val existing = ReminderTime.nextWholeHour(DateUtils.currentTimeMillis())
        setContent {
            MaterialTheme {
                ReminderDialog(
                    initialTimestamp = existing,
                    onConfirm = {},
                    onRemove = { removed = true },
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Remove").assertIsDisplayed().performClick()
        assertTrue(removed, "Remove must clear the reminder")
    }

    @Test
    fun `an existing reminder pre-populates the exact picker`() = runComposeUiTest {
        var confirmed: Long? = null
        // Deliberately not a round hour: a picker that defaulted to "now" instead of reading the
        // existing reminder would silently move it on confirm.
        val existing = assertNotNull(
            ReminderTime.exactReminder(
                ReminderTime.civilDateToUtcMillis(2099, 4, 17),
                16,
                42,
            ),
        )
        setContent {
            MaterialTheme {
                ReminderDialog(
                    initialTimestamp = existing,
                    onConfirm = { confirmed = it },
                    onRemove = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Choose date & time").performClick()
        onNodeWithText("Next").performClick()
        onNodeWithText("OK").performClick()

        assertEquals(
            existing,
            confirmed,
            "editing an existing reminder and confirming it unchanged must be a no-op",
        )
    }
}
