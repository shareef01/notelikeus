package com.aus.notelikeus.data.backup.bundle

import com.aus.notelikeus.data.backup.BackupBundleManifest
import com.aus.notelikeus.data.backup.BundleAttachmentDto
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class BundleFormatException(message: String) : Exception(message)

data class BundleAttachmentSource(
    val noteId: Long,
    val attachmentId: String,
    val type: String = "image",
    val mimeType: String? = null,
    val bytes: ByteArray,
)

/** One image for [BackupBundleCodec.writeBackupBundle], read on demand so the writer holds one at a time. */
class BundleAttachmentEntry(
    val noteId: Long,
    val attachmentId: String,
    val type: String = "image",
    val mimeType: String? = null,
    /** The image's bytes, or null when they are not available on this device. May be called twice. */
    val read: suspend () -> ByteArray?,
)

/** Who wrote a bundle, and when: the manifest's app, appVersion and exportedAt. */
data class BundleOrigin(
    val app: String = "Notelikeus",
    val appVersion: String = "1.0.0",
    val exportedAt: Long = System.currentTimeMillis(),
)

/** What [BackupBundleCodec.writeBackupBundle] wrote: images included, and images it could not read. */
data class BundleWriteResult(val included: Int, val unreadable: Int)

data class ParsedMediaAttachment(
    val bytes: ByteArray,
    val mimeType: String?,
    val type: String,
    val noteId: Long,
)

data class ParsedBackupBundle(
    val manifest: BackupBundleManifest,
    val media: Map<String, ParsedMediaAttachment>,
    val droppedAttachments: Int,
    val warnings: List<String>,
)

/**
 * Builds and parses `.nlkbak` archives for Android/Desktop.
 *
 * Matches `web/src/lib/backup/bundle/backupBundle.ts`: manifest-first, media addressed by
 * validated attachment id (`media/<id>`), never by an archive-supplied path. Attachment failures
 * are dropped with warnings; a bad manifest fails the whole parse.
 */
object BackupBundleCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    private const val MAX_JSON_DEPTH = 64

    fun looksLikeBundle(fileName: String?, head: ByteArray): Boolean {
        if (head.size >= 2 && head[0] == 0x50.toByte() && head[1] == 0x4b.toByte()) return true
        return (fileName ?: "").lowercase().endsWith(BackupBundleLimits.BUNDLE_FILE_EXTENSION)
    }

    fun mediaEntryName(attachmentId: String): String =
        BackupBundleLimits.BUNDLE_MEDIA_PREFIX + attachmentId

    /**
     * Builds a whole bundle in memory. Kept for callers that already hold every image (tests, small
     * archives); exports go through [writeBackupBundle], which never holds more than one image.
     */
    fun buildBackupBundle(
        backupDocument: JsonObject,
        attachments: List<BundleAttachmentSource>,
        app: String = "Notelikeus",
        appVersion: String = "1.0.0",
        exportedAt: Long = System.currentTimeMillis(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // Every read below returns bytes already in memory, so nothing actually suspends.
        runBlocking {
            writeBackupBundle(
                output = out,
                backupDocument = backupDocument,
                attachments = attachments.map { source ->
                    BundleAttachmentEntry(
                        noteId = source.noteId,
                        attachmentId = source.attachmentId,
                        type = source.type,
                        mimeType = source.mimeType,
                    ) { source.bytes }
                },
                origin = BundleOrigin(app = app, appVersion = appVersion, exportedAt = exportedAt),
            )
        }
        return out.toByteArray()
    }

    /**
     * Streams a bundle to [output], holding at most one image at a time (F-07), and closes it.
     *
     * The manifest is the archive's first entry and carries each image's size and SHA-256, and a
     * stored ZIP entry needs its CRC before its data. So images are read twice: once to measure them,
     * and once, after the manifest, to write them. The second read is checked against the first; an
     * image that changed in between fails the export rather than producing a bundle whose manifest
     * does not match its contents. The archive is byte-for-byte what [buildBackupBundle] produced
     * before streaming existed.
     */
    suspend fun writeBackupBundle(
        output: OutputStream,
        backupDocument: JsonObject,
        attachments: List<BundleAttachmentEntry>,
        origin: BundleOrigin = BundleOrigin(),
    ): BundleWriteResult = output.use { stream ->
        if (attachments.size > BackupBundleLimits.MAX_BUNDLE_ATTACHMENTS) {
            throw BundleFormatException(
                "Too many attachments for one bundle (max ${BackupBundleLimits.MAX_BUNDLE_ATTACHMENTS})",
            )
        }
        val measured = measureAttachments(attachments)
        writeArchive(stream, manifestBytes(backupDocument, measured.images, origin), measured.images)
        BundleWriteResult(included = measured.images.size, unreadable = measured.unreadable)
    }

    private class MeasuredImage(val source: BundleAttachmentEntry, val size: Long, val sha256: String, val crc: Long)

    private class MeasuredImages(val images: List<MeasuredImage>, val unreadable: Int)

    /** Pass one: the size, SHA-256 and CRC of every image that will go into the bundle. */
    private suspend fun measureAttachments(attachments: List<BundleAttachmentEntry>): MeasuredImages {
        val measured = ArrayList<MeasuredImage>()
        val seen = HashSet<String>()
        var unreadable = 0
        for (source in attachments) {
            if (!BackupBundleLimits.ATTACHMENT_ID_PATTERN.matches(source.attachmentId)) continue
            if (!seen.add(source.attachmentId)) continue
            val bytes = source.read()
            if (bytes == null) {
                unreadable++
                continue
            }
            if (bytes.size.toLong() > BackupBundleLimits.MAX_BUNDLE_ATTACHMENT_BYTES) continue
            measured.add(MeasuredImage(source, bytes.size.toLong(), sha256Hex(bytes), crc32(bytes)))
        }
        return MeasuredImages(measured, unreadable)
    }

    private fun manifestBytes(
        backupDocument: JsonObject,
        images: List<MeasuredImage>,
        origin: BundleOrigin,
    ): ByteArray {
        val manifest = buildJsonObject {
            put("formatVersion", BackupBundleManifest.BUNDLE_FORMAT_VERSION)
            put("app", origin.app)
            put("appVersion", origin.appVersion)
            put("exportedAt", origin.exportedAt)
            put("backup", backupDocument)
            putJsonArray("attachments") {
                for (image in images) {
                    add(
                        buildJsonObject {
                            put("noteId", image.source.noteId)
                            put("attachmentId", image.source.attachmentId)
                            put("path", mediaEntryName(image.source.attachmentId))
                            put("type", image.source.type.ifBlank { "image" })
                            image.source.mimeType?.let { put("mimeType", it) }
                            put("sizeBytes", image.size)
                            put("sha256", image.sha256)
                        },
                    )
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), manifest).toByteArray(Charsets.UTF_8)
    }

    /** Pass two: the manifest, then each image read again and checked against pass one. */
    private suspend fun writeArchive(stream: OutputStream, manifest: ByteArray, images: List<MeasuredImage>) {
        ZipOutputStream(stream).use { zos ->
            zos.setMethod(ZipEntry.STORED)
            putStoredEntry(zos, BackupBundleLimits.BUNDLE_MANIFEST_ENTRY, manifest, crc32(manifest))
            for (image in images) {
                val id = image.source.attachmentId
                val bytes = image.source.read()
                    ?: throw IOException("Attachment $id could not be read a second time")
                if (bytes.size.toLong() != image.size || sha256Hex(bytes) != image.sha256) {
                    throw IOException("Attachment $id changed while the bundle was being written")
                }
                putStoredEntry(zos, mediaEntryName(id), bytes, image.crc)
            }
        }
    }

    private fun putStoredEntry(zos: ZipOutputStream, name: String, data: ByteArray, crc: Long) {
        require(isSafeZipEntryName(name)) { "Unsafe ZIP entry name: $name" }
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = data.size.toLong()
        entry.compressedSize = data.size.toLong()
        entry.crc = crc
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value

    /**
     * Reads a whole bundle held in memory. Kept for callers that already have the bytes (tests,
     * small archives); imports go through [openBackupBundle], which never holds more than one image.
     */
    fun parseBackupBundle(archive: ByteArray): ParsedBackupBundle {
        if (archive.size.toLong() > BackupBundleLimits.MAX_BUNDLE_FILE_BYTES) {
            throw BundleFormatException("Backup bundle is too large")
        }
        // NIO's temp file is owner-only (0600 on POSIX), unlike File.createTempFile: it holds the
        // user's notes and images, and the system temp directory is shared with other local users.
        val file = java.nio.file.Files
            .createTempFile("nlkbak-parse", BackupBundleLimits.BUNDLE_FILE_EXTENSION)
            .toFile()
        try {
            file.writeBytes(archive)
            return openBackupBundle(file).use(::readEveryImage)
        } finally {
            file.delete()
        }
    }

    /** Every image of [bundle], read and verified, in the shape [parseBackupBundle] returns. */
    private fun readEveryImage(bundle: OpenedBackupBundle): ParsedBackupBundle {
        val media = LinkedHashMap<String, ParsedMediaAttachment>()
        val attachments = ArrayList<BundleAttachmentDto>()
        val warnings = bundle.warnings.toMutableList()
        var dropped = bundle.droppedAttachments
        for (entry in bundle.manifest.attachments) {
            when (val read = bundle.readMedia(entry)) {
                is MediaRead.Dropped -> {
                    dropped++
                    warnings.add(read.warning)
                }
                is MediaRead.Loaded -> {
                    media[entry.attachmentId] = read.media
                    attachments.add(entry.copy(sizeBytes = read.media.bytes.size.toLong()))
                }
            }
        }
        return ParsedBackupBundle(
            manifest = bundle.manifest.copy(attachments = attachments),
            media = media,
            droppedAttachments = dropped,
            warnings = warnings,
        )
    }

    /**
     * Opens the bundle at [file] for a streaming import.
     *
     * Everything that decides whether the bundle is usable at all is checked here, before a single
     * note is imported: the file size, every entry name, the entry count, the declared uncompressed
     * total, and the manifest. Images are only *indexed*; [OpenedBackupBundle.readMedia] reads one at
     * a time, so heap use is bounded by the manifest plus one image, whatever the bundle's size.
     */
    fun openBackupBundle(file: File): OpenedBackupBundle {
        if (file.length() > BackupBundleLimits.MAX_BUNDLE_FILE_BYTES) {
            throw BundleFormatException("Backup bundle is too large")
        }
        val zip = try {
            ZipFile(file)
        } catch (_: ZipException) {
            throw BundleFormatException("Backup bundle is not a valid archive")
        }
        try {
            return indexBundle(zip)
        } catch (error: Throwable) {
            zip.close()
            throw error
        }
    }

    private fun indexBundle(zip: ZipFile): OpenedBackupBundle {
        val byName = indexEntries(zip)
        val manifest = readManifest(zip, byName)
        val root = manifest.root

        val formatVersion = root["formatVersion"]?.jsonPrimitive?.intOrNull ?: 0
        if (formatVersion > BackupBundleManifest.BUNDLE_FORMAT_VERSION) {
            throw BundleFormatException("Unsupported bundle version: $formatVersion")
        }
        val backup = root["backup"]?.jsonObject
            ?: throw BundleFormatException("Backup bundle manifest carries no backup document")

        val index = indexAttachments(root, byName)

        return OpenedBackupBundle(
            zip = zip,
            entries = byName,
            manifest = BackupBundleManifest(
                formatVersion = formatVersion.takeIf { it > 0 }
                    ?: BackupBundleManifest.BUNDLE_FORMAT_VERSION,
                app = root["app"]?.jsonPrimitive?.contentOrNull ?: "Notelikeus",
                appVersion = root["appVersion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                exportedAt = root["exportedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                backup = backup,
                attachments = index.attachments,
            ),
            droppedAttachments = index.dropped,
            warnings = index.warnings,
            initialBytesRead = manifest.sizeBytes,
        )
    }

    /** Every file entry by name, refusing the archive on any unsafe name or size limit. */
    private fun indexEntries(zip: ZipFile): LinkedHashMap<String, ZipEntry> {
        val byName = LinkedHashMap<String, ZipEntry>()
        var declaredTotal = 0L
        for (entry in zip.entries()) {
            if (entry.isDirectory) continue
            if (!isSafeZipEntryName(entry.name)) {
                throw BundleFormatException("Backup bundle contains an unsafe entry name")
            }
            if (byName.size >= BackupBundleLimits.MAX_ZIP_ENTRIES) {
                throw BundleFormatException("Backup bundle has too many entries")
            }
            if (entry.size > BackupBundleLimits.MAX_ZIP_ENTRY_BYTES) {
                throw BundleFormatException("Backup bundle entry is too large")
            }
            declaredTotal += entry.size.coerceAtLeast(0L)
            if (declaredTotal > BackupBundleLimits.MAX_ZIP_TOTAL_BYTES) {
                throw BundleFormatException("Backup bundle expands beyond the allowed size")
            }
            byName[entry.name] = entry
        }
        return byName
    }

    private class ManifestRoot(val root: JsonObject, val sizeBytes: Long)

    private fun readManifest(zip: ZipFile, byName: Map<String, ZipEntry>): ManifestRoot {
        val manifestEntry = byName[BackupBundleLimits.BUNDLE_MANIFEST_ENTRY]
            ?: throw BundleFormatException("Backup bundle has no manifest.json")
        val manifestBytes = readEntryBytes(zip, manifestEntry, BackupBundleLimits.MAX_BUNDLE_MANIFEST_BYTES)
            ?: throw BundleFormatException("Backup bundle manifest is too large")

        val manifestText = manifestBytes.toString(Charsets.UTF_8)
        if (maxJsonNestingDepth(manifestText) > MAX_JSON_DEPTH) {
            throw BundleFormatException("Backup bundle manifest is too deeply nested")
        }
        val root = try {
            json.parseToJsonElement(manifestText).jsonObject
        } catch (_: Exception) {
            throw BundleFormatException("Backup bundle manifest is not valid JSON")
        }
        return ManifestRoot(root, manifestBytes.size.toLong())
    }

    private class AttachmentIndex(
        val attachments: List<BundleAttachmentDto>,
        val dropped: Int,
        val warnings: List<String>,
    )

    /**
     * The manifest's attachments that pass every check not needing their bytes, plus a warning for
     * each one dropped and for media files the manifest does not list.
     */
    private fun indexAttachments(root: JsonObject, byName: Map<String, ZipEntry>): AttachmentIndex {
        val rawAttachments = root["attachments"]?.jsonArray.orEmpty()
        if (rawAttachments.size > BackupBundleLimits.MAX_BUNDLE_ATTACHMENTS) {
            throw BundleFormatException(
                "Bundle lists more attachments than this app will read (max ${BackupBundleLimits.MAX_BUNDLE_ATTACHMENTS})",
            )
        }

        val warnings = ArrayList<String>()
        val seen = HashSet<String>()
        val attachments = ArrayList<BundleAttachmentDto>()
        val referencedNames = hashSetOf(BackupBundleLimits.BUNDLE_MANIFEST_ENTRY)
        for (candidate in rawAttachments) {
            val entry = candidate.jsonObjectOrNull() ?: continue
            val attachmentId = entry["attachmentId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val validId = BackupBundleLimits.ATTACHMENT_ID_PATTERN.matches(attachmentId)
            // A repeated id is skipped silently; an unusable one is refused below, with a warning.
            if (validId && !seen.add(attachmentId)) continue
            val mediaName = mediaEntryName(attachmentId)
            if (validId) referencedNames.add(mediaName)
            val refusal = attachmentRefusal(attachmentId, byName[mediaName])
            if (refusal != null) {
                warnings.add(refusal)
                continue
            }
            attachments.add(attachmentDto(entry, attachmentId, mediaName))
        }

        val strayMedia = byName.keys.count {
            it.startsWith(BackupBundleLimits.BUNDLE_MEDIA_PREFIX) && it !in referencedNames
        }
        val dropped = warnings.size
        if (strayMedia > 0) {
            warnings.add("$strayMedia file(s) in the bundle are not listed in its manifest")
        }
        return AttachmentIndex(attachments, dropped, warnings)
    }

    /** Why a listed attachment cannot be imported, or null when it can. */
    private fun attachmentRefusal(attachmentId: String, zipEntry: ZipEntry?): String? = when {
        !BackupBundleLimits.ATTACHMENT_ID_PATTERN.matches(attachmentId) ->
            "Skipped an attachment with an unusable id"
        zipEntry == null -> "Attachment $attachmentId is listed but its file is missing"
        zipEntry.size > BackupBundleLimits.MAX_BUNDLE_ATTACHMENT_BYTES ->
            "Attachment $attachmentId is too large to import"
        else -> null
    }

    private fun attachmentDto(entry: JsonObject, attachmentId: String, mediaName: String) =
        BundleAttachmentDto(
            noteId = entry["noteId"]?.jsonPrimitive?.longOrNull ?: 0L,
            attachmentId = attachmentId,
            path = mediaName,
            type = entry["type"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "image",
            mimeType = entry["mimeType"]?.jsonPrimitive?.contentOrNull,
            sizeBytes = entry["sizeBytes"]?.jsonPrimitive?.longOrNull,
            sha256 = entry["sha256"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase().ifEmpty { null },
        )

    /**
     * The bytes of [entry], or null when more than [limit] arrive. The limit is enforced on what is
     * actually read, never on the size the archive declares.
     */
    internal fun readEntryBytes(zip: ZipFile, entry: ZipEntry, limit: Long): ByteArray? {
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArrayOutputStream(
                entry.size.takeIf { it in 0..limit }?.toInt() ?: DEFAULT_BUFFER_SIZE,
            )
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > limit) return null
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        }
    }

    fun isSafeZipEntryName(name: String): Boolean {
        if (name.isEmpty() || name.length > 255) return false
        if (name.any { it.code <= 0x1f || it.code == 0x7f || it == '\\' || it == ':' }) return false
        if (name.startsWith('/')) return false
        return name.split('/').all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".."
        }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Iterative raw-string nesting scan — same rules as [NoteBackupImporter] / web jsonNesting.
     */
    fun maxJsonNestingDepth(jsonStr: String): Int {
        var depth = 0
        var max = 0
        var inString = false
        var escaped = false
        for (ch in jsonStr) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > max) max = depth
                }
                '}', ']' -> depth = (depth - 1).coerceAtLeast(0)
            }
        }
        return max
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()
}
