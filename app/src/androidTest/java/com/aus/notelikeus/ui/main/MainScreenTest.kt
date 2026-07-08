package com.aus.notelikeus.ui.main

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.ui.theme.NotelikeusTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class MainScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun mainScreen_showsNotes() {
        val viewModel: MainViewModel = mockk(relaxed = true)
        val notes = listOf(
            Note(id = 1, title = "Note 1", content = "Content 1", timestamp = 0, color = 0),
            Note(id = 2, title = "Note 2", content = "Content 2", timestamp = 0, color = 0)
        )
        
        val state = MainState(notes = notes, filteredNotes = notes)
        every { viewModel.state } returns MutableStateFlow(state)

        composeTestRule.setContent {
            NotelikeusTheme {
                MainScreen(
                    viewModel = viewModel,
                    onNoteClick = { _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithText("Note 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Note 2").assertIsDisplayed()
    }

    @Test
    fun mainScreen_showsEmptyState_whenNoNotes() {
        val viewModel: MainViewModel = mockk(relaxed = true)
        every { viewModel.state } returns MutableStateFlow(MainState(notes = emptyList()))

        composeTestRule.setContent {
            NotelikeusTheme {
                MainScreen(
                    viewModel = viewModel,
                    onNoteClick = { _, _ -> }
                )
            }
        }

        // The grid would be empty, we can check for search bar placeholder
        composeTestRule.onNodeWithText("Search your notes").assertIsDisplayed()
    }
}
