package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.mapper.toNote
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.NoopAttachmentBlobTransport
import com.aus.notelikeus.data.remote.SupabaseAttachmentMetadata
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.util.AppLog

class AttachmentSyncService(
    private val blobTransport: AttachmentBlobTransport,
    private val metadata: SupabaseAttachmentMetadata?,
    private val localStorage: AttachmentLocalStorage,
    private val noteDao: NoteDao,
    private val staging: AttachmentStagingStore,
    /** Current account, or null while signed out. Staged bytes are namespaced by its result. */
    private val ownerIdProvider: () -> String? = { null },
) {
    private val cache = PendingAttachmentCache()

    private val enabled: Boolean
        get() = isR2AttachmentsEnabled() && blobTransport !is NoopAttachmentBlobTransport && metadata != null

    private fun ownerId(): String = ownerIdProvider() ?: GUEST_STAGING_OWNER

    fun mergeAttachmentsIntoNotes(
        notes: List<Note>,
        rows: List<NoteAttachmentMetadata>,
    ): List<Note> = com.aus.notelikeus.data.attachments.mergeAttachmentsIntoNotes(notes, rows)

    /**
     * Stages [bytes] durably so the caller may add the attachment to a note.
     *
     * Returns false when the bytes did not land: the caller must then not reference the
     * attachment, rather than persisting a note that points at bytes which were never written.
     */
    suspend fun stageAttachment(
        attachmentId: String,
        noteId: Long?,
        bytes: ByteArray,
        mimeType: String,
    ): Boolean {
        val owner = ownerId()
        val staged = staging.stage(attachmentId, owner, noteId, bytes, mimeType) ?: return false
        cache.put(owner, attachmentId, PendingAttachment(bytes, staged.mimeType))
        return true
    }

    /** Records the Room id a staged attachment ended up on, once the insert has issued one. */
    suspend fun bindStagedAttachmentsToNote(noteId: Long, attachments: List<Attachment>) {
        val owner = ownerId()
        for (attachment in attachments) {
            if (!isPendingAttachment(attachment.storagePath)) continue
            staging.bindNote(pendingId(attachment.storagePath), owner, noteId)
        }
    }

    /** Drops staged bytes the user explicitly removed, or that a discarded note referenced. */
    suspend fun releaseStagedAttachments(attachments: List<Attachment>) {
        val owner = ownerId()
        for (attachment in attachments) {
            if (!isPendingAttachment(attachment.storagePath)) continue
            val id = pendingId(attachment.storagePath)
            cache.remove(owner, id)
            staging.release(id, owner)
        }
    }

    suspend fun hydrateAllNotes(): Int {
        if (!enabled) return 0
        val metadataClient = metadata ?: return 0
        val rows = metadataClient.listUserAttachments()
        if (rows.isEmpty()) return 0
        var updated = 0
        val notes = noteDao.getAllNotesForBackup().map { it.toNote() }
        for (merged in mergeAttachmentsIntoNotes(notes, rows)) {
            val original = notes.firstOrNull { it.id == merged.id } ?: continue
            if (attachmentsKey(original.attachments) == attachmentsKey(merged.attachments)) continue
            noteDao.updateNote(merged.toNoteEntity())
            updated++
        }
        return updated
    }

    suspend fun syncNoteAttachments(note: Note): Note {
        if (!enabled || note.attachments.isEmpty()) return note
        val noteId = note.id?.toString() ?: return note
        val owner = ownerId()
        val synced = mutableListOf<Attachment>()
        for (attachment in note.attachments) {
            when {
                isPendingAttachment(attachment.storagePath) -> {
                    val pendingId = pendingId(attachment.storagePath)
                    // Keep the only local copy until the note revision commits. A miss here means
                    // the bytes belong to another account's namespace, or were never staged by a
                    // build that predates durable staging — either way there is nothing to upload,
                    // and the reference is left alone rather than dropped from the user's note.
                    val pending = loadStaged(owner, pendingId)
                    if (pending == null) {
                        synced.add(attachment)
                        continue
                    }
                    val result = blobTransport.upload(
                        noteId = noteId,
                        attachmentId = attachment.id,
                        bytes = pending.bytes,
                        mimeType = pending.mimeType,
                    )
                    synced.add(
                        attachment.copy(
                            storagePath = "$ATTACHMENT_R2_PREFIX${result.objectKey}",
                            mimeType = result.mimeType,
                            sizeBytes = result.sizeBytes,
                        ),
                    )
                }
                isFileAttachment(attachment.storagePath) -> {
                    val bytes = localStorage.readBytes(attachment.storagePath) ?: run {
                        synced.add(attachment)
                        continue
                    }
                    val mimeType = attachment.mimeType ?: "image/jpeg"
                    val result = blobTransport.upload(
                        noteId = noteId,
                        attachmentId = attachment.id,
                        bytes = bytes,
                        mimeType = mimeType,
                    )
                    synced.add(
                        attachment.copy(
                            storagePath = "$ATTACHMENT_R2_PREFIX${result.objectKey}",
                            mimeType = result.mimeType,
                            sizeBytes = result.sizeBytes,
                        ),
                    )
                }
                else -> synced.add(attachment)
            }
        }
        return note.copy(attachments = synced)
    }

    suspend fun syncNotesAttachments(notes: List<Note>): List<Note> =
        notes.map { syncNoteAttachments(it) }

    /**
     * Drops local source copies only after the live note revision that references them
     * has been accepted by the server. Never called as compensation for a failed upload.
     */
    suspend fun confirmCommittedAttachments(note: Note) {
        if (!enabled) return
        val owner = ownerId()
        for (attachment in note.attachments) {
            when {
                isPendingAttachment(attachment.storagePath) -> {
                    val pendingId = pendingId(attachment.storagePath)
                    cache.remove(owner, pendingId)
                    staging.release(pendingId, owner)
                }
                isFileAttachment(attachment.storagePath) -> localStorage.deleteIfLocal(attachment.storagePath)
            }
        }
    }

    suspend fun deleteAttachmentsForNote(noteId: Long, attachments: List<Attachment>) {
        if (!enabled || attachments.isEmpty()) return
        val noteIdStr = noteId.toString()
        val owner = ownerId()
        var remoteFailure: Throwable? = null
        for (attachment in attachments) {
            when {
                isPendingAttachment(attachment.storagePath) -> {
                    val pendingId = pendingId(attachment.storagePath)
                    cache.remove(owner, pendingId)
                    staging.release(pendingId, owner)
                }
                isFileAttachment(attachment.storagePath) -> localStorage.deleteIfLocal(attachment.storagePath)
                isR2Attachment(attachment.storagePath) -> runCatching {
                    blobTransport.delete(noteIdStr, attachment.id)
                }.onFailure { error ->
                    if (remoteFailure == null) remoteFailure = error
                }
            }
        }
        remoteFailure?.let { throw it }
    }

    /**
     * Deletes R2 objects the server already marked pending-deleted for tombstoned notes.
     * Skips [skipNoteId] (in-flight restores) and prefers orphan storage on failure.
     */
    suspend fun sweepPendingDeletedAttachments(skipNoteId: (String) -> Boolean = { false }) {
        if (!enabled) return
        val metadataClient = metadata ?: return
        val pending = runCatching { metadataClient.listPendingDeletedAttachments() }.getOrNull() ?: return
        for (row in pending) {
            if (skipNoteId(row.noteId)) continue
            runCatching {
                blobTransport.delete(row.noteId, row.attachmentId)
                metadataClient.purgeDeleted(row.attachmentId, row.noteId)
            }
        }
    }

    /**
     * Re-drives uploads for bytes staged before a restart, and drops staged bytes no live note
     * references any more.
     *
     * Staged bytes are only released when no note claims them: ageing them out would delete the
     * only copy of an image belonging to a note the user can still see. Bytes referenced by a note
     * whose staging is gone (older builds staged in memory) are reported through [onMissingBytes]
     * so the caller can surface a recoverable broken attachment instead of silently erasing it.
     */
    suspend fun reconcileStagedAttachments(
        onMissingBytes: (Note, Attachment) -> Unit = { _, _ -> },
    ): Int {
        val owner = ownerId()
        val notes = runCatching { noteDao.getAllNotesForBackup().map { it.toNote() } }.getOrNull()
            ?: return 0
        val referenced = mutableSetOf<String>()
        var resumed = 0

        for (note in notes) {
            val pending = note.attachments.filter { isPendingAttachment(it.storagePath) }
            if (pending.isEmpty()) continue
            for (attachment in pending) {
                val id = pendingId(attachment.storagePath)
                referenced.add(id)
                if (staging.metadata(id, owner) == null) onMissingBytes(note, attachment)
            }
            if (!enabled) continue
            val synced = runCatching { syncNoteAttachments(note) }.getOrElse { error ->
                // Still offline, or the Worker is down: the staged bytes stay put and the next
                // reconciliation retries. Nothing about the local note changes.
                AppLog.warn(TAG, "Resuming staged attachment upload failed", error)
                continue
            }
            if (attachmentsKey(note.attachments) == attachmentsKey(synced.attachments)) continue
            runCatching { noteDao.updateNote(synced.toNoteEntity()) }
                .onSuccess { resumed++ }
        }

        // Anything staged that no note references is unreachable — the note was discarded or the
        // attachment removed while the release did not land.
        for (staged in staging.list(owner)) {
            if (staged.attachmentId in referenced) continue
            cache.remove(owner, staged.attachmentId)
            staging.release(staged.attachmentId, owner)
        }
        return resumed
    }

    /**
     * Moves bytes staged while signed out into [newOwnerId]'s namespace at sign-in.
     *
     * Guest and signed-in notes share one Room database on these clients, so a note written before
     * signing in keeps its attachments afterwards; without this its staged bytes would sit in a
     * namespace the upload path no longer looks at.
     */
    suspend fun adoptGuestStagedAttachments(newOwnerId: String) {
        if (newOwnerId == GUEST_STAGING_OWNER) return
        for (staged in staging.list(GUEST_STAGING_OWNER)) {
            val bytes = staging.readBytes(staged.attachmentId, GUEST_STAGING_OWNER) ?: continue
            val moved = staging.stage(
                attachmentId = staged.attachmentId,
                ownerId = newOwnerId,
                noteId = staged.noteId,
                bytes = bytes,
                mimeType = staged.mimeType,
            )
            if (moved != null) {
                cache.remove(GUEST_STAGING_OWNER, staged.attachmentId)
                staging.release(staged.attachmentId, GUEST_STAGING_OWNER)
            }
        }
    }

    /** Drops cached bytes so one account's images cannot be read from another's session. */
    suspend fun clearStagingCache() {
        cache.clear()
    }

    suspend fun readAttachmentBytes(attachment: Attachment): ByteArray? {
        return when {
            isPendingAttachment(attachment.storagePath) ->
                loadStaged(ownerId(), pendingId(attachment.storagePath))?.bytes
            isFileAttachment(attachment.storagePath) -> localStorage.readBytes(attachment.storagePath)
            isR2Attachment(attachment.storagePath) -> if (enabled) {
                runCatching {
                    blobTransport.download(attachment.noteId.toString(), attachment.id)
                }.getOrNull()
            } else {
                null
            }
            else -> null
        }
    }

    private suspend fun loadStaged(owner: String, pendingId: String): PendingAttachment? {
        cache.get(owner, pendingId)?.let { return it }
        val staged = staging.metadata(pendingId, owner) ?: return null
        val bytes = staging.readBytes(pendingId, owner) ?: return null
        val value = PendingAttachment(bytes, staged.mimeType)
        cache.put(owner, pendingId, value)
        return value
    }

    private fun pendingId(storagePath: String): String =
        storagePath.removePrefix(ATTACHMENT_PENDING_PREFIX)

    private companion object {
        const val TAG = "AttachmentSync"
    }
}
