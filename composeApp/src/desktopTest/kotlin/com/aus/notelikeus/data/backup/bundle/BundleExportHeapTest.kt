package com.aus.notelikeus.data.backup.bundle

import com.aus.notelikeus.data.attachments.AttachmentLocalStorage
import com.aus.notelikeus.data.attachments.AttachmentStagingStore
import com.aus.notelikeus.data.attachments.GUEST_STAGING_OWNER
import com.aus.notelikeus.data.attachments.StagedAttachment
import com.aus.notelikeus.data.attachments.pendingStoragePath
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.data.sync.FakeNoteRepository
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.runBlocking
import okio.sink
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * F-07, export side: building a bundle must not need heap proportional to the library's images.
 *
 * The export runs in a child JVM capped well below the images' total size, writing to a file, so
 * the test fails for any implementation that holds every image, or the whole archive, in memory.
 * The parent then opens what was written and verifies every image's size and checksum.
 */
class BundleExportHeapTest {

    @Test
    fun `a library much larger than the heap exports in a 96 MB JVM`() {
        val bundle = File.createTempFile("export-probe", BackupBundleLimits.BUNDLE_FILE_EXTENSION)
        try {
            val java = File(System.getProperty("java.home"), "bin/java").path
            val process = ProcessBuilder(
                java,
                "-Xmx96m",
                "-cp",
                System.getProperty("java.class.path"),
                BundleExportHeapProbe::class.java.name,
                bundle.absolutePath,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(5, TimeUnit.MINUTES), "the export did not finish")
            assertEquals(0, process.exitValue(), "export failed in a 96 MB heap:\n$output")
            assertTrue(
                output.contains("exported images=${BundleExportHeapProbe.IMAGE_COUNT} skipped=0"),
                "unexpected export outcome:\n$output",
            )

            assertTrue(bundle.length() > 128L * 1024 * 1024, "the bundle is not larger than the heap")
            BackupBundleCodec.openBackupBundle(bundle).use { opened ->
                assertEquals(BundleExportHeapProbe.IMAGE_COUNT, opened.manifest.attachments.size)
                for (entry in opened.manifest.attachments) {
                    val read = opened.readMedia(entry)
                    assertIs<MediaRead.Loaded>(read, "image ${entry.attachmentId} did not verify")
                    val expected = ByteArrayOutputStream()
                    generate(BundleExportHeapProbe.seedOf(entry.attachmentId), BundleExportHeapProbe.IMAGE_BYTES) {
                        chunk, length -> expected.write(chunk, 0, length)
                    }
                    assertTrue(read.media.bytes.contentEquals(expected.toByteArray()), "image ${entry.attachmentId} changed")
                }
            }
        } finally {
            bundle.delete()
        }
    }
}

/** The child JVM's entry point: exports a generated library to the file at `args[0]`. */
object BundleExportHeapProbe {
    const val IMAGE_COUNT = 16
    const val IMAGE_BYTES = 9 * 1024 * 1024

    fun seedOf(attachmentId: String): Long = attachmentId.removePrefix("att-").toLong()

    @JvmStatic
    fun main(args: Array<String>) {
        val repository = FakeNoteRepository()
        runBlocking {
            for (index in 0 until IMAGE_COUNT) {
                val noteId = repository.insertNoteWithResult(
                    Note(title = "note $index", content = "body", timestamp = index.toLong(), color = 0),
                )
                repository.updateNote(
                    repository.getNoteById(noteId)!!.copy(
                        attachments = listOf(
                            Attachment(
                                id = "att-$index",
                                noteId = noteId,
                                storagePath = pendingStoragePath("att-$index"),
                                mimeType = "image/jpeg",
                            ),
                        ),
                    ),
                )
            }
        }
        val transfer = BackupBundleTransfer(
            repository = repository,
            exporter = NoteBackupExporter(repository, "Notelikeus", "test"),
            importer = NoteBackupImporter(repository),
            staging = GeneratingStaging,
            localStorage = NoLocalStorage,
            ownerIdProvider = { null },
            appVersion = "test",
        )
        val outcome = runBlocking { transfer.exportBundle(File(args[0]).sink()) }
        println("exported images=${outcome.attachmentsIncluded} skipped=${outcome.attachmentsSkipped}")
    }

    /** Hands out each image freshly generated on every read, so the store itself holds nothing. */
    private object GeneratingStaging : AttachmentStagingStore {
        override suspend fun readBytes(attachmentId: String, ownerId: String): ByteArray? {
            if (ownerId != GUEST_STAGING_OWNER) return null
            val out = ByteArrayOutputStream(IMAGE_BYTES)
            generate(seedOf(attachmentId), IMAGE_BYTES) { chunk, length -> out.write(chunk, 0, length) }
            return out.toByteArray()
        }

        override suspend fun stage(
            attachmentId: String,
            ownerId: String,
            noteId: Long?,
            bytes: ByteArray,
            mimeType: String,
        ): StagedAttachment? = null

        override suspend fun metadata(attachmentId: String, ownerId: String): StagedAttachment? = null
        override suspend fun bindNote(attachmentId: String, ownerId: String, noteId: Long) = Unit
        override suspend fun release(attachmentId: String, ownerId: String) = Unit
        override suspend fun list(ownerId: String): List<StagedAttachment> = emptyList()
        override suspend fun isStaged(attachmentId: String, ownerId: String): Boolean = true
    }

    private object NoLocalStorage : AttachmentLocalStorage {
        override fun persistImageBytes(bytes: ByteArray, extension: String): String? = null
        override fun readBytes(storagePath: String): ByteArray? = null
        override fun exists(storagePath: String): Boolean = false
        override fun deleteIfLocal(storagePath: String) = Unit
    }
}
