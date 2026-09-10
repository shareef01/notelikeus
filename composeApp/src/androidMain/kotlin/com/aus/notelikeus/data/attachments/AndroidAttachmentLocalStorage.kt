package com.aus.notelikeus.data.attachments

import android.content.Context
import java.io.File
import java.util.UUID

class AndroidAttachmentLocalStorage(
    private val context: Context,
) : AttachmentLocalStorage {
    private val attachmentsDir: File
        get() = File(context.filesDir, "attachments").also { it.mkdirs() }

    override fun persistImageBytes(bytes: ByteArray, extension: String): String? {
        val safeExtension = extension.ifBlank { "jpg" }
        val destFile = File(attachmentsDir, "${UUID.randomUUID()}.$safeExtension")
        return try {
            destFile.writeBytes(bytes)
            fileStoragePath(destFile.absolutePath)
        } catch (_: Exception) {
            destFile.delete()
            null
        }
    }

    override fun readBytes(storagePath: String): ByteArray? {
        val file = resolveContainedFile(storagePath) ?: return null
        if (!file.exists()) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    override fun exists(storagePath: String): Boolean? {
        val file = resolveContainedFile(storagePath) ?: return false
        return runCatching { file.exists() }.getOrNull()
    }

    override fun deleteIfLocal(storagePath: String) {
        val file = resolveContainedFile(storagePath) ?: return
        runCatching { file.delete() }
    }

    /**
     * Every filesystem operation on a `file:` location goes through here so a note row that
     * somehow carries a path outside the managed attachments directory cannot be read or deleted.
     */
    private fun resolveContainedFile(storagePath: String): File? {
        val raw = localFilePath(storagePath) ?: return null
        if (raw.indexOf('\u0000') >= 0) return null
        val root = runCatching { attachmentsDir.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(raw).canonicalFile }.getOrNull() ?: return null
        if (!isStrictChildPath(root.path, candidate.path, ignoreCase = false)) return null
        return candidate
    }
}
