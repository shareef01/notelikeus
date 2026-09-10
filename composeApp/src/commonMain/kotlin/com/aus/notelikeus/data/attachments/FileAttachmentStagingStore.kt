package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.util.AppLog
import com.aus.notelikeus.util.DateUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * [AttachmentStagingStore] backed by the real filesystem, shared by Android and Windows.
 *
 * Layout, rooted at [root]:
 * ```
 * <root>/<ownerId>/<attachmentId>.bin    staged bytes
 * <root>/<ownerId>/<attachmentId>.json   staged metadata
 * ```
 * The owner segment is what keeps one account's staged bytes out of another's; it is sanitised
 * because it reaches the store as a Supabase user id and must never be able to escape the root.
 *
 * Bytes are written to a temporary sibling and then atomically moved into place, so a process
 * death mid-write leaves either the previous state or the complete new file — never a truncated
 * image that would later upload as corrupt.
 */
class FileAttachmentStagingStore(
    private val root: Path,
    private val ioDispatcher: CoroutineDispatcher,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val now: () -> Long = { DateUtils.currentTimeMillis() },
    private val protector: AttachmentBytesProtector = NoopAttachmentBytesProtector,
) : AttachmentStagingStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun stage(
        attachmentId: String,
        ownerId: String,
        noteId: Long?,
        bytes: ByteArray,
        mimeType: String,
    ): StagedAttachment? = withContext(ioDispatcher) {
        val id = safeSegment(attachmentId) ?: return@withContext null
        val owner = ownerDir(ownerId) ?: return@withContext null
        val staged = StagedAttachment(
            attachmentId = attachmentId,
            ownerId = ownerId,
            noteId = noteId,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            createdAt = now(),
        )
        try {
            fileSystem.createDirectories(owner)
            val target = owner / "$id$BYTES_SUFFIX"
            val temp = owner / "$id$TEMP_SUFFIX"
            val aad = stagingAad(ownerId = ownerId, attachmentId = attachmentId)
            val sealed = protector.seal(bytes, aad)
            fileSystem.write(temp) { write(sealed) }
            fileSystem.atomicMove(temp, target)
            writeMetadata(owner, id, staged)
            staged
        } catch (error: okio.IOException) {
            // The reference must not be added to the note when the bytes did not land.
            AppLog.warn(TAG, "Staging attachment bytes failed", error)
            runCatching { fileSystem.delete(owner / "$id$TEMP_SUFFIX") }
            null
        }
    }

    override suspend fun readBytes(attachmentId: String, ownerId: String): ByteArray? =
        withContext(ioDispatcher) {
            val id = safeSegment(attachmentId) ?: return@withContext null
            val owner = ownerDir(ownerId) ?: return@withContext null
            val target = owner / "$id$BYTES_SUFFIX"
            try {
                if (!fileSystem.exists(target)) return@withContext null
                val raw = fileSystem.read(target) { readByteArray() }
                val aad = stagingAad(ownerId = ownerId, attachmentId = attachmentId)
                protector.open(raw, aad)
            } catch (error: okio.IOException) {
                AppLog.warn(TAG, "Reading staged attachment bytes failed", error)
                null
            }
        }

    override suspend fun isStaged(attachmentId: String, ownerId: String): Boolean? =
        withContext(ioDispatcher) {
            val id = safeSegment(attachmentId) ?: return@withContext false
            val owner = ownerDir(ownerId) ?: return@withContext false
            try {
                fileSystem.exists(owner / "$id$BYTES_SUFFIX")
            } catch (error: okio.IOException) {
                // Could not look. Not the same as "not there", and the caller may be deciding
                // whether to discard a picture, so say so rather than guessing.
                AppLog.warn(TAG, "Could not determine whether staged bytes exist", error)
                null
            }
        }

    override suspend fun metadata(attachmentId: String, ownerId: String): StagedAttachment? =
        withContext(ioDispatcher) {
            val id = safeSegment(attachmentId) ?: return@withContext null
            val owner = ownerDir(ownerId) ?: return@withContext null
            readMetadata(owner, id)
        }

    override suspend fun bindNote(attachmentId: String, ownerId: String, noteId: Long) {
        withContext(ioDispatcher) {
            val id = safeSegment(attachmentId) ?: return@withContext
            val owner = ownerDir(ownerId) ?: return@withContext
            val existing = readMetadata(owner, id) ?: return@withContext
            if (existing.noteId == noteId) return@withContext
            runCatching { writeMetadata(owner, id, existing.copy(noteId = noteId)) }
        }
    }

    override suspend fun release(attachmentId: String, ownerId: String) {
        withContext(ioDispatcher) {
            val id = safeSegment(attachmentId) ?: return@withContext
            val owner = ownerDir(ownerId) ?: return@withContext
            runCatching { fileSystem.delete(owner / "$id$BYTES_SUFFIX") }
            runCatching { fileSystem.delete(owner / "$id$METADATA_SUFFIX") }
        }
    }

    override suspend fun list(ownerId: String): List<StagedAttachment> = withContext(ioDispatcher) {
        val owner = ownerDir(ownerId) ?: return@withContext emptyList()
        try {
            if (!fileSystem.exists(owner)) return@withContext emptyList()
            fileSystem.list(owner)
                .filter { it.name.endsWith(METADATA_SUFFIX) }
                .mapNotNull { path ->
                    val id = path.name.removeSuffix(METADATA_SUFFIX)
                    // Metadata without bytes is not usable staging; reconciliation reports those
                    // through the note, not through this list.
                    readMetadata(owner, id)?.takeIf { fileSystem.exists(owner / "$id$BYTES_SUFFIX") }
                }
        } catch (error: okio.IOException) {
            AppLog.warn(TAG, "Listing staged attachments failed", error)
            emptyList()
        }
    }

    private fun writeMetadata(owner: Path, id: String, staged: StagedAttachment) {
        fileSystem.write(owner / "$id$METADATA_SUFFIX") {
            writeUtf8(json.encodeToString(StagedAttachment.serializer(), staged))
        }
    }

    private fun readMetadata(owner: Path, id: String): StagedAttachment? {
        val target = owner / "$id$METADATA_SUFFIX"
        return try {
            if (!fileSystem.exists(target)) return null
            val text = fileSystem.read(target) { readUtf8() }
            json.decodeFromString(StagedAttachment.serializer(), text)
        } catch (_: okio.IOException) {
            null
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        }
    }

    private fun ownerDir(ownerId: String): Path? = safeSegment(ownerId)?.let { root / it }

    private fun stagingAad(ownerId: String, attachmentId: String): ByteArray =
        "$ownerId/$attachmentId".toByteArray()

    /**
     * Owner and attachment ids arrive from Supabase and from note rows. Anything that is not a
     * plain id is rejected outright rather than sanitised into a different-but-valid path, so a
     * crafted value can neither traverse out of [root] nor collide with another owner's directory.
     */
    private fun safeSegment(value: String): String? {
        if (value.isEmpty() || value.length > MAX_SEGMENT) return null
        if (!value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return value
    }

    private companion object {
        const val TAG = "AttachmentStaging"
        const val BYTES_SUFFIX = ".bin"
        const val METADATA_SUFFIX = ".json"
        const val TEMP_SUFFIX = ".tmp"
        const val MAX_SEGMENT = 128
    }
}
