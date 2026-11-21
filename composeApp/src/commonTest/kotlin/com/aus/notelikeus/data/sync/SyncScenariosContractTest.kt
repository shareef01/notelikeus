package com.aus.notelikeus.data.sync

import com.aus.notelikeus.contract.ContractFixtures
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `contracts/sync/scenarios.json` on the Kotlin engine (Android and Windows): the end states the web
 * and Rust engines must reach too. Kotlin syncs in one call ([NoteSyncEngine.downloadAllNotes]), so
 * each case runs once rather than in the fixture's two orders.
 *
 * The cloud below applies the database's rules, which the shared [FakeCloudNoteTransport] does not:
 * every write is checked against the revision it was based on, a write to a deleted note is refused,
 * and a delete succeeds only at the revision it names. Tombstones come from the delete itself, as
 * `apply_note_delete` makes them; `writeTombstone` is a no-op there, as in SupabaseNoteTransport.
 */
class SyncScenariosContractTest {

    private class DatabaseRulesCloud(private val stateStore: FakeNoteSyncStateStore) : FakeCloudNoteTransport() {
        var seq = 0L
        var offline = false

        private fun online() {
            if (offline) throw java.io.IOException("offline")
        }

        fun stamp(): Pair<Long, Long> {
            seq += 1
            return seq to 1_000_000L + seq
        }

        /** What `apply_note_change` refuses: a deleted note, or a write not based on the current revision. */
        private fun refuses(id: Long): Boolean {
            val row = notes[id]
            val base = stateStore.knownServerRevisionById()[id]
            return id in tombstones || (row != null && base != row.revision) || (row == null && base != null)
        }

        private suspend fun write(uid: String, note: Note): Map<Long, CloudNoteTransport.PutResult> {
            val (revision, serverUpdatedAt) = stamp()
            nextServerRevision = revision
            nextServerTimestamp = serverUpdatedAt
            return super.putNotes(uid, listOf(note))
        }

        override suspend fun fetchNotesSnapshot(uid: String): CloudNoteSnapshot {
            online()
            return super.fetchNotesSnapshot(uid)
        }

        override suspend fun fetchTombstones(uid: String): Map<Long, Long> {
            online()
            return super.fetchTombstones(uid)
        }

        override suspend fun putNotes(uid: String, notes: List<Note>): Map<Long, CloudNoteTransport.PutResult> {
            online()
            return notes
                .filter { note -> note.id?.let { !refuses(it) } == true }
                .fold(emptyMap()) { accepted, note -> accepted + write(uid, note) }
        }

        override suspend fun deleteNote(uid: String, noteId: Long, baseRevision: Long): CloudNoteTransport.DeleteResult {
            online()
            val row = notes[noteId] ?: return CloudNoteTransport.DeleteResult.Success
            if (row.revision != baseRevision) return CloudNoteTransport.DeleteResult.Conflict
            notes.remove(noteId)
            tombstones[noteId] = stamp().second
            return CloudNoteTransport.DeleteResult.Success
        }

        override suspend fun deleteNotes(uid: String, noteIds: List<Long>) {
            online()
            for (id in noteIds) {
                stateStore.knownServerRevisionById()[id]?.let { base -> deleteNote(uid, id, base) }
            }
        }

        override suspend fun writeTombstone(uid: String, noteId: Long, deletedAt: Long) = online()

        override suspend fun deleteTombstones(uid: String, noteIds: List<Long>) = online()

        override suspend fun restoreNote(uid: String, note: Note): Map<Long, CloudNoteTransport.PutResult> {
            online()
            val id = note.id ?: return emptyMap()
            tombstones.remove(id)
            return write(uid, note)
        }
    }

