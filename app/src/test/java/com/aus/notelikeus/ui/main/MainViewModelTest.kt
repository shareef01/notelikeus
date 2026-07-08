package com.aus.notelikeus.ui.main

import app.cash.turbine.test
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private lateinit var viewModel: MainViewModel
    private lateinit var repository: NoteRepository
    private lateinit var settingsRepository: SettingsRepository
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        settingsRepository = mockk(relaxed = true)
        every { repository.getActiveNotes() } returns flowOf(emptyList())
        every { settingsRepository.isTrueDarkMode } returns flowOf(false)
        viewModel = MainViewModel(repository, settingsRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is empty`() = runTest {
        viewModel.state.test {
            val state = awaitItem()
            assertEquals(emptyList<Note>(), state.notes)
            assertEquals("", state.searchQuery)
            assertEquals(false, state.isSearching)
        }
    }

    @Test
    fun `onSearchQueryChange updates state`() = runTest {
        viewModel.state.test {
            awaitItem() // skip initial
            viewModel.onSearchQueryChange("test")
            val state = awaitItem()
            assertEquals("test", state.searchQuery)
        }
    }

    @Test
    fun `toggleSearch updates state and clears query`() = runTest {
        viewModel.onSearchQueryChange("some query")
        viewModel.state.test {
            awaitItem() // current state
            viewModel.toggleSearch()
            val state = awaitItem()
            assertEquals(true, state.isSearching)
            assertEquals("", state.searchQuery)
        }
    }
}
