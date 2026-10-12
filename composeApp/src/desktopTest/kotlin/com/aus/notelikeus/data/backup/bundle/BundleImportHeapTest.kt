package com.aus.notelikeus.data.backup.bundle

import com.aus.notelikeus.data.attachments.AttachmentLocalStorage
import com.aus.notelikeus.data.attachments.AttachmentStagingStore
import com.aus.notelikeus.data.attachments.StagedAttachment
import com.aus.notelikeus.data.backup.BackupImportResult
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.data.sync.FakeNoteRepository
import com.aus.notelikeus.data.sync.actionToken
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-07: importing a valid bundle must not need heap proportional to the bundle.
 *
 * The import runs in a child JVM capped well below the bundle's size, so the test fails for any
 * implementation that holds the archive, or all of its images, in memory at once — exactly what a
 * phone with a 256–512 MB app heap cannot afford — and passes only for one that streams.
 */
class BundleImportHeapTest {

    @Test
    fun `a bundle much larger than the heap imports in a 96 MB JVM`() {
        val bundle = File.createTempFile("heap-probe", BackupBundleLimits.BUNDLE_FILE_EXTENSION)
        try {
            writeLargeBundle(bundle, notes = IMAGE_COUNT, imageBytes = IMAGE_BYTES)
            assertTrue(bundle.length() > 128L * 1024 * 1024, "the bundle is not larger than the heap")

            val java = File(System.getProperty("java.home"), "bin/java").path
            val process = ProcessBuilder(
                java,
                "-Xmx96m",
                "-cp",
                System.getProperty("java.class.path"),
                BundleImportHeapProbe::class.java.name,
                bundle.absolutePath,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(5, TimeUnit.MINUTES), "the import did not finish")

            assertEquals(0, process.exitValue(), "import failed in a 96 MB heap:\n$output")
            assertTrue(
                output.contains("imported notes=$IMAGE_COUNT images=$IMAGE_COUNT skipped=0"),
                "unexpected import outcome:\n$output",
            )
        } finally {
            bundle.delete()
        }
    }

    private companion object {
        const val IMAGE_COUNT = 16
        const val IMAGE_BYTES = 9 * 1024 * 1024
    }
}

/**
 * Writes a v4 bundle of [notes] notes, each with one [imageBytes]-byte image, without ever holding
 * more than one 64 KiB chunk: images are generated from a seed, once for the CRC and once to write.
 */
internal fun writeLargeBundle(target: File, notes: Int, imageBytes: Int) {
    val repository = FakeNoteRepository()
    val ids = runBlocking {
        (1..notes).map { index ->
            repository.insertNoteWithResult(
                Note(title = "note $index", content = "body", timestamp = index.toLong(), color = 0),
            )
        }
    }
    val document = runBlocking {
        Json.parseToJsonElement(NoteBackupExporter(repository, "Notelikeus", "test").createJson()).jsonObject
    }
    val manifest = buildJsonObject {
        put("formatVersion", 4)
        put("app", "Notelikeus")
        put("appVersion", "test")
        put("exportedAt", 1L)
        put("backup", document)
        putJsonArray("attachments") {
            ids.forEachIndexed { index, noteId ->
                add(
                    buildJsonObject {
                        put("noteId", noteId)
                        put("attachmentId", "att-$index")
                        put("path", "media/att-$index")
                        put("type", "image")
                        put("mimeType", "image/jpeg")
                        put("sizeBytes", imageBytes.toLong())
                    },
                )
            }
        }
    }

    ZipOutputStream(target.outputStream().buffered()).use { zip ->
        zip.setMethod(ZipEntry.STORED)
        val manifestBytes = Json.encodeToString(JsonObject.serializer(), manifest).toByteArray()
        zip.putNextEntry(storedEntry(BackupBundleLimits.BUNDLE_MANIFEST_ENTRY, manifestBytes.size) {
            it.update(manifestBytes)
        })
        zip.write(manifestBytes)
        zip.closeEntry()
        for (index in ids.indices) {
            zip.putNextEntry(storedEntry("media/att-$index", imageBytes) { crc ->
                generate(index.toLong(), imageBytes) { chunk, length -> crc.update(chunk, 0, length) }
            })
            generate(index.toLong(), imageBytes) { chunk, length -> zip.write(chunk, 0, length) }
            zip.closeEntry()
        }
    }
}

private fun storedEntry(name: String, size: Int, checksum: (CRC32) -> Unit): ZipEntry {
    val crc = CRC32().also(checksum)
    return ZipEntry(name).apply {
        method = ZipEntry.STORED
        this.size = size.toLong()
        compressedSize = size.toLong()
        this.crc = crc.value
    }
}

private fun generate(seed: Long, total: Int, sink: (ByteArray, Int) -> Unit) {
    val random = Random(seed)
    val chunk = ByteArray(64 * 1024)
    var left = total
    while (left > 0) {
        val length = minOf(chunk.size, left)
        random.nextBytes(chunk)
        sink(chunk, length)
        left -= length
    }
}

/** The child JVM's entry point: imports the bundle at `args[0]` with storage that keeps no bytes. */
object BundleImportHeapProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val repository = FakeNoteRepository()
        val transfer = BackupBundleTransfer(
            repository = repository,
            exporter = NoteBackupExporter(repository, "Notelikeus", "test"),
            importer = NoteBackupImporter(repository),
            staging = DiscardingStaging,
            localStorage = NoLocalStorage,
            ownerIdProvider = { null },
            appVersion = "test",
        )
        val result = runBlocking { transfer.importBundle(File(args[0]).toOkioPath(), actionToken()) }
        if (result !is BackupImportResult.Success) {
            println("import result: $result")
            kotlin.system.exitProcess(2)
        }
        println(
            "imported notes=${result.notesImported} images=${result.attachmentsImported} " +
                "skipped=${result.attachmentsSkipped}",
        )
    }

    private object DiscardingStaging : AttachmentStagingStore {
        override suspend fun stage(
            attachmentId: String,
            ownerId: String,
            noteId: Long?,
            bytes: ByteArray,
            mimeType: String,
        ) = StagedAttachment(
            attachmentId = attachmentId,
            ownerId = ownerId,
            noteId = noteId,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            createdAt = 1L,
        )

        override suspend fun readBytes(attachmentId: String, ownerId: String): ByteArray? = null
        override suspend fun metadata(attachmentId: String, ownerId: String): StagedAttachment? = null
        override suspend fun bindNote(attachmentId: String, ownerId: String, noteId: Long) = Unit
        override suspend fun release(attachmentId: String, ownerId: String) = Unit
        override suspend fun list(ownerId: String): List<StagedAttachment> = emptyList()
        override suspend fun isStaged(attachmentId: String, ownerId: String): Boolean = false
    }

    private object NoLocalStorage : AttachmentLocalStorage {
        override fun persistImageBytes(bytes: ByteArray, extension: String): String? = null
        override fun readBytes(storagePath: String): ByteArray? = null
        override fun exists(storagePath: String): Boolean = false
        override fun deleteIfLocal(storagePath: String) = Unit
    }
}
