package com.aus.notelikeus.ui.navigation

import android.content.ContentResolver
import android.net.Uri
import com.aus.notelikeus.data.attachments.MAX_ATTACHMENT_BYTES
import com.aus.notelikeus.util.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream

sealed interface IngestionResult {
    data class Success(val bytes: ByteArray, val mimeType: String) : IngestionResult
    data class Failure(val reason: String) : IngestionResult
}

interface ExternalImageIngestor {
    suspend fun ingest(uri: Uri, declaredMimeType: String?): IngestionResult
}

/**
 * Safely copies stream bytes up to [maxBytes] into memory.
 *
 * If the stream produces more than [maxBytes] or 0 bytes, reading stops immediately and returns null.
 * This ensures untrusted external streams cannot trigger unbounded memory allocation.
 */
fun readBoundedStream(inputStream: InputStream, maxBytes: Int = MAX_ATTACHMENT_BYTES): ByteArray? {
    val buffer = ByteArray(8192)
    val output = ByteArrayOutputStream()
    var total = 0
    while (true) {
        val read = inputStream.read(buffer)
        if (read == -1) break
        total += read
        if (total > maxBytes) {
            return null
        }
        output.write(buffer, 0, read)
    }
    if (output.size() == 0) return null
    return output.toByteArray()
}

class DefaultExternalImageIngestor(
    private val contentResolver: ContentResolver,
    private val maxBytes: Int = MAX_ATTACHMENT_BYTES,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ExternalImageIngestor {

    private fun resolveMimeType(uri: Uri, declaredMimeType: String?): String? {
        val typeFromResolver = runCatching { contentResolver.getType(uri) }.getOrNull()
        return when {
            typeFromResolver != null && typeFromResolver.startsWith("image/") -> typeFromResolver
            typeFromResolver != null -> null
            declaredMimeType != null &&
                declaredMimeType.startsWith("image/") &&
                declaredMimeType != "image/*" -> declaredMimeType
            else -> "image/jpeg"
        }
    }

    private fun openStream(uri: Uri): Result<InputStream> = runCatching {
        contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Provider returned null stream")
    }

    override suspend fun ingest(uri: Uri, declaredMimeType: String?): IngestionResult =
        withContext(ioDispatcher) {
            if (uri.scheme != "content") {
                return@withContext IngestionResult.Failure("Unsupported scheme: ${uri.scheme}")
            }

            val effectiveMime = resolveMimeType(uri, declaredMimeType)
                ?: return@withContext IngestionResult.Failure("Provider type is not an image")

            val stream = openStream(uri).getOrElse { error ->
                AppLog.warn(TAG, "Error opening stream for shared image: ${error.javaClass.simpleName}")
                return@withContext when (error) {
                    is SecurityException -> IngestionResult.Failure("SecurityException")
                    is FileNotFoundException -> IngestionResult.Failure("FileNotFoundException")
                    else -> IngestionResult.Failure("OpenStreamError")
                }
            }

            val bytes = try {
                stream.use { input ->
                    readBoundedStream(input, maxBytes)
                }
            } catch (e: Exception) {
                AppLog.warn(TAG, "Error reading shared image stream: ${e.javaClass.simpleName}")
                null
            } ?: return@withContext IngestionResult.Failure("Stream empty or exceeded limit")

            IngestionResult.Success(bytes = bytes, mimeType = effectiveMime)
        }

    private companion object {
        const val TAG = "ExternalImageIngestor"
    }
}
