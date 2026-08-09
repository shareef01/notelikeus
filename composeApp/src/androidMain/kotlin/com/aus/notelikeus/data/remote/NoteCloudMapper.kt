package com.aus.notelikeus.data.remote

import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Note
import com.google.firebase.firestore.FieldValue

/**
 * Cloud-serialisation helpers used by [FirestoreNoteTransport].
 *
 * Everything here depends on Firebase SDK types and stays in androidMain;
 * the sync engine in commonMain works only with domain types.
 */

internal fun Note.toCloudMap(): Map<String, Any?> = buildMap {
    put("localId", id)
    // Server-assigned, not the value in [Note.serverUpdatedAt] — Firestore
    // resolves this sentinel to its own commit time, which is what makes
    // cross-device conflict resolution immune to a device's clock being wrong
    // (see NoteSyncEngine.cloudWinsConflict). Rules additionally enforce this
    // is exactly request.time, so a client cannot forge it.
    put("serverUpdatedAt", FieldValue.serverTimestamp())
    put("title", title)
    put("content", content)
    put("timestamp", timestamp)
    put("color", color)
    put("isPinned", isPinned)
    put("isArchived", isArchived)
    put("isTrashed", isTrashed)
    put("position", position)
    // Locking was removed, but older clients and the deployed rules still
    // expect this key.
    put("isLocked", false)
    put("reminderTimestamp", reminderTimestamp)
    put(
        "labels",
        labels.map { label ->
            mapOf("name" to label.name)
        }
    )
    put(
        "checklist",
        checklist.map { item -> item.toCloudMap() }
    )
}

private fun ChecklistItem.toCloudMap(): Map<String, Any> = mapOf(
    "text" to text,
    "isChecked" to isChecked,
    "position" to position
)

internal fun syncMetaMap(noteCount: Int, platform: String): Map<String, Any> = mapOf(
    "lastSyncAt" to System.currentTimeMillis(),
    "noteCount" to noteCount,
    "platform" to platform
)
