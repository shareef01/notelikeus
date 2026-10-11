package com.aus.notelikeus.data.remote

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Parity tests for the Android [com.aus.notelikeus.data.sync.NoteSyncStateStore] against the
 * contract the desktop implementation already satisfies and is tested for in
 * `DesktopNoteSyncStateStoreTest`.
 *
 * The revision-aware sync work added two account-scoped maps to the interface — the server
 * revisions the device believes the cloud holds, and the revision a permanent delete was based on.
 * The Android store did not implement them, which is what broke `compileDebugKotlinAndroid`. These
 * tests cover the whole contract, not just the two maps, because the assertion the engine depends
 * on is semantic: every marker that scopes an account's sync must survive a restart, must be
 * cleared on sign-out, and must never be invented from corrupt input.
 */
@RunWith(RobolectricTestRunner::class)
class SharedPrefsNoteSyncStateStoreTest {

    private lateinit var context: Context

    /**
     * Mirrors the private constants in `SharedPrefsNoteSyncStateStore`. Duplicated deliberately:
     * these names are the on-disk contract a future version has to keep reading, so a rename should
     * fail this test rather than silently orphan a user's existing sync state.
     */
    private val prefsName = "note_sync_state"
    private val keyKnownServerRevisions = "known_server_revisions"
    private val keyBaselineDeleteRevisions = "baseline_delete_revisions"
    private val keyDeletedJson = "deleted_at_by_id"
    private val keyLegacyDeleted = "deleted_ids"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        rawPrefs().edit().clear().commit()
    }

    private fun rawPrefs(): SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** The store re-reads the preference store on every accessor, so a new instance is a reload. */
    private fun store() = SharedPrefsNoteSyncStateStore(context)

    /** `apply()` only queues the disk write; `commit()` flushes the whole map synchronously. */
    private fun flushToDisk() {
        rawPrefs().edit().commit()
    }

    private fun prefsFile() = File(context.dataDir, "shared_prefs/$prefsName.xml")

    private val noteId = 42L

    // ---- revisions the device believes the cloud holds ----

    @Test
    fun `known server revisions survive recreation, update and clear`() {
        store().apply {
            updateKnownServerRevision(100L, 10L)
            updateKnownServerRevision(200L, 20L)
        }

        store().let { reloaded ->
            assertEquals(10L, reloaded.knownServerRevisionById()[100L])
            assertEquals(20L, reloaded.knownServerRevisionById()[200L])
            reloaded.updateKnownServerRevision(100L, 11L)
        }

        store().let { reloaded ->
            assertEquals(11L, reloaded.knownServerRevisionById()[100L])
            reloaded.clearKnownServerRevisions(listOf(100L))
        }

        val afterClear = store().knownServerRevisionById()
        assertFalse("a cleared revision must not come back after a reload", 100L in afterClear)
        assertEquals("clearing one id must not disturb the others", 20L, afterClear[200L])
    }

    @Test
    fun `updateKnownServerRevisions merges instead of replacing`() {
        store().updateKnownServerRevision(1L, 100L)

        store().updateKnownServerRevisions(mapOf(2L to 200L, 3L to 300L))

        val reloaded = store().knownServerRevisionById()
        assertEquals(100L, reloaded[1L])
        assertEquals(200L, reloaded[2L])
        assertEquals(300L, reloaded[3L])
    }

    // ---- the revision a permanent delete was based on ----

    @Test
    fun `delete baselines survive recreation, update and clear`() {
        store().setBaselineDeleteRevision(100L, 10L)

        assertEquals(10L, store().baselineDeleteRevisionById()[100L])

        store().setBaselineDeleteRevision(100L, 12L)
        assertEquals(12L, store().baselineDeleteRevisionById()[100L])

        store().clearBaselineDeleteRevision(100L)
        assertFalse(100L in store().baselineDeleteRevisionById())
    }

    @Test
    fun `markDeleted records the tombstone and its baseline together`() {
        store().markDeleted(noteId, deletedAt = 5_000L, baselineRevision = 77L)

        val reloaded = store()
        assertEquals(5_000L, reloaded.deletedAtById()[noteId])
        assertEquals(77L, reloaded.baselineDeleteRevisionById()[noteId])
        assertTrue(reloaded.isDeleted(noteId))
    }

    @Test
    fun `a delete without a baseline leaves no baseline behind`() {
        store().markDeleted(noteId, deletedAt = 5_000L, baselineRevision = null)

        val reloaded = store()
        assertTrue(reloaded.isDeleted(noteId))
        assertFalse(
            "an unknown baseline must stay absent, never 0 — 0 would authorise an unconditional delete",
            noteId in reloaded.baselineDeleteRevisionById(),
        )
    }

    // ---- the values the engine compares must not be mangled ----

    @Test
    fun `revision values larger than an int round-trip without truncation`() {
        // The server sequence is a bigint, and client timestamps are epoch millis: both exceed
        // Int.MAX_VALUE, so a truncating read would silently change which write wins.
        val bigRevision = 5_000_000_000L
        val epochMillis = 1_789_880_000_000L

        store().apply {
            updateKnownServerRevision(noteId, bigRevision)
            setBaselineDeleteRevision(noteId, epochMillis)
        }

        val reloaded = store()
        assertEquals(bigRevision, reloaded.knownServerRevisionById()[noteId])
        assertEquals(epochMillis, reloaded.baselineDeleteRevisionById()[noteId])
    }

    // ---- durability ----

    @Test
    fun `revision maps reach the durable preferences file, not just memory`() {
        store().updateKnownServerRevision(noteId, 4_242L)
        flushToDisk()

        val xml = prefsFile()
        assertTrue("expected $xml to exist after a write", xml.exists())
        assertTrue(
            "the revision map was never serialised to durable storage",
            xml.readText().contains(keyKnownServerRevisions),
        )
    }

    // ---- sign-out ----

    @Test
    fun `clear removes every account-scoped sync marker`() {
        store().apply {
            markDeleted(noteId, deletedAt = 5_000L, baselineRevision = 9L)
            markRestored(noteId)
            markPendingAttachmentGc(noteId, listOf("att-1"))
            setKnownCloudIds(setOf(noteId))
            updateKnownServerRevision(noteId, 9L)
            setBaselineDeleteRevision(noteId, 9L)
            markReconciled(1234L)
            setLastMergedUserId("owner-a")
        }

        store().clear()

        store().let { cleared ->
            assertEquals(emptySet<Long>(), cleared.deletedIds())
            assertEquals(emptySet<Long>(), cleared.restoredIds())
            assertEquals(emptySet<Long>(), cleared.pendingAttachmentGcIds())
            assertEquals(emptySet<Long>(), cleared.knownCloudIds())
            assertEquals(emptyMap<Long, Long>(), cleared.knownServerRevisionById())
            assertEquals(emptyMap<Long, Long>(), cleared.baselineDeleteRevisionById())
            assertEquals(0L, cleared.lastReconciledAt())
            assertNull(cleared.lastMergedUserId())
        }
        assertFalse("the backing preference file still holds another account's state", rawPrefs().all.isNotEmpty())
    }

    // ---- old installations ----

    @Test
    fun `an installation predating the revision keys starts empty and is usable`() {
        // What a device upgrading into revision-aware sync actually has on disk: no revision keys
        // at all, but real tombstones and a known-cloud set.
        rawPrefs().edit()
            .putString(keyDeletedJson, JSONObject().apply { put(noteId.toString(), 5_000L) }.toString())
            .commit()

        val upgraded = store()
        assertTrue(upgraded.isDeleted(noteId))
        assertEquals(emptyMap<Long, Long>(), upgraded.knownServerRevisionById())
        assertEquals(emptyMap<Long, Long>(), upgraded.baselineDeleteRevisionById())

        // And it must accept the new markers afterwards.
        upgraded.updateKnownServerRevision(noteId, 3L)
        assertEquals(3L, store().knownServerRevisionById()[noteId])
    }

    @Test
    fun `legacy string-set tombstones still migrate`() {
        rawPrefs().edit().putStringSet(keyLegacyDeleted, setOf(noteId.toString())).commit()

        val migrated = store().deletedAtById()

        assertTrue("legacy tombstones must not be lost on upgrade", noteId in migrated)
        assertTrue("a migrated tombstone needs a timestamp", (migrated[noteId] ?: 0L) > 0L)
    }

    // ---- corrupt input ----

    @Test
    fun `malformed revision state fails safely instead of inventing values`() {
        rawPrefs().edit()
            .putString(keyKnownServerRevisions, "{not json at all")
            .putString(keyBaselineDeleteRevisions, JSONObject().apply { put(noteId.toString(), "not-a-number") }.toString())
            .commit()

        val reloaded = store()

        assertEquals(
            "unreadable revision state must read as absent, not as a fabricated revision",
            emptyMap<Long, Long>(),
            reloaded.knownServerRevisionById(),
        )
        assertEquals(
            "a non-numeric revision must be skipped, never coerced to 0",
            emptyMap<Long, Long>(),
            reloaded.baselineDeleteRevisionById(),
        )

        // Corrupt input must not wedge the store either.
        reloaded.updateKnownServerRevision(noteId, 8L)
        assertEquals(8L, store().knownServerRevisionById()[noteId])
    }
}
