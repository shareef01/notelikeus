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
        val path = localFilePath(storagePath) ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    override fun deleteIfLocal(storagePath: String) {
        val path = localFilePath(storagePath) ?: return
        runCatching { File(path).delete() }
    }
}
