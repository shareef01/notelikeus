package com.aus.notelikeus.ui.editor.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.aus.notelikeus.domain.model.Label
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import androidx.compose.ui.semantics.Role

/**
 * The label list is a set of checkboxes, and has to say so.
 *
 * Each row used to be a clickable `ListItem` wrapping a `Checkbox` with its own `onCheckedChange` --
 * one action wearing two hit targets. The row therefore announced as a button carrying no checked
 * state, so the only thing the list exists to communicate -- which labels are on -- was the one
 * thing it did not communicate to anyone who was not looking at it.
 */
@OptIn(ExperimentalTestApi::class)
class EditorBottomSheetSemanticsTest {

    private val work = Label(id = 1L, name = "Work")
    private val home = Label(id = 2L, name = "Home")

    @Composable
    private fun Sheet(selected: List<Label>, onToggle: (Label) -> Unit = {}) {
        MaterialTheme {
            EditorBottomSheet(
                selectedColor = 0,
                onColorSelect = {},
                allLabels = listOf(work, home),
                selectedLabels = selected,
                onLabelToggle = onToggle,
                onCreateLabel = {},
                onDeleteNote = {},
                onShareNote = {},
                onDismiss = {}
            )
        }
    }

    @Test
    fun `a label row reports its checked state, not just its name`() = runComposeUiTest {
        setContent { Sheet(selected = listOf(work)) }

        val checked = onNodeWithText("Work").fetchSemanticsNode()
        val unchecked = onNodeWithText("Home").fetchSemanticsNode()

        assertEquals(
            ToggleableState.On,
            checked.config.getOrNull(SemanticsProperties.ToggleableState)
        )
        assertEquals(
            ToggleableState.Off,
            unchecked.config.getOrNull(SemanticsProperties.ToggleableState)
        )
    }

    /**
     * The role is the half a screen reader speaks. A clickable row wrapping a live checkbox
     * announced "Work, button"; a toggleable row announces "Work, checkbox, checked".
     *
     * Counting toggleable nodes does *not* distinguish the two, because `ListItem`'s clickable
     * merges its descendants and the inner checkbox's state merges upward either way -- which is
     * exactly why the row could carry the state and still describe itself wrongly.
     */
    @Test
    fun `a label row announces as a checkbox, not a button`() = runComposeUiTest {
        setContent { Sheet(selected = emptyList()) }

        assertEquals(
            Role.Checkbox,
            onNodeWithText("Work").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role)
        )
    }

    @Test
    fun `toggling a row reports the label once`() = runComposeUiTest {
        val toggled = mutableListOf<Label>()
        setContent { Sheet(selected = emptyList(), onToggle = { toggled += it }) }

        onNodeWithText("Home").performClick()

        assertEquals(listOf(home), toggled, "a single tap must toggle exactly once")
    }

    /** The row's own text says "Delete"; describing the icon too said it twice. */
    @Test
    fun `the delete action names itself once`() = runComposeUiTest {
        setContent { Sheet(selected = emptyList()) }

        val description = onNodeWithText("Delete").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.ContentDescription)

        assertNull(description, "the visible label is the description; the icon should add nothing")
    }

    @Test
    fun `the colour swatches are named`() = runComposeUiTest {
        setContent { Sheet(selected = emptyList()) }

        val named = onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)
        ).fetchSemanticsNodes().flatMap {
            it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }

        // A palette of unlabelled circles is unusable without sight.
        assertTrue(named.isNotEmpty(), "no swatch carried a name")
    }
}
