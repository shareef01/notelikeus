package com.aus.notelikeus.data.local

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttachmentStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val attachmentsDir: File
        get() = File(context.filesDir, "attachments").also { it.mkdirs() }

    fun persistImage(sourceUri: Uri): String? {
        val extension = resolveExtension(sourceUri)
        val destFile = File(attachmentsDir, "${UUID.randomUUID()}.$extension")
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            destFile.absolutePath
        } catch (_: Exception) {
            destFile.delete()
            null
        }
    }

    fun deleteIfLocal(uri: String) {
        if (!isLocalPath(uri)) return
        runCatching { File(uri).delete() }
    }

    fun isLocalPath(uri: String): Boolean {
        val path = uri.removePrefix("file://")
        return path.startsWith(attachmentsDir.absolutePath)
    }

    fun readLocalBytes(uri: String): ByteArray? {
        if (!isLocalPath(uri)) return null
        val path = uri.removePrefix("file://")
        val file = File(path)
        if (!file.exists()) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    fun persistImageFromBytes(bytes: ByteArray, extension: String = "jpg"): String? {
        val safeExtension = extension.ifBlank { "jpg" }
        val destFile = File(attachmentsDir, "${UUID.randomUUID()}.$safeExtension")
        return try {
            destFile.writeBytes(bytes)
            destFile.absolutePath
        } catch (_: Exception) {
            destFile.delete()
            null
        }
    }

    private fun resolveExtension(uri: Uri): String {
        val mimeType = context.contentResolver.getType(uri)
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "jpg"
    }
}
