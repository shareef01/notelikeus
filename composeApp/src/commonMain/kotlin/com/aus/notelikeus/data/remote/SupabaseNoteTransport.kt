package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.sync.ChecklistItemData
import com.aus.notelikeus.data.sync.CloudNoteRecord
import com.aus.notelikeus.data.sync.CloudNoteTransport
import com.aus.notelikeus.domain.model.Note
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Supabase revision-RPC adapter for [CloudNoteTransport].
 *
 * Dev-only: enabled when [BackendConfig.remoteBackend] is [RemoteBackend.SUPABASE].
 * Requires a Supabase JWT via [SupabaseAccessTokenProvider] (wired in Phase 5).
 */
class SupabaseNoteTransport(
    private val rpc: SupabaseRpcClient,
) : CloudNoteTransport {

    private val revisions = mutableMapOf<String, MutableMap<Long, Long>>()

    private fun revisionMap(uid: String): MutableMap<Long, Long> =
        revisions.getOrPut(uid) { mutableMapOf() }

    override suspend fun fetchNotes(uid: String): List<CloudNoteRecord> {
        val snapshot = rpc.callRpc("fetch_full_snapshot", buildJsonObject { })
        val notes = snapshot["notes"]?.jsonArray.orEmpty()
        val tombstones = snapshot["tombstones"]?.jsonArray.orEmpty()
        val map = revisionMap(uid)
        map.clear()
        var maxRevision = 0L
        val records = notes.mapNotNull { element ->
            val row = element.jsonObject
            val noteId = row.longId("local_id") ?: row.stringId("note_id")?.toLongOrNull()
                ?: return@mapNotNull null
            val revision = row.longId("revision")
            if (revision != null) {
                map[noteId] = revision
                maxRevision = maxOf(maxRevision, revision)
            }
            row.toCloudNoteRecord(noteId)
        }
        for (element in tombstones) {
            val revision = element.jsonObject.longId("revision")
            if (revision != null) maxRevision = maxOf(maxRevision, revision)
        }
        return records
    }

    override suspend fun fetchNote(uid: String, noteId: Long): CloudNoteRecord? =
        fetchNotes(uid).firstOrNull { it.noteId == noteId }

    override suspend fun putNotes(uid: String, notes: List<Note>): Map<Long, Long?> {
        val result = mutableMapOf<Long, Long?>()
        for (note in notes) {
            val noteId = note.id ?: continue
            val response = rpc.callRpc(
                "apply_note_change",
                note.toRpcArgs(revisionMap(uid)[noteId]),
            )
            when (response.stringField("status")) {
                "applied" -> {
                    val revision = response.longId("revision")
                    val serverUpdatedAt = response.longId("server_updated_at")
                    if (revision != null) revisionMap(uid)[noteId] = revision
                    result[noteId] = serverUpdatedAt
                }
                "conflict" -> {
                    val current = response["current"]?.jsonObject
                    val revision = current?.longId("revision")
                    if (revision != null) revisionMap(uid)[noteId] = revision
                    result[noteId] = current?.longId("server_updated_at")
                }
                else -> result[noteId] = null
            }
        }
        return result
    }

    override suspend fun deleteNotes(uid: String, noteIds: List<Long>) {
        for (noteId in noteIds) {
            val baseRevision = revisionMap(uid)[noteId]
            if (baseRevision == null) continue
            val response = rpc.callRpc(
                "apply_note_delete",
                buildJsonObject {
                    put("p_note_id", JsonPrimitive(noteId.toString()))
                    put("p_base_revision", JsonPrimitive(baseRevision))
                },
            )
            when (response.stringField("status")) {
                "applied" -> {
                    response.longId("revision")?.let { revisionMap(uid).remove(noteId) }
                }
            }
        }
    }

    override suspend fun fetchTombstones(uid: String): Map<Long, Long> {
        val snapshot = rpc.callRpc("fetch_full_snapshot", buildJsonObject { })
        val tombstones = snapshot["tombstones"]?.jsonArray.orEmpty()
        return tombstones.mapNotNull { element ->
            val row = element.jsonObject
            val noteId = row.stringId("note_id")?.toLongOrNull() ?: return@mapNotNull null
            val deletedAt = row.longId("deleted_at") ?: return@mapNotNull null
            noteId to deletedAt
        }.toMap()
    }

    override suspend fun writeTombstone(uid: String, noteId: Long, deletedAt: Long) {
        // apply_note_delete creates the durable tombstone; the engine also calls deleteNotes.
    }

    override suspend fun deleteTombstones(uid: String, noteIds: List<Long>) {
        // Individual tombstone cleanup is server-managed; account wipe uses deleteAllOwnedCloudData.
    }

    override suspend fun writeSyncMeta(uid: String, noteCount: Int, platform: String) {
        // Optional metadata — direct table writes deferred until Phase 5 auth mapping is stable.
    }

    override suspend fun deleteSyncMeta(uid: String) {
        // Covered by delete_all_user_cloud_data during account wipe.
    }

    override suspend fun deleteAllOwnedCloudData(uid: String) {
        rpc.callRpc("delete_all_user_cloud_data", buildJsonObject { })
        revisions.remove(uid)
    }

    private fun Note.toRpcArgs(baseRevision: Long?): JsonObject = buildJsonObject {
        val noteId = id ?: return@buildJsonObject
        put("p_note_id", JsonPrimitive(noteId.toString()))
        put("p_local_id", JsonPrimitive(noteId))
        if (baseRevision == null) {
            put("p_base_revision", JsonNull)
        } else {
            put("p_base_revision", JsonPrimitive(baseRevision))
        }
        put("p_title", JsonPrimitive(title))
        put("p_content", JsonPrimitive(content))
        put("p_client_timestamp", JsonPrimitive(timestamp))
        put("p_color", JsonPrimitive(color))
        put("p_is_pinned", JsonPrimitive(isPinned))
        put("p_is_archived", JsonPrimitive(isArchived))
        put("p_is_trashed", JsonPrimitive(isTrashed))
        put("p_position", JsonPrimitive(position))
        if (reminderTimestamp == null) {
            put("p_reminder_timestamp", JsonNull)
        } else {
            put("p_reminder_timestamp", JsonPrimitive(reminderTimestamp))
        }
        put("p_labels", labelsToJsonArray(labels))
        put("p_checklist", checklistToJsonArray(checklist))
    }

    private fun labelsToJsonArray(labels: List<com.aus.notelikeus.domain.model.Label>): JsonArray =
        buildJsonArray {
            for (label in labels) {
                add(buildJsonObject { put("name", JsonPrimitive(label.name)) })
            }
        }

    private fun checklistToJsonArray(
        checklist: List<com.aus.notelikeus.domain.model.ChecklistItem>,
    ): JsonArray =
        buildJsonArray {
            for (item in checklist) {
                add(
                    buildJsonObject {
                        put("text", JsonPrimitive(item.text))
                        put("isChecked", JsonPrimitive(item.isChecked))
                        put("position", JsonPrimitive(item.position))
                    },
                )
            }
        }

    private fun JsonObject.toCloudNoteRecord(noteId: Long): CloudNoteRecord {
        val labels = this["labels"]?.jsonArray.orEmpty().mapNotNull { element ->
            element.jsonObject.stringField("name")?.trim()?.takeIf { it.isNotEmpty() }
        }
        val checklist = this["checklist"]?.jsonArray.orEmpty().mapIndexed { index, element ->
            val row = element.jsonObject
            ChecklistItemData(
                text = row.stringField("text").orEmpty(),
                isChecked = row["isChecked"]?.jsonPrimitive?.content == "true",
                position = row.longId("position")?.toInt() ?: index,
            )
        }
        return CloudNoteRecord(
            noteId = noteId,
            serverUpdatedAt = longId("server_updated_at"),
            clientTimestamp = longId("client_timestamp"),
            title = stringField("title").orEmpty(),
            content = stringField("content").orEmpty(),
            timestamp = longId("client_timestamp") ?: 0L,
            color = longId("color")?.toInt() ?: 0,
            isPinned = this["is_pinned"]?.jsonPrimitive?.content == "true",
            isArchived = this["is_archived"]?.jsonPrimitive?.content == "true",
            isTrashed = this["is_trashed"]?.jsonPrimitive?.content == "true",
            position = longId("position")?.toInt() ?: 0,
            reminderTimestamp = longId("reminder_timestamp"),
            labels = labels,
            checklistItems = checklist,
        )
    }

    private fun JsonObject.stringField(key: String): String? =
        this[key]?.jsonPrimitive?.content

    private fun JsonObject.stringId(key: String): String? = stringField(key)

    private fun JsonObject.longId(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull
}
