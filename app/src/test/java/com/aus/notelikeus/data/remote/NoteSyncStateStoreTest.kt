package com.aus.notelikeus.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NoteSyncStateStoreTest {

    private lateinit var store: NoteSyncStateStore

    @Before
    fun setup() {
        store = NoteSyncStateStore(RuntimeEnvironment.getApplication())
        store.clear()
    }

    @Test
    fun `markDeleted and isDeleted`() {
        store.markDeleted(7L, 1_000L)
        assertTrue(store.isDeleted(7L))
        assertEquals(1_000L, store.deletedAtById()[7L])
        assertFalse(store.isDeleted(8L))
    }

    @Test
    fun `markDeleted keeps first deletedAt`() {
        store.markDeleted(1L, 100L)
        store.markDeleted(1L, 999L)
        assertEquals(100L, store.deletedAtById()[1L])
    }

    @Test
    fun `mergeDeleted keeps earlier deletedAt`() {
        store.markDeleted(1L, 500L)
        store.mergeDeleted(mapOf(1L to 100L, 2L to 200L))
        assertEquals(100L, store.deletedAtById()[1L])
        assertEquals(200L, store.deletedAtById()[2L])
    }

    @Test
    fun `pruneExpired removes old tombstones`() {
        store.markDeleted(1L, 1_000L)
        store.markDeleted(2L, 50_000L)
        val pruned = store.pruneExpired(maxAgeMs = 10_000L, now = 20_000L)
        assertEquals(setOf(1L), pruned)
        assertFalse(store.isDeleted(1L))
        assertTrue(store.isDeleted(2L))
    }

    @Test
    fun `migrates legacy string-set tombstones`() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("note_sync_state", 0)
        prefs.edit()
            .putStringSet("deleted_ids", setOf("42", "bad"))
            .commit()

        val migrated = NoteSyncStateStore(RuntimeEnvironment.getApplication())
        assertTrue(migrated.isDeleted(42L))
        assertFalse(migrated.isDeleted(0L))
        assertTrue(prefs.getString("deleted_at_by_id", null)!!.isNotBlank())
    }

    @Test
    fun `lastMergedUserId persists and clears`() {
        assertEquals(null, store.lastMergedUserId())
        store.setLastMergedUserId("uid-a")
        assertEquals("uid-a", store.lastMergedUserId())
        store.clear()
        assertEquals(null, store.lastMergedUserId())
    }
}
