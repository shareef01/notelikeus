package com.aus.notelikeus.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.runComposeUiTest
import com.aus.notelikeus.domain.model.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reorder handle used to be drag-only.
 *
 * A screen reader cannot produce a drag, so the handle announced a control its user had no way to
 * operate -- the list could be reordered by sighted touch and by nothing else. These assert the
 * custom actions that replaced that gap, and that invoking one performs the same move the drag
 * would have.
 *
 * Asserting on the semantics tree rather than on pixels is the point: this *is* the API a screen
 * reader consumes, so a test that passes here is a test of the thing that was broken.
 */
@OptIn(ExperimentalTestApi::class)
class NoteReorderSemanticsTest {

    private fun notes() = List(3) { i ->
        Note(id = i + 1L, title = "Note ${i + 1}", content = "", timestamp = 0L, color = 0)
    }

    private fun customActionLabels(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    @Test
    fun `manual order exposes move actions, bounded at the ends of the list`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                NoteStaggeredGrid(
                    notes = notes(),
                    selectedNotes = emptySet(),
                    onNoteClick = {},
                    onNoteLongClick = {},
                    onSwipeToArchive = {},
                    onSwipeToTrash = {},
                    onMoveNote = { _, _ -> },
                    columns = 1,
                    allowReorder = true
                )
            }
        }

        val cards = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNodes()
            .map { customActionLabels(it) }
            .filter { labels -> labels.any { it == "Move up" || it == "Move down" } }

        assertEquals(3, cards.size, "every note should carry move actions")
        // The first cannot move up and the last cannot move down, so the ends offer one action
        // rather than a second that would silently do nothing.
        assertEquals(listOf("Move down"), cards.first())
        assertEquals(listOf("Move up", "Move down"), cards[1])
        assertEquals(listOf("Move up"), cards.last())
    }

    @Test
    fun `invoking move down performs the same move the drag would`() = runComposeUiTest {
        val moves = mutableListOf<Pair<Int, Int>>()
        var completed = 0
        setContent {
            MaterialTheme {
                NoteStaggeredGrid(
                    notes = notes(),
                    selectedNotes = emptySet(),
                    onNoteClick = {},
                    onNoteLongClick = {},
                    onSwipeToArchive = {},
                    onSwipeToTrash = {},
                    onMoveNote = { from, to -> moves += from to to },
                    onReorderComplete = { completed++ },
                    columns = 1,
                    allowReorder = true
                )
            }
        }

        val first = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNodes()
            .first { node -> customActionLabels(node).contains("Move down") }
        val moveDown = first.config[SemanticsActions.CustomActions].first { it.label == "Move down" }

        assertTrue(moveDown.action?.invoke() == true)

        assertEquals(listOf(0 to 1), moves)
        // Positions are persisted on completion, so a keyboard move that skipped it would look
        // right until the next launch and then snap back.
        assertEquals(1, completed)
    }

    @Test
    fun `an automatic sort offers the switch instead of a move`() = runComposeUiTest {
        var blocked = 0
        setContent {
            MaterialTheme {
                NoteStaggeredGrid(
                    notes = notes(),
                    selectedNotes = emptySet(),
                    onNoteClick = {},
                    onNoteLongClick = {},
                    onSwipeToArchive = {},
                    onSwipeToTrash = {},
                    onMoveNote = { _, _ -> },
                    columns = 1,
                    allowReorder = false,
                    onReorderBlocked = { blocked++ }
                )
            }
        }

        val node = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNodes()
            .first { node ->
                customActionLabels(node).any { it.startsWith("Reordering is off") }
            }
        val labels = customActionLabels(node)

        // No move actions, because there is no move to make -- only the explanation.
        assertTrue(labels.none { it == "Move up" || it == "Move down" }, "offered: $labels")

        val explain = node.config[SemanticsActions.CustomActions].first {
            it.label.startsWith("Reordering is off")
        }
        assertTrue(explain.action?.invoke() == true)
        assertEquals(1, blocked)
    }

    @Test
    fun `a filtered list offers nothing at all`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                NoteStaggeredGrid(
                    notes = notes(),
                    selectedNotes = emptySet(),
                    onNoteClick = {},
                    onNoteLongClick = {},
                    onSwipeToArchive = {},
                    onSwipeToTrash = {},
                    onMoveNote = { _, _ -> },
                    columns = 1,
                    allowReorder = false,
                    onReorderBlocked = null
                )
            }
        }

        val reorderLabels = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNodes()
            .flatMap { customActionLabels(it) }
            .filter { it == "Move up" || it == "Move down" || it.startsWith("Reordering is off") }

        assertTrue(reorderLabels.isEmpty(), "offered: $reorderLabels")
    }
}
