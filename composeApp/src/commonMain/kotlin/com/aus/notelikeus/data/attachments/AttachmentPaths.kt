package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val ATTACHMENT_PENDING_PREFIX = "pending:"
const val ATTACHMENT_R2_PREFIX = "r2:"
const val ATTACHMENT_FILE_PREFIX = "file:"
const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

@OptIn(ExperimentalUuidApi::class)
fun createAttachmentId(): String = Uuid.random().toString()

fun pendingStoragePath(attachmentId: String): String = "$ATTACHMENT_PENDING_PREFIX$attachmentId"

fun fileStoragePath(absolutePath: String): String = "$ATTACHMENT_FILE_PREFIX$absolutePath"

fun isPendingAttachment(storagePath: String): Boolean =
    storagePath.startsWith(ATTACHMENT_PENDING_PREFIX)

fun isR2Attachment(storagePath: String): Boolean =
    storagePath.startsWith(ATTACHMENT_R2_PREFIX)

fun isFileAttachment(storagePath: String): Boolean =
    storagePath.startsWith(ATTACHMENT_FILE_PREFIX)

fun localFilePath(storagePath: String): String? =
    if (isFileAttachment(storagePath)) storagePath.removePrefix(ATTACHMENT_FILE_PREFIX) else null

fun r2ObjectKey(storagePath: String): String? =
    if (isR2Attachment(storagePath)) storagePath.removePrefix(ATTACHMENT_R2_PREFIX) else null

data class NoteAttachmentMetadata(
    val attachmentId: String,
    val noteId: String,
    val objectKey: String,
    val mimeType: String,
    val sizeBytes: Long,
    val attachmentType: String,
    val createdAt: Long = 0L,
)

fun attachmentFromMetadata(note: Note, row: NoteAttachmentMetadata): Attachment {
    require(note.id != null) { "note id required" }
    return Attachment(
        id = row.attachmentId,
        noteId = note.id,
        storagePath = "$ATTACHMENT_R2_PREFIX${row.objectKey}",
        type = row.attachmentType,
        mimeType = row.mimeType,
        sizeBytes = row.sizeBytes,
    )
}

fun mergeAttachmentsIntoNotes(
    notes: List<Note>,
    rows: List<NoteAttachmentMetadata>,
): List<Note> {
    val byNoteId = rows.groupBy { it.noteId }
    return notes.map { note ->
        val noteId = note.id?.toString() ?: return@map note
        val remote = (byNoteId[noteId] ?: emptyList()).map { row -> attachmentFromMetadata(note, row) }
        val pending = note.attachments.filter { isPendingAttachment(it.storagePath) }
        val remoteIds = remote.map { it.id }.toSet()
        val keptPending = pending.filter { it.id !in remoteIds }
        note.copy(attachments = remote + keptPending)
    }
}

fun firstImageAttachment(attachments: List<Attachment>): Attachment? =
    attachments.firstOrNull { attachment ->
        val mime = attachment.mimeType?.lowercase().orEmpty()
        attachment.type.equals("image", ignoreCase = true) || mime.startsWith("image/")
    }

fun attachmentsKey(attachments: List<Attachment>): String =
    attachments
        .map { "${it.id}:${it.storagePath}:${it.mimeType.orEmpty()}:${it.sizeBytes ?: ""}" }
        .sorted()
        .joinToString(",")
