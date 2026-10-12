package com.aus.notelikeus.data.backup.bundle

import com.aus.notelikeus.data.backup.BackupBundleManifest
import com.aus.notelikeus.data.backup.BundleAttachmentDto
import java.io.Closeable
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** What reading one image out of an [OpenedBackupBundle] produced. */
sealed interface MediaRead {
    data class Loaded(val media: ParsedMediaAttachment) : MediaRead

    /** The image is skipped; the rest of the import goes on. [warning] is shown to the user. */
    data class Dropped(val warning: String) : MediaRead
}

/**
 * A bundle opened by [BackupBundleCodec.openBackupBundle]: the validated manifest, plus random access
 * to its images.
 *
 * [manifest]'s attachments are the ones that passed every check that does not need their bytes.
 * Size and checksum are verified by [readMedia], when each image is actually read.
 */
class OpenedBackupBundle internal constructor(
    private val zip: ZipFile,
    private val entries: Map<String, ZipEntry>,
    val manifest: BackupBundleManifest,
    val droppedAttachments: Int,
    val warnings: List<String>,
    initialBytesRead: Long,
) : Closeable {

    /** Uncompressed bytes read so far, against [BackupBundleLimits.MAX_ZIP_TOTAL_BYTES]. */
    private var bytesRead = initialBytesRead

    fun readMedia(attachment: BundleAttachmentDto): MediaRead {
        val id = attachment.attachmentId
        val entry = entries[attachment.path]
            ?: return MediaRead.Dropped("Attachment $id is listed but its file is missing")
        val bytes = BackupBundleCodec.readEntryBytes(
            zip,
            entry,
            BackupBundleLimits.MAX_BUNDLE_ATTACHMENT_BYTES,
        ) ?: return MediaRead.Dropped("Attachment $id is too large to import")

        bytesRead += bytes.size
        if (bytesRead > BackupBundleLimits.MAX_ZIP_TOTAL_BYTES) {
            return MediaRead.Dropped("Attachment $id was skipped: the bundle expands beyond the allowed size")
        }
        val declaredSize = attachment.sizeBytes ?: bytes.size.toLong()
        if (declaredSize != bytes.size.toLong()) {
            return MediaRead.Dropped("Attachment $id does not match its recorded size")
        }
        val declaredHash = attachment.sha256.orEmpty()
        if (declaredHash.isNotEmpty() && BackupBundleCodec.sha256Hex(bytes) != declaredHash) {
            return MediaRead.Dropped("Attachment $id failed its checksum and was skipped")
        }
        return MediaRead.Loaded(
            ParsedMediaAttachment(
                bytes = bytes,
                mimeType = attachment.mimeType,
                type = attachment.type,
                noteId = attachment.noteId ?: 0L,
            ),
        )
    }

    override fun close() = zip.close()
}
