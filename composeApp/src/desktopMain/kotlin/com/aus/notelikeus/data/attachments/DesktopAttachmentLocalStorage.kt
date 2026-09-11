package com.aus.notelikeus.data.attachments

import java.io.File
import java.util.UUID

class DesktopAttachmentLocalStorage(
    private val protector: AttachmentBytesProtector = NoopAttachmentBytesProtector,
    private val homeDir: File = File(System.getProperty("user.home")),
) : AttachmentLocalStorage {
    private val attachmentsDir: File
        get() = File(homeDir, ".notelikeus/attachments").also { it.mkdirs() }

    override fun persistImageBytes(bytes: ByteArray, extension: String): String? {
        val safeExtension = extension.ifBlank { "jpg" }
        val destFile = File(attachmentsDir, "${UUID.randomUUID()}.$safeExtension")
        val tempFile = File(attachmentsDir, "${destFile.name}.tmp")
        return try {
            val aad = destFile.name.toByteArray(Charsets.UTF_8)
            val sealed = protector.seal(bytes, aad)
            tempFile.writeBytes(sealed)
            if (destFile.exists()) destFile.delete()
            if (!tempFile.renameTo(destFile)) {
                destFile.writeBytes(sealed)
                tempFile.delete()
            }
            fileStoragePath(destFile.absolutePath)
        } catch (_: Exception) {
            tempFile.delete()
            destFile.delete()
            null
        }
    }

    override fun readBytes(storagePath: String): ByteArray? {
        val file = resolveContainedFile(storagePath) ?: return null
        if (!file.exists()) return null
        val raw = runCatching { file.readBytes() }.getOrNull() ?: return null
        val aad = file.name.toByteArray(Charsets.UTF_8)
        return protector.open(raw, aad)
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
