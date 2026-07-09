package com.aus.notelikeus.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
import com.aus.notelikeus.ui.theme.NotelikeusTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class EditorScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun editorScreen_showsNotePlaceholder() {
        val viewModel = mockk<EditorViewModel>(relaxed = true)
        every { viewModel.state } returns MutableStateFlow(
            EditorState(
                isNoteLoaded = true,
                contentValue = TextFieldValue("")
            )
        )

        composeTestRule.setContent {
            NotelikeusTheme {
                EditorScreen(
                    viewModel = viewModel,
                    onBack = {},
                    onStageUndo = { _, _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithText("Note").assertIsDisplayed()
    }

    @Test
    fun editorScreen_rendersMarkdownContentWithoutMarkers() {
        val viewModel = mockk<EditorViewModel>(relaxed = true)
        every { viewModel.state } returns MutableStateFlow(
            EditorState(
                isNoteLoaded = true,
                content = "**hidden**",
                contentValue = TextFieldValue("**hidden**")
            )
        )

        composeTestRule.setContent {
            NotelikeusTheme {
                EditorScreen(
                    viewModel = viewModel,
                    onBack = {},
                    onStageUndo = { _, _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithText("hidden", substring = true).assertIsDisplayed()
    }
}
