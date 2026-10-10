package com.aus.notelikeus.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * What a screen reader is told about a note card.
 *
 * The picture on a card is decorative (no content description of its own), so the card's description
 * is the only place a TalkBack user learns the note has an image. The web card says "Has image" in
 * the same place and order; these pin that the Compose card does too.
 */
@OptIn(ExperimentalTestApi::class)
class NoteCardAccessibilityTest {

    private fun note(
        attachments: List<Attachment> = emptyList(),
        isPinned: Boolean = false,
        reminderTimestamp: Long? = null,
    ) = Note(
        id = 1L,
        title = "Receipts",
        content = "",
        timestamp = 0L,
        color = 0,
        isPinned = isPinned,
        reminderTimestamp = reminderTimestamp,
        attachments = attachments,
    )

    private val picture = Attachment(id = "pic", noteId = 1L, storagePath = "r2:owners/o/notes/1/pic", mimeType = "image/png")

    /** The description the card's merged semantics node exposes. */
    private fun ComposeUiTest.describedAs(note: Note, listStyle: Boolean = false, isSelected: Boolean = false): String {
        setContent {
            MaterialTheme {
                NoteCard(
                    note = note,
                    isSelected = isSelected,
                    listStyle = listStyle,
                    onClick = {},
                    onLongClick = {},
                )
            }
        }
        waitForIdle()
        val descriptions = onRoot(useUnmergedTree = false).fetchSemanticsNode().let { root ->
            generateSequence(listOf(root)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }
                .flatten()
                .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }
                .toList()
        }
        return assertNotNull(descriptions.firstOrNull { it.startsWith("Receipts") }, "no card description found in $descriptions")
    }

    @Test
    fun `a note with a picture says so`() = runComposeUiTest {
        assertEquals("Receipts, Has image", describedAs(note(attachments = listOf(picture))))
    }

    @Test
    fun `a note without attachments does not claim an image`() = runComposeUiTest {
        assertEquals("Receipts", describedAs(note()))
    }

    @Test
    fun `an image is announced after the pin and the reminder, and before the selection`() = runComposeUiTest {
        val described = describedAs(
            note(attachments = listOf(picture), isPinned = true, reminderTimestamp = 1_000L),
            isSelected = true,
        )

        assertEquals("Receipts, Pinned, Reminder set, Has image, Selected", described)
    }

    @Test
    fun `a list row says so as well`() = runComposeUiTest {
        assertEquals("Receipts, Has image", describedAs(note(attachments = listOf(picture)), listStyle = true))
    }
}
