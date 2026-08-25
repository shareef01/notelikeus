package com.aus.notelikeus.ui.editor.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import com.aus.notelikeus.domain.model.ChecklistItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A checklist is a column of identical controls, so each one has to say what it belongs to.
 *
 * The checkbox's label lives in a separate text-field node beside it, so on its own the checkbox
 * announced "checkbox, checked" — the same thing for every row on the list. The remove button was
 * worse in the same way: "Remove item", repeated down the column, identifying nothing.
 */
@OptIn(ExperimentalTestApi::class)
class ChecklistSemanticsTest {

    private val items = listOf(
        ChecklistItem(id = 1L, text = "Bread", isChecked = false, position = 0),
        ChecklistItem(id = 2L, text = "Milk", isChecked = true, position = 1),
        ChecklistItem(id = 3L, text = "", isChecked = false, position = 2)
    )

    private fun descriptions(nodes: List<androidx.compose.ui.semantics.SemanticsNode>) =
        nodes.flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    @Test
    fun `each checkbox names the item it checks`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ChecklistUI(
                    items = items,
                    onUpdate = { _, _, _ -> },
                    onRemove = {},
                    onAdd = {},
                    contentColor = Color.Black
                )
            }
        }

        onNodeWithContentDescription("Bread").assertExists()
        onNodeWithContentDescription("Milk").assertExists()
        // An empty item still needs a name, or its checkbox is anonymous.
        onNodeWithContentDescription("Empty item").assertExists()
    }

    @Test
    fun `each remove button names what it removes`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ChecklistUI(
                    items = items,
                    onUpdate = { _, _, _ -> },
                    onRemove = {},
                    onAdd = {},
                    contentColor = Color.Black
                )
            }
        }

        onNodeWithContentDescription("Remove Bread").assertExists()
        onNodeWithContentDescription("Remove Milk").assertExists()
        onNodeWithContentDescription("Remove Empty item").assertExists()
    }

    @Test
    fun `no two controls in the list share a description`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ChecklistUI(
                    items = items,
                    onUpdate = { _, _, _ -> },
                    onRemove = {},
                    onAdd = {},
                    contentColor = Color.Black
                )
            }
        }

        val all = descriptions(
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
                .fetchSemanticsNodes()
        )

        assertEquals(all.size, all.toSet().size, "duplicate descriptions: $all")
        assertTrue(all.isNotEmpty())
    }

    @Test
    fun `checked state is reported per item`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ChecklistUI(
                    items = items,
                    onUpdate = { _, _, _ -> },
                    onRemove = {},
                    onAdd = {},
                    contentColor = Color.Black
                )
            }
        }

        assertEquals(
            androidx.compose.ui.state.ToggleableState.Off,
            onNodeWithContentDescription("Bread").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.ToggleableState)
        )
        assertEquals(
            androidx.compose.ui.state.ToggleableState.On,
            onNodeWithContentDescription("Milk").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.ToggleableState)
        )
    }
}
