package com.aus.notelikeus.data.attachments

import java.io.File
import java.util.UUID

class DesktopAttachmentLocalStorage : AttachmentLocalStorage {
    private val attachmentsDir: File
        get() = File(System.getProperty("user.home"), ".notelikeus/attachments").also { it.mkdirs() }

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
     *
     * Windows path comparison is case-insensitive; separators are normalised by
     * [isStrictChildPath].
     */
    private fun resolveContainedFile(storagePath: String): File? {
        val raw = localFilePath(storagePath) ?: return null
        if (raw.indexOf('\u0000') >= 0) return null
        val root = runCatching { attachmentsDir.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(raw).canonicalFile }.getOrNull() ?: return null
        val ignoreCase = File.separatorChar == '\\'
        if (!isStrictChildPath(root.path, candidate.path, ignoreCase = ignoreCase)) return null
        return candidate
    }
}
