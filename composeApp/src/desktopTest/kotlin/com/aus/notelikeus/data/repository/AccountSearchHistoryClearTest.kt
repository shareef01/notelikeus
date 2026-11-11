package com.aus.notelikeus.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.sync.RecordingSyncCoordinator
import com.aus.notelikeus.domain.model.NoteQuery
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import com.aus.notelikeus.domain.platform.ReminderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NL-02: recent searches and saved filters are the account's words, but they live in preferences,
 * not the notes database, so wiping the account's notes left them for the next account on the
 * device. Real Room database and real file-backed DataStore, wired the way Koin wires them.
 */
class AccountSearchHistoryClearTest {

    private object NoWidgets : PlatformWidgetManager {
        override suspend fun refreshWidgets() = Unit
    }

    private object NoReminders : ReminderManager {
        override fun scheduleReminder(noteId: Long, timestamp: Long) = Unit
        override fun cancelReminder(noteId: Long) = Unit
    }

    private lateinit var tempDir: File
    private lateinit var database: NotelikeusDatabase
    private lateinit var settings: SettingsRepositoryImpl

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("account-search-history").toFile()
        database = Room.databaseBuilder<NotelikeusDatabase>(name = File(tempDir, "test.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath {
            File(tempDir, "settings.preferences_pb").toOkioPath()
        }
        settings = SettingsRepositoryImpl(store, NoWidgets)
    }

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    private fun notes() = NoteRepositoryImpl(
        database = database,
        noteDao = database.noteDao,
        labelDao = database.labelDao,
        reminderManager = NoReminders,
        widgetManager = NoWidgets,
        syncCoordinator = RecordingSyncCoordinator(),
        ioDispatcher = Dispatchers.Unconfined,
    ).apply {
        clearAccountScopedPreferences = { settings.clearAccountSearchHistory() }
    }

    @Test
    fun `clearing the account's data forgets its searches and saved filters`() = runTest {
        settings.addRecentSearch("dr smith invoice")
        settings.saveFilter("Invoices from Dr Smith", NoteQuery(text = "invoice"))

        notes().clearAllUserData()

        assertTrue(settings.recentSearches.first().isEmpty())
        assertTrue(settings.savedFilters.first().isEmpty())
    }

    @Test
    fun `device preferences are not the account's and survive the clear`() = runTest {
        settings.setNoteViewMode(NoteViewMode.LIST)
        settings.addRecentSearch("anything")

        notes().clearAllUserData()

        assertEquals(NoteViewMode.LIST, settings.noteViewMode.first())
    }
}
