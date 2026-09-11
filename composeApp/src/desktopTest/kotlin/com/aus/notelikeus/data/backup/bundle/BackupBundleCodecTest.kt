package com.aus.notelikeus.data.backup.bundle

import com.aus.notelikeus.data.backup.BackupBundleManifest
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test

class BackupBundleCodecTest {

    private val backupDocument: JsonObject = buildJsonObject {
        put("version", 3)
        put("app", "Notelikeus")
        put("appVersion", "test")
        put("exportedAt", 1L)
        putJsonArray("labels") {}
        putJsonArray("notes") {
            add(
                buildJsonObject {
                    put("id", 1L)
                    put("title", "Hello")
                    put("content", "World")
                    put("timestamp", 1L)
                    put("color", 0)
                },
            )
        }
    }

    @Test
    fun roundTripsManifestAndMedia() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
        val archive = BackupBundleCodec.buildBackupBundle(
            backupDocument = backupDocument,
            attachments = listOf(
                BundleAttachmentSource(
                    noteId = 1L,
                    attachmentId = "att-1",
                    mimeType = "image/png",
                    bytes = bytes,
                ),
            ),
            appVersion = "test",
            exportedAt = 42L,
        )

        assertTrue(BackupBundleCodec.looksLikeBundle("x.nlkbak", archive.copyOf(2)))
        val parsed = BackupBundleCodec.parseBackupBundle(archive)
        assertEquals(BackupBundleManifest.BUNDLE_FORMAT_VERSION, parsed.manifest.formatVersion)
        assertEquals(42L, parsed.manifest.exportedAt)
        val title = parsed.manifest.backup["notes"]!!
            .jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content
        assertEquals("Hello", title)
        assertEquals(1, parsed.media.size)
        assertTrue(parsed.media["att-1"]!!.bytes.contentEquals(bytes))
        assertEquals(0, parsed.droppedAttachments)
    }

    @Test
    fun notesOnlyBundleParses() {
        val archive = BackupBundleCodec.buildBackupBundle(backupDocument, emptyList())
        val parsed = BackupBundleCodec.parseBackupBundle(archive)
        assertEquals(0, parsed.media.size)
        assertEquals(3, parsed.manifest.backup["version"]!!.jsonPrimitive.intOrNull)
    }

    @Test
    fun refusesPathTraversalEntryNames() {
        assertFalse(BackupBundleCodec.isSafeZipEntryName("../evil"))
        assertFalse(BackupBundleCodec.isSafeZipEntryName("/abs"))
        assertFalse(BackupBundleCodec.isSafeZipEntryName("media\\att"))
        assertTrue(BackupBundleCodec.isSafeZipEntryName("media/att-1"))
    }

    @Test
    fun refusesUnsupportedBundleVersion() {
        val high = buildJsonObject {
            put("formatVersion", 99)
            put("app", "Notelikeus")
            put("appVersion", "x")
            put("exportedAt", 1L)
            put("backup", backupDocument)
            putJsonArray("attachments") {}
        }
        val custom = writeRawBundle(Json.encodeToString(JsonObject.serializer(), high))
        val error = assertFailsWith<BundleFormatException> {
            BackupBundleCodec.parseBackupBundle(custom)
        }
        assertTrue(error.message!!.contains("Unsupported bundle version"))
    }

    @Test
    fun limitsMatchWebContract() {
        assertEquals(256L * 1024 * 1024, BackupBundleLimits.MAX_BUNDLE_FILE_BYTES)
        assertEquals(10L * 1024 * 1024, BackupBundleLimits.MAX_BUNDLE_ATTACHMENT_BYTES)
        assertEquals(5_000, BackupBundleLimits.MAX_BUNDLE_ATTACHMENTS)
        assertEquals(10_000, BackupBundleLimits.MAX_ZIP_ENTRIES)
    }

    @Test
    fun checksumMismatchDropsAttachment() {
        val good = byteArrayOf(9, 8, 7)
        val badHashManifest = buildJsonObject {
            put("formatVersion", 4)
            put("app", "Notelikeus")
            put("appVersion", "x")
            put("exportedAt", 1L)
            put("backup", backupDocument)
            putJsonArray("attachments") {
                add(
                    buildJsonObject {
                        put("noteId", 1L)
                        put("attachmentId", "att-hash")
                        put("path", "media/att-hash")
                        put("type", "image")
                        put("sizeBytes", good.size.toLong())
                        put("sha256", "0".repeat(64))
                    },
                )
            }
        }
        val custom = writeRawBundle(
            Json.encodeToString(JsonObject.serializer(), badHashManifest),
            "media/att-hash" to good,
        )
        val parsed = BackupBundleCodec.parseBackupBundle(custom)
        assertEquals(0, parsed.media.size)
        assertEquals(1, parsed.droppedAttachments)
        assertTrue(parsed.warnings.any { it.contains("checksum") })
    }

    @Test
    fun missingMediaIsDroppedWithWarning() {
        val manifest = buildJsonObject {
            put("formatVersion", 4)
            put("app", "Notelikeus")
            put("appVersion", "x")
            put("exportedAt", 1L)
            put("backup", backupDocument)
            putJsonArray("attachments") {
                add(
                    buildJsonObject {
                        put("noteId", 1L)
                        put("attachmentId", "ghost")
                        put("path", "media/ghost")
                        put("type", "image")
                        put("sizeBytes", 3L)
                    },
                )
            }
        }
        val custom = writeRawBundle(Json.encodeToString(JsonObject.serializer(), manifest))
        val parsed = BackupBundleCodec.parseBackupBundle(custom)
        assertEquals(0, parsed.media.size)
        assertEquals(1, parsed.droppedAttachments)
        assertTrue(parsed.warnings.any { it.contains("missing") })
        assertEquals("Hello", parsed.manifest.backup["notes"]!!.jsonArray[0]
            .jsonObject["title"]!!.jsonPrimitive.contentOrNull)
    }

    private fun writeRawBundle(
        manifestJson: String,
        vararg media: Pair<String, ByteArray>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.setMethod(ZipEntry.STORED)
            fun put(name: String, data: ByteArray) {
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
            put(BackupBundleLimits.BUNDLE_MANIFEST_ENTRY, manifestJson.toByteArray(Charsets.UTF_8))
            for ((name, data) in media) put(name, data)
        }
        return out.toByteArray()
    }
}
