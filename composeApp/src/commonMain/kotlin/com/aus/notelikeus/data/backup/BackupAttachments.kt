package com.aus.notelikeus.data.backup

import com.aus.notelikeus.data.attachments.AttachmentLocalStorage
import com.aus.notelikeus.data.attachments.MAX_ATTACHMENT_BYTES
import com.aus.notelikeus.data.attachments.PendingAttachmentStore
import com.aus.notelikeus.data.attachments.createAttachmentId
import com.aus.notelikeus.data.attachments.pendingStoragePath
import com.aus.notelikeus.domain.model.Attachment
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal const val MIN_BACKUP_VERSION_WITH_ATTACHMENTS = 3
internal const val MAX_NOTE_BACKUP_ATTACHMENTS = 20

private val ALLOWED_IMAGE_MIMES = setOf(
    "image/jpeg",
    "image/jpg",
    "image/png",
    "image/webp",
    "image/gif",
)

fun interface AttachmentBackupReader {
    suspend fun readBytes(attachment: Attachment): ByteArray?
}

@OptIn(ExperimentalEncodingApi::class)
internal fun encodeAttachmentBytes(bytes: ByteArray): String = Base64.encode(bytes)

@OptIn(ExperimentalEncodingApi::class)
internal fun decodeAttachmentBytes(dataBase64: String): ByteArray? =
    runCatching { Base64.decode(dataBase64.trim()) }.getOrNull()

internal fun extensionFromMime(mimeType: String): String = when (mimeType.lowercase()) {
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    else -> "jpg"
}

internal fun isAllowedBackupImageMime(mimeType: String): Boolean =
    mimeType.lowercase() in ALLOWED_IMAGE_MIMES

internal suspend fun attachmentsToBackupDtos(
    attachments: List<Attachment>,
    reader: AttachmentBackupReader?,
): List<AttachmentBackupDto> {
    if (reader == null || attachments.isEmpty()) return emptyList()
    val exported = mutableListOf<AttachmentBackupDto>()
    for (attachment in attachments) {
        if (exported.size >= MAX_NOTE_BACKUP_ATTACHMENTS) break
        if (attachment.type.isNotBlank() && !attachment.type.equals("image", ignoreCase = true)) {
            continue
        }
        val mimeType = attachment.mimeType?.takeIf { it.isNotBlank() } ?: "image/jpeg"
        if (!isAllowedBackupImageMime(mimeType)) continue
        val bytes = reader.readBytes(attachment) ?: continue
        if (bytes.isEmpty() || bytes.size > MAX_ATTACHMENT_BYTES) continue
        exported += AttachmentBackupDto(
            id = attachment.id,
            type = "image",
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            dataBase64 = encodeAttachmentBytes(bytes),
            extension = extensionFromMime(mimeType),
        )
    }
    return exported
}

internal fun attachmentsFromBackupDtos(
    dtos: List<AttachmentBackupDto>,
    noteId: Long,
    backupVersion: Int,
    localStorage: AttachmentLocalStorage?,
): List<Attachment> {
    if (backupVersion < MIN_BACKUP_VERSION_WITH_ATTACHMENTS || dtos.isEmpty()) {
        return emptyList()
    }
    val restored = mutableListOf<Attachment>()
    for (dto in dtos) {
        if (restored.size >= MAX_NOTE_BACKUP_ATTACHMENTS) break
        if (dto.type.isNotBlank() && !dto.type.equals("image", ignoreCase = true)) continue
        val mimeType = dto.mimeType?.takeIf { it.isNotBlank() } ?: "image/jpeg"
        if (!isAllowedBackupImageMime(mimeType)) continue
        val bytes = decodeAttachmentBytes(dto.dataBase64) ?: continue
        if (bytes.isEmpty() || bytes.size > MAX_ATTACHMENT_BYTES) continue
        val id = dto.id?.trim()?.takeIf { it.isNotEmpty() } ?: createAttachmentId()
        val extension = dto.extension?.trim()?.takeIf { it.isNotEmpty() } ?: extensionFromMime(mimeType)
        val storagePath = localStorage?.persistImageBytes(bytes, extension)
            ?: pendingStoragePath(id).also { PendingAttachmentStore.put(id, bytes, mimeType) }
        restored += Attachment(
            id = id,
            noteId = noteId,
            storagePath = storagePath,
            type = "image",
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
        )
    }
    return restored
}
