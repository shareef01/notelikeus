package com.aus.notelikeus.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.aus.notelikeus.data.local.SAVED_FILTERS_KEY
import com.aus.notelikeus.domain.model.NoteFlag
import com.aus.notelikeus.domain.model.NoteQuery
import com.aus.notelikeus.domain.model.NoteScope
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.domain.model.SavedFilter
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Saved filters live in a preferences blob, which means every read is a parse of user-influenced
 * text that has already survived an app upgrade.
 *
 * These run against a real file-backed DataStore rather than a fake, because the parts worth
 * doubting are the parts a fake would paper over: what happens when the blob is truncated, what
 * happens when it holds a field this build has never heard of, and whether a name really is the
 * identity when a filter is saved twice.
 */
class SavedFilterStorageTest {

    private object NoWidgets : PlatformWidgetManager {
        override suspend fun refreshWidgets() = Unit
    }

    private fun newStore(): DataStore<Preferences> {
        val dir = createTempDirectory("saved-filters")
        return PreferenceDataStoreFactory.createWithPath {
            dir.resolve("settings.preferences_pb").toOkioPath()
        }
    }

    private fun repository(store: DataStore<Preferences>) = SettingsRepositoryImpl(store, NoWidgets)

    private val invoices = NoteQuery(
        text = "invoice",
        labels = setOf(3L, 7L),
        colors = setOf(0xFF2E5A32.toInt()),
        flags = setOf(NoteFlag.HAS_REMINDER, NoteFlag.UNTITLED),
        scope = NoteScope.ARCHIVE
    )

    @Test
    fun `a saved filter survives the round trip intact`() = runTest {
        val repo = repository(newStore())

        repo.saveFilter("Invoices", invoices)

        val saved = repo.savedFilters.first()
        assertEquals(1, saved.size)
        assertEquals("Invoices", saved.first().name)
        // Field by field, because a dimension that silently fails to serialise would come back as
        // its default and quietly widen the filter rather than fail.
        assertEquals(invoices, saved.first().query)
    }

    @Test
    fun `the name is the identity, so saving twice replaces rather than duplicates`() = runTest {
        val repo = repository(newStore())

        repo.saveFilter("Work", NoteQuery(labels = setOf(1L)))
        repo.saveFilter("Other", NoteQuery(labels = setOf(9L)))
        repo.saveFilter("work", NoteQuery(labels = setOf(2L)))

        val saved = repo.savedFilters.first()
        assertEquals(2, saved.size)
        // Case-insensitively the same name, so it replaced -- and came back to the top, where the
        // user just put it.
        assertEquals("work", saved.first().name)
        assertEquals(setOf(2L), saved.first().query.labels)
    }

    @Test
    fun `deleting removes one and leaves the rest`() = runTest {
        val repo = repository(newStore())
        repo.saveFilter("A", NoteQuery(labels = setOf(1L)))
        repo.saveFilter("B", NoteQuery(labels = setOf(2L)))

        repo.deleteSavedFilter("A")

        assertEquals(listOf("B"), repo.savedFilters.first().map { it.name })
    }

    @Test
    fun `deleting the last one clears the key rather than storing an empty list`() = runTest {
        val store = newStore()
        val repo = repository(store)
        repo.saveFilter("Only", NoteQuery(labels = setOf(1L)))

        repo.deleteSavedFilter("Only")

        assertEquals(emptyList(), repo.savedFilters.first())
        assertEquals(null, store.data.first()[SAVED_FILTERS_KEY])
    }

    @Test
    fun `the list is capped so a preferences blob cannot grow without bound`() = runTest {
        val repo = repository(newStore())

        repeat(SavedFilter.MAX_SAVED + 5) { i ->
            repo.saveFilter("Filter $i", NoteQuery(labels = setOf(i.toLong())))
        }

        val saved = repo.savedFilters.first()
        assertEquals(SavedFilter.MAX_SAVED, saved.size)
        // Newest first, so the cap drops the oldest rather than refusing the newest.
        assertEquals("Filter ${SavedFilter.MAX_SAVED + 4}", saved.first().name)
    }

    @Test
    fun `a name longer than the cap is trimmed rather than rejected`() = runTest {
        val repo = repository(newStore())

        repo.saveFilter("x".repeat(200), NoteQuery(labels = setOf(1L)))

        assertEquals(SavedFilter.MAX_NAME_LENGTH, repo.savedFilters.first().first().name.length)
    }

    @Test
    fun `a blank name saves nothing`() = runTest {
        val repo = repository(newStore())

        repo.saveFilter("   ", NoteQuery(labels = setOf(1L)))

        assertTrue(repo.savedFilters.first().isEmpty())
    }

    /**
     * The reason this flow does not throw. It feeds the drawer, which is on screen from launch, so
     * a settings blob that cannot be parsed would take the notes list down with it -- to protect
     * shortcuts that cost a few taps to rebuild.
     */
    @Test
    fun `an unreadable blob reads as no filters rather than throwing`() = runTest {
        val store = newStore()
        store.edit { it[SAVED_FILTERS_KEY] = "{ this is not the json you are looking for" }

        assertEquals(emptyList(), repository(store).savedFilters.first())
    }

    @Test
    fun `a filter written by a newer build still loads`() = runTest {
        val store = newStore()
        // A dimension this build has never heard of, exactly as a later version would write it.
        store.edit {
            it[SAVED_FILTERS_KEY] =
                """[{"name":"Future","query":{"text":"tax","mood":"optimistic"}}]"""
        }

        val saved = repository(store).savedFilters.first()

        assertEquals(1, saved.size)
        assertEquals("tax", saved.first().query.text)
    }

    /**
     * Sort and view are how you look at a list, not which list it is. A saved filter that carried
     * them would rewrite two persisted preferences every time a shortcut was tapped.
     */
    @Test
    fun `narrowingOnly keeps the filters and drops the presentation`() {
        val query = NoteQuery(
            text = "milk",
            labels = setOf(4L),
            sort = NoteSortOrder.OLDEST,
            view = NoteViewMode.LIST
        )

        val narrowed = query.narrowingOnly()

        assertEquals("milk", narrowed.text)
        assertEquals(setOf(4L), narrowed.labels)
        assertEquals(NoteQuery.Default.sort, narrowed.sort)
        assertEquals(NoteQuery.Default.view, narrowed.view)
    }
}
