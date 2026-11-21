package com.aus.notelikeus.data.sync

import com.aus.notelikeus.contract.ContractFixtures
import com.aus.notelikeus.data.local.entity.ChecklistItemEntity
import com.aus.notelikeus.data.local.entity.LabelEntity
import com.aus.notelikeus.data.local.entity.NoteLabelCrossRef
import com.aus.notelikeus.data.mapper.toNote
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
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
    private class Scenario {
        val stateStore = FakeNoteSyncStateStore()
        val labelDao = FakeLabelDao()
        val noteDao = FakeNoteDao(labelDao)
        val cloud = DatabaseRulesCloud(stateStore)
        val engine = NoteSyncEngine(
            transport = cloud,
            remoteIdentityProvider = testRemoteIdentityProvider { "uid" },
            noteDao = noteDao,
            labelDao = labelDao,
            syncStateStore = stateStore,
            uidProvider = { Result.success("uid") },
        )

        /** Writes [note] with its labels and checklist, replacing what the row had, as the repository does. */
        suspend fun store(note: Note) {
            val id = checkNotNull(note.id)
            noteDao.insertNote(note.toNoteEntity())
            noteDao.deleteChecklistItems(id)
            note.checklist.forEach { item ->
                noteDao.insertChecklistItem(ChecklistItemEntity(noteId = id, text = item.text, isChecked = item.isChecked, position = item.position))
            }
            noteDao.deleteNoteLabelCrossRefs(id)
            note.labels.forEach { label ->
                val labelId = labelDao.labels.values.firstOrNull { it.name == label.name }?.id
                    ?: labelDao.insertLabel(LabelEntity(name = label.name))
                noteDao.insertNoteLabelCrossRef(NoteLabelCrossRef(noteId = id, labelId = labelId))
            }
        }

        suspend fun note(id: Long): Note = checkNotNull(noteDao.getNoteById(id)).toNote()

        /** The device and the cloud agree on these notes (ids 1, 2, …), as after a completed sync. */
        suspend fun seedSynced(specs: List<JsonObject>) {
            stateStore.setLastMergedUserId("uid")
            specs.forEachIndexed { index, spec ->
                val id = index + 1L
                val (revision, serverUpdatedAt) = cloud.stamp()
                val note = Note(
                    id = id, title = spec.text("title"), content = spec.text("content"), timestamp = 900L, color = 0,
                    serverUpdatedAt = serverUpdatedAt,
                    labels = spec["labels"]?.jsonArray?.map { Label(name = it.jsonPrimitive.content) }.orEmpty(),
                    checklist = spec["checklist"]?.jsonArray?.mapIndexed { position, item ->
                        val o = item.jsonObject
                        ChecklistItem(text = o.text("text"), isChecked = o.flag("isChecked"), position = position)
                    }.orEmpty(),
                )
                store(note)
                cloud.notes[id] = note.toCloudRecord(revision, serverUpdatedAt)
                stateStore.updateKnownServerRevision(id, revision)
            }
            stateStore.updateSyncedTimestamps(specs.indices.associate { it + 1L to 900L })
            stateStore.setKnownCloudIds(specs.indices.map { it + 1L }.toSet())
        }

        /** Offline on this device, through the same calls the repository makes. */
        suspend fun actLocally(change: JsonElement) {
            cloud.offline = true
            changes(change).forEachIndexed { k, spec ->
                val created = spec["create"]?.jsonObject
                val id = spec.target()
                when {
                    created != null -> store(
                        Note(id = 100L + k, title = created.text("title"), content = created.text("content"), timestamp = 6_000L, color = 0),
                    )
                    spec.flag("delete") -> {
                        val current = note(id)
                        stateStore.setDeletedDigest(id, ConflictResolution.authoredContentDigest(current))
                        noteDao.deleteNote(current.toNoteEntity())
                        engine.deleteNote(id) // Records the tombstone and its baseline; the cloud half fails offline.
                    }
                    else -> store(note(id).edited(spec).copy(timestamp = 5_000L + k))
                }
            }
            cloud.offline = false
        }

        /** Another device writes through the server. */
        fun actElsewhere(change: JsonElement) {
            for (spec in changes(change)) {
                val id = spec.target()
                if (spec.flag("delete")) {
                    cloud.notes.remove(id)
                    cloud.tombstones[id] = cloud.stamp().second
                    continue
                }
                val (revision, stamp) = cloud.stamp()
                val row = cloud.notes.getValue(id)
                val edited = Note(
                    id = id, title = row.title, content = row.content, timestamp = 1_900L, color = 0,
                    labels = row.labels.map { Label(name = it) },
                    checklist = row.checklistItems.map { ChecklistItem(text = it.text, isChecked = it.isChecked, position = it.position) },
                ).edited(spec)
                cloud.notes[id] = edited.toCloudRecord(revision, stamp)
            }
        }

        suspend fun device() = noteDao.notes.keys.filterNot { stateStore.isDeleted(it) }.map { describe(note(it)) }.sorted()

        fun inCloud() = cloud.notes.values.map { row ->
            describe(row.title, row.content, row.labels, row.checklistItems.map { Triple(it.position, it.isChecked, it.text) })
        }.sorted()
    }

    private val fixture = Json.parseToJsonElement(ContractFixtures.read("sync/scenarios.json")).jsonObject

    private fun JsonElement.strings() = jsonArray.map { it.jsonPrimitive.content }

    /** Runs one case; null when it reached its end state, otherwise what went wrong. */
    private suspend fun run(case: JsonObject): String? {
        val scenario = Scenario()
        scenario.seedSynced((case["synced"] ?: fixture.getValue("synced")).jsonArray.map { it.jsonObject })
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

    private companion object {
        fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
        fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true
        fun JsonObject.target() = (this["note"]?.jsonPrimitive?.int ?: 1).toLong()

        /** A scenario change, normalised: null, "delete", one object or a list of objects. */
        fun changes(change: JsonElement): List<JsonObject> = when (change) {
            is JsonNull -> emptyList()
            is JsonPrimitive -> listOf(JsonObject(mapOf("delete" to JsonPrimitive(true)))).also { check(change.content == "delete") }
            is JsonArray -> change.map { it.jsonObject }
            is JsonObject -> listOf(change)
        }

        /** The words a change object gives a note; everything it does not name stays. */
        fun Note.edited(spec: JsonObject): Note {
            val check = spec["check"]?.jsonPrimitive?.content
            return copy(
                content = spec["edit"]?.jsonPrimitive?.content ?: content,
                labels = spec["labels"]?.jsonArray?.map { Label(name = it.jsonPrimitive.content) } ?: labels,
                checklist = checklist.map { if (it.text == check) it.copy(isChecked = true) else it },
            )
        }

        fun Note.toCloudRecord(revision: Long, serverUpdatedAt: Long) = CloudNoteRecord(
            noteId = checkNotNull(id), serverUpdatedAt = serverUpdatedAt, clientTimestamp = timestamp, title = title,
            content = content, timestamp = timestamp, color = 0, isPinned = false, isArchived = false, isTrashed = false,
            position = 0, reminderTimestamp = null, labels = labels.map { it.name },
            checklistItems = checklist.map { ChecklistItemData(text = it.text, isChecked = it.isChecked, position = it.position) },
            revision = revision,
        )

        /** A note as the fixture writes it: `title: content #label… [x] item…`. */
        fun describe(title: String, content: String, labels: List<String>, items: List<Triple<Int, Boolean, String>>) =
            "$title: $content" +
                labels.sorted().joinToString("") { " #$it" } +
                items.sortedBy { it.first }.joinToString("") { (_, checked, text) -> if (checked) " [x] $text" else " [ ] $text" }

        fun describe(note: Note) =
            describe(note.title, note.content, note.labels.map { it.name }, note.checklist.map { Triple(it.position, it.isChecked, it.text) })
    }
}
