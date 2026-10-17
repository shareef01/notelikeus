package com.aus.notelikeus.ui.components

import androidx.compose.foundation.layout.width
import com.aus.notelikeus.ui.theme.NotelikeusTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.domain.model.Note
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A grid card's title keeps its words whole. In a two-column grid on a 360dp phone the timestamp
 * beside the title left it about 80dp, and "Groceries" rendered as "Grocerie / s".
 */
@OptIn(ExperimentalTestApi::class)
class NoteCardTitleWrapTest {

    private val note = Note(id = 1L, title = "Groceries", content = "Oat milk", timestamp = 1_791_990_000_000L, color = 0, isPinned = true)

    private fun SemanticsNodeInteraction.lineCount(): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().lineCount
    }

    @Test
    fun `a narrow grid card keeps a one-word title on one line`() = runComposeUiTest {
        setContent {
            NotelikeusTheme(darkTheme = false) {
                NoteCard(note = note, isSelected = false, onClick = {}, onLongClick = {}, modifier = Modifier.width(168.dp))
            }
        }
        assertEquals(1, onNodeWithText("Groceries", useUnmergedTree = true).lineCount())
    }

    @Test
    fun `a wide grid card keeps the title beside the timestamp`() = runComposeUiTest {
        setContent {
            NotelikeusTheme(darkTheme = false) {
                NoteCard(note = note, isSelected = false, onClick = {}, onLongClick = {}, modifier = Modifier.width(320.dp))
            }
        }
        val titleTop = onNodeWithText("Groceries", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        val textTops = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot.top }
        // Nothing sits above the title: the timestamp shares its row instead of a line of its own.
        assertEquals(titleTop, textTops.min())
    }
}