    /** One device, its local stores and the cloud, for one case. */
    private inner class Scenario {
        val stateStore = FakeNoteSyncStateStore()
        val noteDao = FakeNoteDao()
        val cloud = DatabaseRulesCloud(stateStore)
        val engine = NoteSyncEngine(
            transport = cloud,
            remoteIdentityProvider = testRemoteIdentityProvider { "uid" },
            noteDao = noteDao,
            labelDao = FakeLabelDao(),
            syncStateStore = stateStore,
            uidProvider = { Result.success("uid") },
        )
        lateinit var base: Note

        /** The device and the cloud agree on one note, as after a completed sync. */
        suspend fun seedSynced() {
            stateStore.setLastMergedUserId("uid")
            val (revision, serverUpdatedAt) = cloud.stamp()
            base = Note(id = 1L, title = title, content = synced, timestamp = 900L, color = 0, serverUpdatedAt = serverUpdatedAt)
            noteDao.insertNote(base.toNoteEntity())
            cloud.notes[1L] = CloudNoteRecord(
                noteId = 1L, serverUpdatedAt = serverUpdatedAt, clientTimestamp = 900L, title = title, content = synced,
                timestamp = 900L, color = 0, isPinned = false, isArchived = false, isTrashed = false, position = 0,
                reminderTimestamp = null, labels = emptyList(), checklistItems = emptyList(), revision = revision,
            )
            stateStore.updateKnownServerRevision(1L, revision)
            stateStore.updateSyncedTimestamps(mapOf(1L to 900L))
            stateStore.setKnownCloudIds(setOf(1L))
        }

        /** Offline on this device, through the same calls the repository makes. */
        suspend fun actLocally(change: JsonElement) {
            cloud.offline = true
            when (change) {
                is JsonNull -> Unit
                is JsonPrimitive -> {
                    check(change.content == "delete")
                    stateStore.setDeletedDigest(1L, ConflictResolution.authoredContentDigest(base))
                    noteDao.deleteNote(base.toNoteEntity())
                    engine.deleteNote(1L) // Records the tombstone and its baseline; the cloud half fails offline.
                }
                is JsonObject -> {
                    change["edit"]?.let { edit ->
                        noteDao.insertNote(base.copy(content = edit.jsonPrimitive.content, timestamp = 5_000L).toNoteEntity())
                    }
                    change["create"]?.jsonObject?.let { new ->
                        val note = Note(
                            id = 2L, title = new.getValue("title").jsonPrimitive.content,
                            content = new.getValue("content").jsonPrimitive.content, timestamp = 6_000L, color = 0,
                        )
                        noteDao.insertNote(note.toNoteEntity())
                    }
                }
                else -> error("unknown local change $change")
            }
            cloud.offline = false
        }

        /** Another device writes through the server. */
        fun actElsewhere(change: JsonElement) {
            when (change) {
                is JsonNull -> Unit
                is JsonPrimitive -> {
                    check(change.content == "delete")
                    cloud.notes.remove(1L)
                    cloud.tombstones[1L] = cloud.stamp().second
                }
                is JsonObject -> {
                    val (revision, stamp) = cloud.stamp()
                    val words = change.getValue("edit").jsonPrimitive.content
                    cloud.notes[1L] = cloud.notes.getValue(1L).copy(
                        content = words, clientTimestamp = 1_900L, timestamp = 1_900L, serverUpdatedAt = stamp,
                        revision = revision,
                    )
                }
                else -> error("unknown elsewhere change $change")
            }
        }

        fun device() = noteDao.notes.values.filterNot { stateStore.isDeleted(it.id) }.map { "${it.title}: ${it.content}" }.sorted()

        fun inCloud() = cloud.notes.values.map { "${it.title}: ${it.content}" }.sorted()
    }

    private val fixture = Json.parseToJsonElement(ContractFixtures.read("sync/scenarios.json")).jsonObject
    private val title = fixture.getValue("synced").jsonObject.getValue("title").jsonPrimitive.content
    private val synced = fixture.getValue("synced").jsonObject.getValue("content").jsonPrimitive.content

    private fun JsonElement.strings() = jsonArray.map { it.jsonPrimitive.content }

    /** Runs one case; null when it reached its end state, otherwise what went wrong. */
    private suspend fun run(case: JsonObject): String? {
        val scenario = Scenario()
        scenario.seedSynced()
        scenario.actLocally(case.getValue("local"))
        scenario.actElsewhere(case.getValue("elsewhere"))

        scenario.engine.downloadAllNotes().getOrThrow()

        val expected = case.getValue("expected").jsonObject
        val (wantDevice, wantCloud) = expected.getValue("device").strings() to expected.getValue("cloud").strings()
        val (device, inCloud) = scenario.device() to scenario.inCloud()
        return if (device == wantDevice && inCloud == wantCloud) {
            null
        } else {
            "${case.getValue("name")}: device $device (want $wantDevice), cloud $inCloud (want $wantCloud)"
        }
    }

    @Test
    fun `every shared scenario reaches its end state`() = runTest {
        val mismatches = fixture.getValue("cases").jsonArray.mapNotNull { run(it.jsonObject) }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }
}
