package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.sync.CloudNoteRecord
import com.aus.notelikeus.data.sync.CloudNoteTransport
import com.aus.notelikeus.data.sync.ChecklistItemData
import com.aus.notelikeus.domain.model.Note
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
                // Read back server-resolved timestamps for every written note.
                coroutineScope {
                    ids.map { noteId ->
                        async {
                            noteId to readServerTimestamp(
                                collection.document(noteId.toString())
                            )
                        }
                    }.awaitAll().forEach { (noteId, ts) ->
                        result[noteId] = ts
                    }
                }
            }
        }

        return result
    }

    override suspend fun deleteNotes(uid: String, noteIds: List<Long>) {
        val collection = userNotesCollection(uid)
        noteIds.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { noteId ->
                batch.delete(collection.document(noteId.toString()))
            }
            batch.commit().await()
        }
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
        val collection = userTombstonesCollection(uid)
        noteIds.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { noteId ->
                batch.delete(collection.document(noteId.toString()))
            }
            batch.commit().await()
        }
    }

    override suspend fun writeSyncMeta(uid: String, noteCount: Int, platform: String) {
        firestore.collection("users")
            .document(uid)
            .collection("_meta")
            .document("sync")
            .set(syncMetaMap(noteCount, platform), SetOptions.merge())
            .await()
    }

    override suspend fun deleteSyncMeta(uid: String) {
        firestore.collection("users")
            .document(uid)
            .collection("_meta")
            .document("sync")
            .delete()
            .await()
    }

    // ---- private helpers ----

    private fun userNotesCollection(uid: String) = firestore.collection("users")
        .document(uid)
        .collection("notes")

    private fun userTombstonesCollection(uid: String) = firestore.collection("users")
        .document(uid)
        .collection("tombstones")

    private suspend fun readServerTimestamp(
        docRef: com.google.firebase.firestore.DocumentReference
    ): Long? {
        return docRef.get().await().getTimestamp("serverUpdatedAt")?.toEpochMillis()
    }

    private companion object {
        private const val BATCH_LIMIT = 400
    }
}

// ---- Map helpers (derived from NoteCloudMapper, kept here as Firebase types
//     must not leak into commonMain) ----

private fun Timestamp.toEpochMillis(): Long = seconds * 1000 + nanoseconds / 1_000_000

private fun Map<String, Any?>.toCloudNoteRecord(noteId: Long): CloudNoteRecord {
    val rawLabels = (this["labels"] as? List<Map<String, Any?>>).orEmpty()
    val labelNames = rawLabels.mapNotNull { it["name"] as? String }

    val checklist = (this["checklist"] as? List<Map<String, Any?>>)
        ?.mapIndexed { index, item ->
            ChecklistItemData(
                text = item["text"] as? String ?: "",
                isChecked = item["isChecked"] as? Boolean ?: false,
                position = (item["position"] as? Number)?.toInt() ?: index
            )
        }
        .orEmpty()

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
