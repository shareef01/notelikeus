package com.aus.notelikeus.ui.main

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.ui.theme.NotelikeusTheme
import io.mockk.every
import io.mockk.mockk
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/** Phone-sized window, so these assertions exercise the single-pane layout. */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
private fun compactWindowSizeClass(): WindowSizeClass =
    WindowSizeClass.calculateFromSize(DpSize(400.dp, 800.dp))

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
                    onNoteClick = { _ -> },
                    onEditLabels = {},
                    windowSizeClass = compactWindowSizeClass()
                )
            }
        }

        composeTestRule.onNodeWithText("Note 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Note 2").assertIsDisplayed()
    }

    @Test
    fun mainScreen_showsEmptyState_whenNoNotes() {
        val viewModel: MainViewModel = mockk(relaxed = true)
        every { viewModel.state } returns MutableStateFlow(MainState(notes = emptyList(), filteredNotes = emptyList()))

        composeTestRule.setContent {
            NotelikeusTheme {
                MainScreen(
                    viewModel = viewModel,
                    onNoteClick = { _ -> },
                    onEditLabels = {},
                    windowSizeClass = compactWindowSizeClass()
                )
            }
        }

        composeTestRule.onNodeWithText("Notes you add appear here").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add note").assertIsDisplayed()
    }

    @Test
    fun mainScreen_hidesFilterRowInSelectionMode() {
        val viewModel: MainViewModel = mockk(relaxed = true)
        val notes = listOf(
            Note(id = 1, title = "Note 1", content = "Content 1", timestamp = 0, color = 0)
        )
        val state = MainState(
            notes = notes,
            filteredNotes = notes,
            selectedNotes = setOf(1L)
        )
        every { viewModel.state } returns MutableStateFlow(state)

        composeTestRule.setContent {
            NotelikeusTheme {
                MainScreen(
                    viewModel = viewModel,
                    onNoteClick = { _ -> },
                    onEditLabels = {},
                    windowSizeClass = compactWindowSizeClass()
                )
            }
        }

        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeTestRule.onNodeWithText("All Colors").assertDoesNotExist()
    }
}
