package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.sync.CloudNoteRecord
import com.aus.notelikeus.data.sync.CloudNoteTransport
import com.aus.notelikeus.data.sync.ChecklistItemData
import com.aus.notelikeus.domain.model.Note
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * Firebase Firestore adapter implementing [CloudNoteTransport].
 *
 * All Firestore SDK types stay inside this class; the engine sees only
 * domain types from commonMain.
 */
class FirestoreNoteTransport(
    private val firestore: FirebaseFirestore
) : CloudNoteTransport {

    override suspend fun fetchNotes(uid: String): List<CloudNoteRecord> {
        val docs = userNotesCollection(uid).get().await().documents
        return docs.mapNotNull { doc ->
            val noteId = doc.id.toLongOrNull()
                ?: doc.getLong("localId")
                ?: return@mapNotNull null
            val data = doc.data ?: return@mapNotNull null
            data.toCloudNoteRecord(noteId)
        }
    }

    override suspend fun fetchNote(uid: String, noteId: Long): CloudNoteRecord? {
        val doc = userNotesCollection(uid).document(noteId.toString()).get().await()
        if (!doc.exists()) return null
        val data = doc.data ?: return null
        return data.toCloudNoteRecord(noteId)
    }

    override suspend fun putNotes(uid: String, notes: List<Note>): Map<Long, Long?> {
        val result = mutableMapOf<Long, Long?>()
        val collection = userNotesCollection(uid)

        notes.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = firestore.batch()
            val ids = mutableListOf<Long>()

            chunk.forEach { note ->
                val noteId = note.id ?: return@forEach
                batch.set(
                    collection.document(noteId.toString()),
                    note.toCloudMap(),
                    SetOptions.merge()
                )
                ids.add(noteId)
            }

            if (ids.isNotEmpty()) {
                batch.commit().await()
                result.putAll(readServerTimestamps(collection, ids))
            }
        }

        return result
    }

    override suspend fun deleteNotes(uid: String, noteIds: List<Long>) {
        deleteDocuments(userNotesCollection(uid), noteIds)
    }

    override suspend fun fetchTombstones(uid: String): Map<Long, Long> {
        val docs = userTombstonesCollection(uid).get().await().documents
        return docs.mapNotNull { doc ->
            val id = doc.id.toLongOrNull() ?: return@mapNotNull null
            val deletedAt = doc.getLong("deletedAt") ?: return@mapNotNull null
            id to deletedAt
        }.toMap()
    }

    override suspend fun writeTombstone(uid: String, noteId: Long, deletedAt: Long) {
        userTombstonesCollection(uid)
            .document(noteId.toString())
            .set(mapOf("deletedAt" to deletedAt), SetOptions.merge())
            .await()
    }

    override suspend fun deleteTombstones(uid: String, noteIds: List<Long>) {
        deleteDocuments(userTombstonesCollection(uid), noteIds)
    }

    override suspend fun writeSyncMeta(uid: String, noteCount: Int, platform: String) {
        syncMetaDocument(uid)
            .set(syncMetaMap(noteCount, platform), SetOptions.merge())
            .await()
    }

    override suspend fun deleteSyncMeta(uid: String) {
        syncMetaDocument(uid).delete().await()
    }

    // ---- private helpers ----

    private fun userNotesCollection(uid: String) = firestore.collection("users")
        .document(uid)
        .collection("notes")

    private fun userTombstonesCollection(uid: String) = firestore.collection("users")
        .document(uid)
        .collection("tombstones")

    private fun syncMetaDocument(uid: String) = firestore.collection("users")
        .document(uid)
        .collection("_meta")
        .document("sync")

    /** Deletes the documents named by [ids] from [collection], one committed batch per chunk. */
    private suspend fun deleteDocuments(collection: CollectionReference, ids: List<Long>) {
        ids.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { id ->
                batch.delete(collection.document(id.toString()))
            }
            batch.commit().await()
        }
    }

    /**
     * Reads back the server-assigned `serverUpdatedAt` for [noteIds].
     *
     * The values have to come from the server — the rules require `serverUpdatedAt == request.time`
     * so it cannot be computed here, and Android's `batch.commit()` returns `Task<Void>` with no
     * write results (the desktop REST transport gets them free from the commit response). The
     * round trips were avoidable though: this used to issue one `document().get()` per note, so a
     * 400-note first sync cost 400 of them. A `documentId()` query covers [WHERE_IN_LIMIT] ids per
     * request for the same billed reads.
     *
     * Ids absent from the response map to null, exactly as a missing field did before.
     */
    private suspend fun readServerTimestamps(
        collection: CollectionReference,
        noteIds: List<Long>
    ): Map<Long, Long?> {
        val timestamps = noteIds.associateWithTo(mutableMapOf<Long, Long?>()) { null }
        for (idChunk in noteIds.chunked(WHERE_IN_LIMIT)) {
            val snapshot = collection
                .whereIn(FieldPath.documentId(), idChunk.map { it.toString() })
                .get()
                .await()
            for (document in snapshot.documents) {
                val noteId = document.id.toLongOrNull() ?: continue
                timestamps[noteId] = document.getTimestamp("serverUpdatedAt")?.toEpochMillis()
            }
        }
        return timestamps
    }

    private companion object {
        private const val BATCH_LIMIT = 400

        /** Firestore caps the value list of a `whereIn` filter at 30 entries. */
        private const val WHERE_IN_LIMIT = 30
    }
}

