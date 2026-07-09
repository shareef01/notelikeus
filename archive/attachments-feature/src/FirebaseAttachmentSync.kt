package com.aus.notelikeus.data.remote

import android.util.Log
import com.aus.notelikeus.data.local.AttachmentStorage
import com.aus.notelikeus.domain.model.Attachment
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FirebaseAttachmentSync"

@Singleton
class FirebaseAttachmentSync @Inject constructor(
    private val storage: FirebaseStorage,
    private val attachmentStorage: AttachmentStorage
) {
    suspend fun uploadAttachments(
        userId: String,
        noteId: Long,
        attachments: List<Attachment>
    ): List<Map<String, Any?>> {
        if (attachments.isEmpty()) return emptyList()
        return attachments.mapNotNull { attachment ->
            runCatching { uploadAttachment(userId, noteId, attachment) }
                .onFailure { error ->
                    Log.w(
                        TAG,
                        "Attachment upload skipped for note $noteId (Storage may require Blaze plan)",
                        error
                    )
                }
                .getOrNull()
        }
    }

    suspend fun downloadAttachments(
        userId: String,
        noteId: Long,
        cloudAttachments: List<Map<String, Any?>>
    ): List<Attachment> {
        if (cloudAttachments.isEmpty()) return emptyList()
        return cloudAttachments.mapNotNull { entry ->
            runCatching { downloadAttachment(userId, noteId, entry) }
                .onFailure { error ->
                    Log.w(TAG, "Attachment download skipped for note $noteId", error)
                }
                .getOrNull()
        }
    }

    suspend fun deleteNoteAttachments(userId: String, noteId: Long) {
        val folderRef = storage.reference.child(noteAttachmentFolder(userId, noteId))
        runCatching {
            val listing = folderRef.listAll().await()
            listing.items.forEach { item ->
                item.delete().await()
            }
        }
    }

    private suspend fun uploadAttachment(
        userId: String,
        noteId: Long,
        attachment: Attachment
    ): Map<String, Any?>? {
        val storagePath = uploadLocalAttachment(userId, noteId, attachment) ?: return null
        return mapOf(
            "storagePath" to storagePath,
            "type" to attachment.type,
            "contentType" to contentTypeForPath(storagePath)
        )
    }

    private suspend fun uploadLocalAttachment(
        userId: String,
        noteId: Long,
        attachment: Attachment
    ): String? {
        val bytes = attachmentStorage.readLocalBytes(attachment.uri) ?: return null
        val extension = extensionFromPath(attachment.uri)
        val storagePath = noteAttachmentObject(userId, noteId, extension)
        val metadata = com.google.firebase.storage.StorageMetadata.Builder()
            .setContentType(contentTypeForExtension(extension))
            .build()
        storage.reference.child(storagePath)
            .putBytes(bytes, metadata)
            .await()
        return storagePath
    }

    private suspend fun downloadAttachment(
        userId: String,
        noteId: Long,
        entry: Map<String, Any?>
    ): Attachment? {
        val storagePath = entry["storagePath"] as? String ?: return null
        if (!storagePath.startsWith("users/$userId/")) return null
        val bytes = storage.reference.child(storagePath).getBytes(MAX_DOWNLOAD_BYTES).await()
        val extension = extensionFromPath(storagePath)
        val localPath = attachmentStorage.persistImageFromBytes(bytes, extension) ?: return null
        return Attachment(
            noteId = noteId,
            uri = localPath,
            type = entry["type"] as? String ?: "image"
        )
    }

    private fun noteAttachmentFolder(userId: String, noteId: Long): String {
        return "users/$userId/notes/$noteId"
    }

    private fun noteAttachmentObject(userId: String, noteId: Long, extension: String): String {
        return "${noteAttachmentFolder(userId, noteId)}/image.$extension"
    }

    private fun extensionFromPath(path: String): String {
        return File(path.substringAfterLast('/')).extension.ifBlank { "jpg" }
    }

    private fun contentTypeForPath(path: String): String {
        return contentTypeForExtension(extensionFromPath(path))
    }

    private fun contentTypeForExtension(extension: String): String {
        return when (extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
    }

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 10L * 1024L * 1024L
    }
}
