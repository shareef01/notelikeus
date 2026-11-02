package com.aus.notelikeus.data.backup.bundle

import com.aus.notelikeus.data.backup.BackupBundleManifest
import com.aus.notelikeus.contract.ContractFixtures
import java.io.File
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
    fun parsesCommittedCrossPlatformWebAndKotlinBundleFixtures() {
        val sampleBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3, 4)

        // 1. Verify Kotlin parses genuine Web-exported bundle fixture from contracts
        assertTrue(ContractFixtures.fileExists("backup/v4-web-bundle.nlkbak"), "Required contract fixture backup/v4-web-bundle.nlkbak must be committed and present")
        val webArchive = ContractFixtures.readBytes("backup/v4-web-bundle.nlkbak")
        val parsedWeb = BackupBundleCodec.parseBackupBundle(webArchive)
        assertEquals(4, parsedWeb.manifest.formatVersion)
        assertEquals("Notelikeus", parsedWeb.manifest.app)
        assertEquals(1, parsedWeb.media.size)
        assertTrue(parsedWeb.media.containsKey("att-cross-1"))
        assertTrue(parsedWeb.media["att-cross-1"]!!.bytes.contentEquals(sampleBytes))
        val webTitle = parsedWeb.manifest.backup["notes"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content
        assertEquals("From Web Exporter", webTitle)

        // 2. Verify Kotlin parses genuine Kotlin-exported bundle fixture from contracts
        assertTrue(ContractFixtures.fileExists("backup/v4-kotlin-bundle.nlkbak"), "Required contract fixture backup/v4-kotlin-bundle.nlkbak must be committed and present")
        val kotlinArchive = ContractFixtures.readBytes("backup/v4-kotlin-bundle.nlkbak")
        val parsedKotlin = BackupBundleCodec.parseBackupBundle(kotlinArchive)
        assertEquals(4, parsedKotlin.manifest.formatVersion)
        assertEquals("Notelikeus", parsedKotlin.manifest.app)
        assertEquals(1, parsedKotlin.media.size)
        assertTrue(parsedKotlin.media.containsKey("att-cross-1"))
        assertTrue(parsedKotlin.media["att-cross-1"]!!.bytes.contentEquals(sampleBytes))
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


    @Test
    fun `valid 40 MiB bundle with attachments opens cleanly`() {
        val target = File.createTempFile("bundle-40mb", BackupBundleLimits.BUNDLE_FILE_EXTENSION)
        try {
            writeLargeBundle(target, notes = 5, imageBytes = 8 * 1024 * 1024)
            assertTrue(target.length() >= 40L * 1024 * 1024, "bundle size should be at least 40 MiB")
            val bundle = BackupBundleCodec.openBackupBundle(target)
            bundle.use {
                assertEquals(4, it.manifest.formatVersion)
                assertEquals(5, it.manifest.attachments.size)
                assertEquals(0, it.droppedAttachments)
            }
        } finally {
            target.delete()
        }
    }

    @Test
    fun `bundle exceeding 256 MiB archive boundary is rejected`() {
        val target = File.createTempFile("oversized-bundle", ".nlkbak")
        try {
            val raf = java.io.RandomAccessFile(target, "rw")
            raf.setLength(BackupBundleLimits.MAX_BUNDLE_FILE_BYTES + 1L)
            raf.close()
            assertFailsWith<BundleFormatException> {
                BackupBundleCodec.openBackupBundle(target)
            }
        } finally {
            target.delete()
        }
    }

    @Test
    fun `oversized manifest inside valid zip is rejected`() {
        val hugeManifest = "x".repeat((BackupBundleLimits.MAX_BUNDLE_MANIFEST_BYTES + 1).toInt())
        val custom = writeRawBundle(hugeManifest)
        val file = File.createTempFile("oversized-manifest", ".nlkbak")
        try {
            file.writeBytes(custom)
            assertFailsWith<BundleFormatException> {
                BackupBundleCodec.openBackupBundle(file)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `truncated and malformed zip is rejected`() {
        val malformedBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4, 5)
        assertFailsWith<BundleFormatException> {
            BackupBundleCodec.parseBackupBundle(malformedBytes)
        }
    }

    @Test
    fun `archive decompression limit violation is rejected`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            val manifestBytes = Json.encodeToString(JsonObject.serializer(), backupDocument).toByteArray()
            val mEntry = ZipEntry(BackupBundleLimits.BUNDLE_MANIFEST_ENTRY)
            zos.putNextEntry(mEntry)
            zos.write(manifestBytes)
            zos.closeEntry()

            for (i in 0..16) {
                val entry = ZipEntry("media/att-$i")
                entry.size = 32L * 1024 * 1024
                zos.putNextEntry(entry)
                zos.write(ByteArray(10))
                zos.closeEntry()
            }
        }
        val file = File.createTempFile("zip-bomb-test", ".nlkbak")
        try {
            file.writeBytes(out.toByteArray())
            assertFailsWith<BundleFormatException> {
                BackupBundleCodec.openBackupBundle(file)
            }
        } finally {
            file.delete()
        }
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