// ---- Map helpers (derived from NoteCloudMapper, kept here as Firebase types
//     must not leak into commonMain) ----

private fun Timestamp.toEpochMillis(): Long = seconds * 1000 + nanoseconds / 1_000_000

/**
 * The maps inside a cloud list field, skipping anything that isn't one.
 *
 * firestore.rules bounds these lists' *length* but not their element *types*, so a document
 * written by an older client — or restored from a hand-edited backup — can legitimately hold bare
 * strings in `labels`. The previous `as? List<Map<String, Any?>>` succeeded on any list at all
 * (erasure), and indexing a String element as a Map threw ClassCastException out of fetchNotes,
 * which failed every subsequent sync for that account until the document was repaired by hand.
 * The web mapper (`cloudMapToNote`) has always filtered by element type; this matches it.
 */
private fun Map<String, Any?>.cloudMapList(key: String): List<Map<String, Any?>> =
    (this[key] as? List<*>).orEmpty().filterIsInstance<Map<String, Any?>>()

private fun Map<String, Any?>.toCloudNoteRecord(noteId: Long): CloudNoteRecord {
    val labelNames = cloudMapList("labels").mapNotNull { it["name"] as? String }

    val checklist = cloudMapList("checklist").mapIndexed { index, item ->
        ChecklistItemData(
            text = item["text"] as? String ?: "",
            isChecked = item["isChecked"] as? Boolean ?: false,
            position = (item["position"] as? Number)?.toInt() ?: index
        )
    }

    return CloudNoteRecord(
        noteId = noteId,
        serverUpdatedAt = (this["serverUpdatedAt"] as? Timestamp)?.toEpochMillis(),
        clientTimestamp = (this["timestamp"] as? Number)?.toLong(),
        title = this["title"] as? String ?: "",
        content = this["content"] as? String ?: "",
        timestamp = (this["timestamp"] as? Number)?.toLong() ?: 0L,
        color = (this["color"] as? Number)?.toInt() ?: 0,
        isPinned = this["isPinned"] as? Boolean ?: false,
        isArchived = this["isArchived"] as? Boolean ?: false,
        isTrashed = this["isTrashed"] as? Boolean ?: false,
        position = (this["position"] as? Number)?.toInt() ?: 0,
        reminderTimestamp = (this["reminderTimestamp"] as? Number)?.toLong(),
        labels = labelNames,
        checklistItems = checklist
    )
}
