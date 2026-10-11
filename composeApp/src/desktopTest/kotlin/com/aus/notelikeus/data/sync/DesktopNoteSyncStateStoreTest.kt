package com.aus.notelikeus.data.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.aus.notelikeus.di.DesktopNoteSyncStateStore
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopNoteSyncStateStoreTest {

    private lateinit var testFile: File
    private lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>

    @Before
    fun setup() {
        testFile = File.createTempFile("test_datastore", ".preferences_pb")
        dataStore = PreferenceDataStoreFactory.createWithPath(produceFile = { testFile.absolutePath.toPath() })
    }

    @After
    fun teardown() {
        testFile.delete()
    }

    private fun createStore() = DesktopNoteSyncStateStore(dataStore)

    @Test
    fun `known server revision round-trips correctly`() = runTest {
        var store = createStore()

        // 1. Write
        store.updateKnownServerRevision(100L, 10L)
        store.updateKnownServerRevision(200L, 20L)

        // 2. Recreate/reload
        store = createStore()
        
        val known = store.knownServerRevisionById()
        assertEquals(10L, known[100L])
        assertEquals(20L, known[200L])

        // 3. Update revision
        store.updateKnownServerRevision(100L, 11L)

        // 4. Recreate/reload
        store = createStore()
        assertEquals(11L, store.knownServerRevisionById()[100L])

        // 5. Clear known revision
        store.clearKnownServerRevisions(listOf(100L))

        // 6. Reload and verify absence
        store = createStore()
        assertFalse(100L in store.knownServerRevisionById())
    }

    @Test
    fun `delete baseline round-trips correctly`() = runTest {
        var store = createStore()

        // 1. Write baseline
        store.setBaselineDeleteRevision(100L, 10L)

        // 2. Recreate/reload
        store = createStore()
        
        val baselines = store.baselineDeleteRevisionById()
        assertEquals(10L, baselines[100L])

        // 3. Clear baseline
        store.clearBaselineDeleteRevision(100L)

        // 4. Reload and verify absence
        store = createStore()
        assertFalse(100L in store.baselineDeleteRevisionById())
    }

    @Test
    fun `legacy state loads without crashing and missing baseline is absent not zero`() = runTest {
        // Create an existing state without the new keys
        var store = createStore()
        store.markDeleted(99L, 5000L) // Legacy behavior simulation
        
        // Reload
        store = createStore()
        
        assertTrue(store.isDeleted(99L))
        
        val baselines = store.baselineDeleteRevisionById()
        assertFalse(99L in baselines, "Missing baseline should be absent, not present")
        assertFalse(baselines.values.contains(0L), "Missing baseline must never be 0")
    }
}
