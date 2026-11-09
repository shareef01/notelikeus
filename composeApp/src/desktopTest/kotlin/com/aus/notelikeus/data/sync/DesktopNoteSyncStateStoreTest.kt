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

    /**
     * The store is written from the sync worker, the coordinator's own scope and the UI thread at
     * once. Before its members were synchronized, a write landing while another thread encoded the
     * same map threw ConcurrentModificationException or lost an entry.
     */
    @Test
    fun `writes from several threads all survive`() {
        val store = createStore()
        val threads = 4
        val writesPerThread = 40
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        // Readers copy the maps while the writers grow them: that copy is what used to throw.
        val writing = java.util.concurrent.atomic.AtomicBoolean(true)
        val readers = (0 until 2).map {
            Thread {
                try {
                    while (writing.get()) {
                        store.knownServerRevisionById()
                        store.deletedDigestById()
                    }
                } catch (error: Throwable) {
                    failures.add(error)
                }
            }.apply { start() }
        }

        val workers = (0 until threads).map { thread ->
            Thread {
                try {
                    for (n in 0 until writesPerThread) {
                        val id = (thread * writesPerThread + n).toLong()
                        store.updateKnownServerRevision(id, id + 1)
                        store.setDeletedDigest(id, "digest-$id")
                    }
                } catch (error: Throwable) {
                    failures.add(error)
                }
            }.apply { start() }
        }
        workers.forEach { it.join() }
        writing.set(false)
        readers.forEach { it.join() }

        assertTrue(failures.isEmpty(), "A concurrent write failed: ${failures.firstOrNull()}")
        val reloaded = createStore()
        assertEquals(threads * writesPerThread, reloaded.knownServerRevisionById().size)
        assertEquals(threads * writesPerThread, reloaded.deletedDigestById().size)
    }
}
