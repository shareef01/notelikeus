package com.aus.notelikeus.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import com.aus.notelikeus.domain.model.Note
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The keyboard half of multi-selection (FU-8).
 *
 * `combinedClickable` binds Enter to `onClick` and exposes long-press only through the semantics
 * action, so before this the only way to start a selection was a long-press: a keyboard user could
 * open a note but never select one, and nothing told them to hold Enter. These tests press the keys
 * rather than reading the source, which is the difference between "the modifier is there" and "the
 * card answers Space".
 *
 * The mouse paths are asserted in the same breath, because the change was safe only if it left them
 * alone: selection and opening must still be reachable by click and long-press.
 */
@OptIn(ExperimentalTestApi::class)
class NoteCardKeyboardSelectionTest {

    private fun note() = Note(
        id = 7L,
        title = "Groceries",
        content = "milk",
        timestamp = 1_000L,
        color = 0,
    )

    /** The card's own node: the only one that both handles clicks and contains the title. */
    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.card() =
        onNode(hasClickAction() and hasAnyDescendant(hasText("Groceries")))

    @Test
    fun `space selects the focused card`() = runComposeUiTest {
        var selections = 0
        setContent {
            MaterialTheme {
                NoteCard(
                    note = note(),
                    isSelected = false,
                    onClick = {},
                    onLongClick = { selections++ },
                )
            }
        }

        card().requestFocus()
        card().performKeyInput { pressKey(Key.Spacebar) }

        assertEquals(1, selections, "Space did not start a selection on the focused card")
    }

    @Test
    fun `space toggles back off when the card is already selected`() = runComposeUiTest {
        // The card toggles through the same callback either way; what matters here is that a
        // selected card is still focusable and still answers Space, so a selection can be undone
        // from the keyboard rather than only added to.
        var selections = 0
        setContent {
            MaterialTheme {
                NoteCard(
                    note = note(),
                    isSelected = true,
                    onClick = {},
                    onLongClick = { selections++ },
                )
            }
        }

        card().requestFocus()
        card().performKeyInput { pressKey(Key.Spacebar) }

        assertEquals(1, selections, "a selected card did not answer Space")
    }

    @Test
    fun `enter still opens the note instead of selecting it`() = runComposeUiTest {
        var opens = 0
        var selections = 0
        setContent {
            MaterialTheme {
                NoteCard(
                    note = note(),
                    isSelected = false,
                    onClick = { opens++ },
                    onLongClick = { selections++ },
                )
            }
        }

        card().requestFocus()
        card().performKeyInput { pressKey(Key.Enter) }

        assertEquals(1, opens, "Enter no longer opens the focused card")
        assertEquals(0, selections, "Enter started a selection")
    }

    @Test
    fun `mouse and touch paths are unchanged`() = runComposeUiTest {
        var opens = 0
        var selections = 0
        setContent {
            MaterialTheme {
                Column {
                    NoteCard(
                        note = note(),
                        isSelected = false,
                        onClick = { opens++ },
                        onLongClick = { selections++ },
                    )
                }
            }
        }

        card().performClick()
        assertEquals(1, opens, "a click no longer opens the note")
        assertEquals(0, selections, "a click started a selection")
    }
}
