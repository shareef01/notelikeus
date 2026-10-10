package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.sync.ChecklistItemData
import com.aus.notelikeus.data.attachments.ATTACHMENT_R2_PREFIX
import com.aus.notelikeus.data.sync.CloudNoteRecord
import com.aus.notelikeus.data.sync.CloudNoteSnapshot
import com.aus.notelikeus.data.sync.CloudNoteTransport
import com.aus.notelikeus.data.sync.IdentityBoundNoteTransport
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.util.AppLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Supabase revision-RPC adapter for [CloudNoteTransport], and the production
 * [IdentityBoundNoteTransport].
 * Requires a Supabase JWT via [SupabaseAccessTokenProvider].
 *
 * It implements both interfaces explicitly. The `uid`-keyed members are the shared bodies both paths
 * run — the identity-bound entry points below route through [scopedTo] into exactly those bodies —
 * while [IdentityBoundNoteTransport] is the contract the engine is constructed with. The two are
 * separate types on purpose: the engine must not be able to reach the live-session members, and
 * keeping them off the identity interface is what makes that structural rather than a convention.
 */
class SupabaseNoteTransport internal constructor(
    private val rpc: SupabaseRpcClient,
    /**
     * Shared with the identity-scoped views, so captured base revisions survive a scoped call, and
     * guarded: the epoch it carries and the revisions it holds are read and written inside one critical
     * section, so a reset can never land between an operation's generation check and its access.
     */
    private val revisionState: RevisionStateStore = RevisionStateStore(),
    /**
     * The dataset generation this view belongs to, when it was created for one logical operation.
     *
     * Bound from the operation's [OperationRemoteIdentity], which captured it while the originating token
     * was still authoritative. Null on the uid-only/live paths, which read the active generation instead
     * — those are not issued under a token, so "current" is the only context they can have.
     */
    private val boundEpoch: Long? = null,
) : IdentityBoundNoteTransport, CloudNoteTransport, DatasetScopedCloudRevisionState {

    /**
     * Invalidates every revision learned from the previous dataset — see [DatasetScopedCloudRevisionState].
     *
     * The whole state goes, not only the departing uid's bucket: isolation replaces the local dataset
     * regardless of which account it belonged to, and a revision can be re-learned cheaply from the next
     * snapshot, while a stale one silently corrupts optimistic concurrency.
     */
    override suspend fun clearDatasetScopedState() {
        revisionState.resetDataset()
    }

    override suspend fun currentRevisionEpoch(): Long = revisionState.currentEpoch()

    /**
     * The dataset this operation belongs to, captured once before it suspends.
     *
     * One capture per logical operation on purpose. Capturing again after a suspension would let work
     * that began on the departing dataset present itself as belonging to the replacement.
     */
    private suspend fun datasetEpoch(): Long = boundEpoch ?: revisionState.currentEpoch()

    /**
     * This transport with every RPC authenticated as [identity] — the identity-bound path.
     *
     * The scoped view shares [revisionState] and wraps the client so that the *live-signature* members
     * the reused bodies call internally are themselves already bound to the identity. That is what
     * lets those bodies run unchanged without ever reaching the live-session overload: inside a
     * scoped view `rpc.callRpc(fn, body)` sends `identity.accessToken`, not the session's.
     */
    private fun scopedTo(identity: OperationRemoteIdentity) =
        SupabaseNoteTransport(IdentityScopedRpc(rpc, identity), revisionState, identity.revisionEpoch)

    override suspend fun fetchNotes(identity: OperationRemoteIdentity): List<CloudNoteRecord> =
        scopedTo(identity).fetchNotes(identity.ownerId)

    override suspend fun fetchNotesSnapshot(identity: OperationRemoteIdentity): CloudNoteSnapshot =
        scopedTo(identity).fetchNotesSnapshot(identity.ownerId)

    override suspend fun fetchNote(identity: OperationRemoteIdentity, noteId: Long): CloudNoteRecord? =
        scopedTo(identity).fetchNote(identity.ownerId, noteId)

    override suspend fun fetchTombstones(identity: OperationRemoteIdentity): Map<Long, Long> =
        scopedTo(identity).fetchTombstones(identity.ownerId)

    /**
     * One document, not the whole collection, on the identity-bound path.
     *
     * The default would filter `fetchTombstones`, which is the whole point of
     * [CloudNoteTransport.fetchTombstone] — so the scoped view is what makes this one identity-bound
     * rather than a live-session read hiding behind a signature that takes an identity.
     */
    override suspend fun fetchTombstone(identity: OperationRemoteIdentity, noteId: Long): Long? =
        scopedTo(identity).fetchTombstone(identity.ownerId, noteId)

    override suspend fun putNotes(
        identity: OperationRemoteIdentity,
        notes: List<Note>,
    ): Map<Long, CloudNoteTransport.PutResult> = scopedTo(identity).putNotes(identity.ownerId, notes)

    override suspend fun deleteNote(
        identity: OperationRemoteIdentity,
        noteId: Long,
        baseRevision: Long,
    ): CloudNoteTransport.DeleteResult =
        scopedTo(identity).deleteNote(identity.ownerId, noteId, baseRevision)

    override suspend fun deleteNotes(identity: OperationRemoteIdentity, noteIds: List<Long>) =
        scopedTo(identity).deleteNotes(identity.ownerId, noteIds)

    override suspend fun writeTombstone(identity: OperationRemoteIdentity, noteId: Long, deletedAt: Long) =
        scopedTo(identity).writeTombstone(identity.ownerId, noteId, deletedAt)

    override suspend fun deleteTombstones(identity: OperationRemoteIdentity, noteIds: List<Long>) =
        scopedTo(identity).deleteTombstones(identity.ownerId, noteIds)

    override suspend fun restoreNote(
        identity: OperationRemoteIdentity,
        note: Note,
    ): Map<Long, CloudNoteTransport.PutResult> = scopedTo(identity).restoreNote(identity.ownerId, note)

    override suspend fun writeSyncMeta(identity: OperationRemoteIdentity, noteCount: Int, platform: String) =
        scopedTo(identity).writeSyncMeta(identity.ownerId, noteCount, platform)

    override suspend fun deleteAllOwnedCloudData(identity: OperationRemoteIdentity) =
        scopedTo(identity).deleteAllOwnedCloudData(identity.ownerId)

    private data class SnapshotPayload(
        val notes: List<JsonElement>,
        val tombstones: List<JsonElement>,
        /** The server's own COUNT(*), already checked against [notes]. */
        val noteCount: Int,
    )

    private fun parsedSnapshot(snapshot: JsonObject): SnapshotPayload {
        val notes = snapshot["notes"]?.jsonArray.orEmpty()
        val expectedCount = snapshot["note_count"]?.jsonPrimitive?.longOrNull
            ?: error("Incomplete snapshot: missing note_count")
        // `note_count` is a separate COUNT(*) so a truncated jsonb_agg cannot look like a full
        // library. Check before clearing the revision map — a throw here must not wipe it.
        if (expectedCount != notes.size.toLong()) {
            error("Incomplete snapshot: expected $expectedCount notes, got ${notes.size}")
        }
        return SnapshotPayload(
            notes = notes,
            tombstones = snapshot["tombstones"]?.jsonArray.orEmpty(),
            noteCount = expectedCount.toInt(),
        )
    }

    override suspend fun fetchNotes(uid: String): List<CloudNoteRecord> =
        fetchNotesSnapshot(uid).records

    /**
     * Carries `note_count` out to the engine rather than only checking it here.
     *
     * Matching `note_count` against the raw `notes` array proves the *aggregate* was not truncated,
     * but the mapping below still drops any row whose id cannot be read — a row with neither a
     * numeric `local_id` nor a numeric `note_id` yields no record at all. That silent drop produces
     * exactly the payload the engine cannot tell from a deletion, so the authoritative count has to
     * travel with the records and be re-checked against what survived parsing.
     */
    override suspend fun fetchNotesSnapshot(uid: String): CloudNoteSnapshot =
        fetchSnapshot(uid, datasetEpoch = datasetEpoch())

    /**
     * The snapshot body, carrying the epoch of the operation that asked for it.
     *
     * [datasetEpoch] is a parameter rather than a fresh capture so that an operation which refreshes
     * the map *inside* another operation (the delete path's one-shot refresh) keeps its own generation
     * instead of adopting whatever is current by then.
     */
    private suspend fun fetchSnapshot(uid: String, datasetEpoch: Long): CloudNoteSnapshot {
        val snapshot = parsedSnapshot(rpc.callRpc("fetch_full_snapshot", buildJsonObject { }))
        val learned = mutableMapOf<Long, Long>()
        val records = snapshot.notes.mapNotNull { element ->
            val row = element.jsonObject
            val noteId = row.longId("local_id") ?: row.stringId("note_id")?.toLongOrNull()
                ?: return@mapNotNull null
            row.longId("revision")?.let { learned[noteId] = it }
            row.toCloudNoteRecord(noteId)
        }
        // One publication: a concurrent reader sees the previous revision set or this one, never a
        // half-applied mixture, and an operation from a replaced dataset changes nothing at all.
        revisionState.replace(datasetEpoch, uid, learned)
        return CloudNoteSnapshot(records = records, authoritativeNoteCount = snapshot.noteCount)
    }

    override suspend fun fetchNote(uid: String, noteId: Long): CloudNoteRecord? =
        fetchNotes(uid).firstOrNull { it.noteId == noteId }

    override suspend fun putNotes(uid: String, notes: List<Note>): Map<Long, CloudNoteTransport.PutResult> {
        val epoch = datasetEpoch()
        val result = mutableMapOf<Long, CloudNoteTransport.PutResult>()
        for (note in notes) {
            val noteId = note.id ?: continue
            val baseRevision = revisionState.read(epoch, uid, noteId)
            val response = try {
                rpc.callRpc("apply_note_change", note.toRpcArgs(baseRevision))
            } catch (failure: SupabaseTransportException) {
                // Note id, HTTP status and the server's reply only — never note content.
                AppLog.warn(
                    "NoteTransport",
                    "apply_note_change failed for note $noteId (baseRevision=$baseRevision, " +
                        "attachments=${note.attachments.size}): ${failure.message}",
                )
                throw failure
            }
            when (val status = response.stringField("status")) {
                "applied" -> {
                    val revision = response.longId("revision") ?: error("Missing required revision in successful apply_note_change")
                    val serverUpdatedAt = response.longId("server_updated_at")
                    revisionState.write(epoch, uid, noteId, revision)
                    result[noteId] = CloudNoteTransport.PutResult(revision, serverUpdatedAt)
                }
                "conflict" -> {
                    val current = response["current"]?.jsonObject
                    val revision = current?.longId("revision")
                    // A conflict leaves the note pending without any other trace, so say so.
                    AppLog.warn(
                        "NoteTransport",
                        "apply_note_change conflict for note $noteId (baseRevision=$baseRevision, " +
                            "error=${response.stringField("error")}, serverRevision=$revision)",
                    )
                    if (revision != null) revisionState.write(epoch, uid, noteId, revision)
                }
                else -> AppLog.warn(
                    "NoteTransport",
                    "apply_note_change returned unexpected status '$status' for note $noteId",
                )
            }
        }
        return result
    }

    override suspend fun deleteNotes(uid: String, noteIds: List<Long>) {
        // The revision map lives only in this instance, so a delete issued before the first
        // download of the process — NoteSyncEngine.deleteNote() goes straight here — had no base
        // revision and was dropped without reaching the server. The note stayed in the cloud and
        // came back on the next device that synced. Fetching once recovers the revisions the map
        // would have held; only the full-sync path used to populate it, and only by luck of
        // ordering.
        val epoch = datasetEpoch()
        var refreshed = false
        for (noteId in noteIds) {
            var baseRevision = revisionState.read(epoch, uid, noteId)
            if (baseRevision == null && !refreshed) {
                refreshed = true
                // The refresh keeps *this* operation's generation: a delete that began on the departing
                // dataset cannot adopt the replacement's revisions by refreshing inside it.
                runCatching { fetchSnapshot(uid, datasetEpoch = epoch) }
                baseRevision = revisionState.read(epoch, uid, noteId)
            }
            // Still unknown after a refresh: the note is not on the server, so there is nothing to
            // tombstone. apply_note_delete would answer note_not_found.
            if (baseRevision == null) continue

            val response = rpc.callRpc(
                "apply_note_delete",
                buildJsonObject {
                    put("p_note_id", JsonPrimitive(noteId.toString()))
                    put("p_base_revision", JsonPrimitive(baseRevision))
                },
            )
            when (response.stringField("status")) {
                // Covers the idempotent answer too, which carries no revision — keying the cleanup
                // on `revision` left a stale entry behind for an already-tombstoned note.
                "applied" -> revisionState.remove(epoch, uid, noteId)
                // Another device moved the note on. NO blind retry here anymore.
                "conflict" -> {
                    val current = response["current"]?.jsonObject?.longId("revision")
                    if (current != null) {
                        revisionState.write(epoch, uid, noteId, current)
                    } else {
                        revisionState.remove(epoch, uid, noteId)
                    }
                }
            }
        }
    }

    override suspend fun deleteNote(uid: String, noteId: Long, baseRevision: Long): CloudNoteTransport.DeleteResult {
        val response = rpc.callRpc(
            "apply_note_delete",
            buildJsonObject {
                put("p_note_id", JsonPrimitive(noteId.toString()))
                put("p_base_revision", JsonPrimitive(baseRevision))
            },
        )
        return when (response.stringField("status")) {
            "applied" -> CloudNoteTransport.DeleteResult.Success
            "conflict" -> CloudNoteTransport.DeleteResult.Conflict
            else -> CloudNoteTransport.DeleteResult.Conflict // or throw, but conflict is safe
        }
    }

    override suspend fun fetchTombstones(uid: String): Map<Long, Long> {
        val snapshot = parsedSnapshot(rpc.callRpc("fetch_full_snapshot", buildJsonObject { }))
        return snapshot.tombstones.mapNotNull { element ->
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

    override suspend fun restoreNote(uid: String, note: Note): Map<Long, CloudNoteTransport.PutResult> {
        val noteId = note.id ?: return emptyMap()
        // Captured once, before the RPC: the writes below belong to the dataset this restore began in.
        val epoch = datasetEpoch()
        val response = rpc.callRpc("restore_note", note.toRpcArgs(revisionState.read(epoch, uid, noteId)))
        return when (response.stringField("status")) {
            "applied" -> {
                val revision = response.longId("revision") ?: error("Missing required revision in successful restore_note")
                val serverUpdatedAt = response.longId("server_updated_at")
                revisionState.write(epoch, uid, noteId, revision)
                mapOf(noteId to CloudNoteTransport.PutResult(revision, serverUpdatedAt))
            }
            "conflict" -> {
                val current = response["current"]?.jsonObject?.longId("revision")
                if (current != null) revisionState.write(epoch, uid, noteId, current)
                emptyMap()
            }
            else -> emptyMap()
        }
    }

    override suspend fun writeSyncMeta(uid: String, noteCount: Int, platform: String) {
        // Optional metadata — direct table writes deferred until Phase 5 auth mapping is stable.
    }

    override suspend fun deleteSyncMeta(uid: String) {
        // Covered by delete_all_user_cloud_data during account wipe.
    }

    override suspend fun deleteAllOwnedCloudData(uid: String) {
        rpc.callRpc("delete_all_user_cloud_data", buildJsonObject { })
        revisionState.forget(uid)
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
        // R19.2: exactly the remote attachments this note version references. `apply_note_change`
        // promotes those provisional rows to committed in the same transaction as the note write, and
        // only those — a provisional row for the same note that this version does not name (an upload
        // whose continuation went stale) stays provisional and therefore stays out of hydration.
        //
        // Only `r2:` references can have a server-side row: a `pending:`/`file:` attachment's bytes are
        // still local, and naming one would promote a row this note does not consider remote.
        put(
            "p_attachment_ids",
            buildJsonArray {
                attachments.filter { it.storagePath.startsWith(ATTACHMENT_R2_PREFIX) }
                    .forEach { add(JsonPrimitive(it.id)) }
            },
        )
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
            revision = longId("revision") ?: error("Missing revision in note snapshot")
        )
    }

    private fun JsonObject.stringField(key: String): String? =
        this[key]?.jsonPrimitive?.content

    private fun JsonObject.stringId(key: String): String? = stringField(key)

    private fun JsonObject.longId(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull
}

/**
 * A [SupabaseRpcClient] whose *live-session-signature* calls are bound to [identity] instead.
 *
 * This is what makes [SupabaseNoteTransport]'s identity-bound members safe without duplicating its
 * RPC bodies: the scoped transport view reuses those bodies unchanged, and every `callRpc(fn, body)`
 * they make lands here, where the bearer is the captured identity's rather than the live session's.
 * The live-session overload is therefore unreachable from an identity-bound call.
 */
private class IdentityScopedRpc(
    private val delegate: SupabaseRpcClient,
    private val identity: OperationRemoteIdentity,
) : SupabaseRpcClient {

    override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject =
        delegate.callRpc(identity, functionName, body)

    override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement =
        delegate.callRpcElement(identity, functionName, body)

    override suspend fun callRpc(identity: OperationRemoteIdentity, functionName: String, body: JsonObject): JsonObject =
        delegate.callRpc(identity, functionName, body)

    override suspend fun callRpcElement(
        identity: OperationRemoteIdentity,
        functionName: String,
        body: JsonObject,
    ): JsonElement = delegate.callRpcElement(identity, functionName, body)
}
