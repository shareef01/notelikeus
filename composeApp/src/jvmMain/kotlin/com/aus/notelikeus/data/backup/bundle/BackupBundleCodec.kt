package com.aus.notelikeus.data.backup.bundle

import com.aus.notelikeus.data.backup.BackupBundleManifest
import com.aus.notelikeus.data.backup.BundleAttachmentDto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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

    fun buildBackupBundle(
        backupDocument: JsonObject,
        attachments: List<BundleAttachmentSource>,
        app: String = "Notelikeus",
        appVersion: String = "1.0.0",
        exportedAt: Long = System.currentTimeMillis(),
    ): ByteArray {
        if (attachments.size > BackupBundleLimits.MAX_BUNDLE_ATTACHMENTS) {
            throw BundleFormatException(
                "Too many attachments for one bundle (max ${BackupBundleLimits.MAX_BUNDLE_ATTACHMENTS})",
            )
        }

        val index = ArrayList<BundleAttachmentDto>()
        val media = ArrayList<Pair<String, ByteArray>>()
        val seen = HashSet<String>()

        for (source in attachments) {
            if (!BackupBundleLimits.ATTACHMENT_ID_PATTERN.matches(source.attachmentId)) continue
            if (!seen.add(source.attachmentId)) continue
            if (source.bytes.size.toLong() > BackupBundleLimits.MAX_BUNDLE_ATTACHMENT_BYTES) continue

            val name = mediaEntryName(source.attachmentId)
            index.add(
                BundleAttachmentDto(
                    noteId = source.noteId,
                    attachmentId = source.attachmentId,
                    path = name,
                    type = source.type.ifBlank { "image" },
                    mimeType = source.mimeType,
                    sizeBytes = source.bytes.size.toLong(),
                    sha256 = sha256Hex(source.bytes),
                ),
            )
            media.add(name to source.bytes)
        }

        val manifest = buildJsonObject {
            put("formatVersion", BackupBundleManifest.BUNDLE_FORMAT_VERSION)
            put("app", app)
            put("appVersion", appVersion)
            put("exportedAt", exportedAt)
            put("backup", backupDocument)
            putJsonArray("attachments") {
                for (entry in index) {
                    add(
                        buildJsonObject {
                            put("noteId", entry.noteId ?: 0L)
                            put("attachmentId", entry.attachmentId)
                            put("path", entry.path)
                            put("type", entry.type)
                            entry.mimeType?.let { put("mimeType", it) }
                            entry.sizeBytes?.let { put("sizeBytes", it) }
                            entry.sha256?.let { put("sha256", it) }
                        },
                    )
                }
            }
        }

        val manifestBytes = json.encodeToString(JsonObject.serializer(), manifest)
            .toByteArray(Charsets.UTF_8)

        return writeStoredZip(
            listOf(BackupBundleLimits.BUNDLE_MANIFEST_ENTRY to manifestBytes) + media,
        )
    }

    fun parseBackupBundle(archive: ByteArray): ParsedBackupBundle {
        if (archive.size.toLong() > BackupBundleLimits.MAX_BUNDLE_FILE_BYTES) {
            throw BundleFormatException("Backup bundle is too large")
        }

        val entries = readAllStoredOrDeflatedEntries(archive)
        val byName = entries.associateBy { it.first }
        if (byName.size > BackupBundleLimits.MAX_ZIP_ENTRIES) {
            throw BundleFormatException("Backup bundle has too many entries")
        }

        val manifestBytes = byName[BackupBundleLimits.BUNDLE_MANIFEST_ENTRY]?.second
            ?: throw BundleFormatException("Backup bundle has no manifest.json")
        if (manifestBytes.size.toLong() > BackupBundleLimits.MAX_BUNDLE_MANIFEST_BYTES) {
            throw BundleFormatException("Backup bundle manifest is too large")
        }

        val manifestText = manifestBytes.toString(Charsets.UTF_8)
        if (maxJsonNestingDepth(manifestText) > MAX_JSON_DEPTH) {
            throw BundleFormatException("Backup bundle manifest is too deeply nested")
        }

        val root = try {
            json.parseToJsonElement(manifestText).jsonObject
        } catch (_: Exception) {
            throw BundleFormatException("Backup bundle manifest is not valid JSON")
        }

        val formatVersion = root["formatVersion"]?.jsonPrimitive?.intOrNull ?: 0
        if (formatVersion > BackupBundleManifest.BUNDLE_FORMAT_VERSION) {
            throw BundleFormatException("Unsupported bundle version: $formatVersion")
        }
        val backup = root["backup"]?.jsonObject
            ?: throw BundleFormatException("Backup bundle manifest carries no backup document")

        val warnings = ArrayList<String>()
        var droppedAttachments = 0
        val media = LinkedHashMap<String, ParsedMediaAttachment>()
        val attachments = ArrayList<BundleAttachmentDto>()
        val referencedNames = hashSetOf(BackupBundleLimits.BUNDLE_MANIFEST_ENTRY)

        val rawAttachments = root["attachments"]?.jsonArray.orEmpty()
        if (rawAttachments.size > BackupBundleLimits.MAX_BUNDLE_ATTACHMENTS) {
            throw BundleFormatException(
                "Bundle lists more attachments than this app will read (max ${BackupBundleLimits.MAX_BUNDLE_ATTACHMENTS})",
            )
        }

        for (candidate in rawAttachments) {
            val entry = candidate.jsonObjectOrNull() ?: continue
            val attachmentId = entry["attachmentId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!BackupBundleLimits.ATTACHMENT_ID_PATTERN.matches(attachmentId)) {
                droppedAttachments++
                warnings.add("Skipped an attachment with an unusable id")
                continue
            }
            if (media.containsKey(attachmentId)) continue

            val mediaName = mediaEntryName(attachmentId)
            referencedNames.add(mediaName)
            val mediaBytes = byName[mediaName]?.second
            if (mediaBytes == null) {
                droppedAttachments++
                warnings.add("Attachment $attachmentId is listed but its file is missing")
                continue
            }
            if (mediaBytes.size.toLong() > BackupBundleLimits.MAX_BUNDLE_ATTACHMENT_BYTES) {
                droppedAttachments++
                warnings.add("Attachment $attachmentId is too large to import")
                continue
            }

            val declaredSize = entry["sizeBytes"]?.jsonPrimitive?.longOrNull ?: mediaBytes.size.toLong()
            if (declaredSize != mediaBytes.size.toLong()) {
                droppedAttachments++
                warnings.add("Attachment $attachmentId does not match its recorded size")
                continue
            }

            val declaredHash = entry["sha256"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
            if (declaredHash.isNotEmpty()) {
                val actual = sha256Hex(mediaBytes)
                if (actual != declaredHash) {
                    droppedAttachments++
                    warnings.add("Attachment $attachmentId failed its checksum and was skipped")
                    continue
                }
            }

            val type = entry["type"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "image"
            val mimeType = entry["mimeType"]?.jsonPrimitive?.contentOrNull
            val noteId = entry["noteId"]?.jsonPrimitive?.longOrNull ?: 0L
            media[attachmentId] = ParsedMediaAttachment(
                bytes = mediaBytes,
                mimeType = mimeType,
                type = type,
                noteId = noteId,
            )
            attachments.add(
                BundleAttachmentDto(
                    noteId = noteId,
                    attachmentId = attachmentId,
                    path = mediaName,
                    type = type,
                    mimeType = mimeType,
                    sizeBytes = mediaBytes.size.toLong(),
                    sha256 = declaredHash.ifEmpty { null },
                ),
            )
        }

        val strayMedia = byName.keys.count {
            it.startsWith(BackupBundleLimits.BUNDLE_MEDIA_PREFIX) && it !in referencedNames
        }
        if (strayMedia > 0) {
            warnings.add("$strayMedia file(s) in the bundle are not listed in its manifest")
        }

        return ParsedBackupBundle(
            manifest = BackupBundleManifest(
                formatVersion = formatVersion.takeIf { it > 0 }
                    ?: BackupBundleManifest.BUNDLE_FORMAT_VERSION,
                app = root["app"]?.jsonPrimitive?.contentOrNull ?: "Notelikeus",
                appVersion = root["appVersion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                exportedAt = root["exportedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                backup = backup,
                attachments = attachments,
            ),
            media = media,
            droppedAttachments = droppedAttachments,
            warnings = warnings,
        )
    }

    private fun writeStoredZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.setMethod(ZipEntry.STORED)
            for ((name, data) in entries) {
                require(isSafeZipEntryName(name)) { "Unsafe ZIP entry name: $name" }
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = data.size.toLong()
                entry.compressedSize = data.size.toLong()
                val crc = CRC32()
                crc.update(data)
                entry.crc = crc.value
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun readAllStoredOrDeflatedEntries(archive: ByteArray): List<Pair<String, ByteArray>> {
        val result = ArrayList<Pair<String, ByteArray>>()
        var totalUncompressed = 0L
        ZipInputStream(ByteArrayInputStream(archive)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                if (!isSafeZipEntryName(entry.name)) {
                    throw BundleFormatException("Backup bundle contains an unsafe entry name")
                }
                if (result.size >= BackupBundleLimits.MAX_ZIP_ENTRIES) {
                    throw BundleFormatException("Backup bundle has too many entries")
                }
                val bytes = readEntryBytes(zis, entry)
                totalUncompressed += bytes.size.toLong()
                if (totalUncompressed > BackupBundleLimits.MAX_ZIP_TOTAL_BYTES) {
                    throw BundleFormatException("Backup bundle expands beyond the allowed size")
                }
                result.add(entry.name to bytes)
            }
        }
        return result
    }

    private fun readEntryBytes(zis: ZipInputStream, entry: ZipEntry): ByteArray {
        val declared = entry.size
        if (declared >= 0 && declared > BackupBundleLimits.MAX_ZIP_ENTRY_BYTES) {
            throw BundleFormatException("Backup bundle entry is too large")
        }
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = zis.read(chunk)
            if (read < 0) break
            total += read
            if (total > BackupBundleLimits.MAX_ZIP_ENTRY_BYTES) {
                throw BundleFormatException("Backup bundle entry is too large")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
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
