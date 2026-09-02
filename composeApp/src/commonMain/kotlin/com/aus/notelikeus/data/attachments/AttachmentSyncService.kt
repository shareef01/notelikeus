package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.mapper.toNote
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.NoopAttachmentBlobTransport
import com.aus.notelikeus.data.remote.SupabaseAttachmentMetadata
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note

class AttachmentSyncService(
    private val blobTransport: AttachmentBlobTransport,
    private val metadata: SupabaseAttachmentMetadata?,
    private val localStorage: AttachmentLocalStorage,
    private val noteDao: NoteDao,
) {
    private val enabled: Boolean
        get() = isR2AttachmentsEnabled() && blobTransport !is NoopAttachmentBlobTransport && metadata != null

    fun mergeAttachmentsIntoNotes(
        notes: List<Note>,
        rows: List<NoteAttachmentMetadata>,
    ): List<Note> = com.aus.notelikeus.data.attachments.mergeAttachmentsIntoNotes(notes, rows)

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
        val synced = mutableListOf<Attachment>()
        for (attachment in note.attachments) {
            when {
                isPendingAttachment(attachment.storagePath) -> {
                    val pendingId = attachment.storagePath.removePrefix(ATTACHMENT_PENDING_PREFIX)
                    val pending = PendingAttachmentStore.take(pendingId)
                        ?: PendingAttachmentStore.peek(pendingId)
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
                    localStorage.deleteIfLocal(attachment.storagePath)
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

    suspend fun deleteAttachmentsForNote(noteId: Long, attachments: List<Attachment>) {
        if (!enabled || attachments.isEmpty()) return
        val noteIdStr = noteId.toString()
        for (attachment in attachments) {
            when {
                isPendingAttachment(attachment.storagePath) -> {
                    val pendingId = attachment.storagePath.removePrefix(ATTACHMENT_PENDING_PREFIX)
                    PendingAttachmentStore.take(pendingId)
                }
                isFileAttachment(attachment.storagePath) -> localStorage.deleteIfLocal(attachment.storagePath)
                isR2Attachment(attachment.storagePath) -> runCatching {
                    blobTransport.delete(noteIdStr, attachment.id)
                }
            }
        }
    }

    suspend fun readAttachmentBytes(attachment: Attachment): ByteArray? {
        return when {
            isPendingAttachment(attachment.storagePath) -> {
                val pendingId = attachment.storagePath.removePrefix(ATTACHMENT_PENDING_PREFIX)
                PendingAttachmentStore.peek(pendingId)?.bytes
            }
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
}
