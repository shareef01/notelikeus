package com.aus.notelikeus.ui.navigation

import android.content.ContentResolver
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExternalImageIngestorTest {

    private val contentResolver = mockk<ContentResolver>()
    private val ingestor = DefaultExternalImageIngestor(contentResolver, maxBytes = 100)

    @Test
    fun `ingest succeeds for valid content uri and image stream`() = runTest {
        val uri = Uri.parse("content://media/external/images/media/1")
        val testBytes = byteArrayOf(10, 20, 30, 40)
        every { contentResolver.getType(uri) } returns "image/png"
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testBytes)

        val result = ingestor.ingest(uri, "image/*")

        assertTrue(result is IngestionResult.Success)
        val success = result as IngestionResult.Success
        assertArrayEquals(testBytes, success.bytes)
        assertEquals("image/png", success.mimeType)
    }

    @Test
    fun `ingest rejects non-content scheme`() = runTest {
        val fileUri = Uri.parse("file:///data/local/tmp/img.png")
        val result = ingestor.ingest(fileUri, "image/png")

        assertTrue(result is IngestionResult.Failure)
        val failure = result as IngestionResult.Failure
        assertTrue(failure.reason.contains("Unsupported scheme"))
    }

    @Test
    fun `ingest rejects provider reporting non-image mime in getType`() = runTest {
        val uri = Uri.parse("content://downloads/doc/1")
        every { contentResolver.getType(uri) } returns "application/pdf"

        val result = ingestor.ingest(uri, "image/png")

        assertTrue(result is IngestionResult.Failure)
        val failure = result as IngestionResult.Failure
        assertTrue(failure.reason.contains("not an image"))
    }

    @Test
    fun `ingest falls back to declared mime when getType returns null`() = runTest {
        val uri = Uri.parse("content://photos/1")
        val testBytes = byteArrayOf(1, 2, 3)
        every { contentResolver.getType(uri) } returns null
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testBytes)

        val result = ingestor.ingest(uri, "image/webp")

        assertTrue(result is IngestionResult.Success)
        val success = result as IngestionResult.Success
        assertArrayEquals(testBytes, success.bytes)
        assertEquals("image/webp", success.mimeType)
    }

    @Test
    fun `ingest falls back to image jpeg when getType is null and declared is image wildcard`() = runTest {
        val uri = Uri.parse("content://photos/1")
        val testBytes = byteArrayOf(1, 2, 3)
        every { contentResolver.getType(uri) } returns null
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testBytes)

        val result = ingestor.ingest(uri, "image/*")

        assertTrue(result is IngestionResult.Success)
        val success = result as IngestionResult.Success
        assertArrayEquals(testBytes, success.bytes)
        assertEquals("image/jpeg", success.mimeType)
    }

    @Test
    fun `ingest rejects when stream exceeds maxBytes`() = runTest {
        val uri = Uri.parse("content://photos/oversized")
        val oversizedBytes = ByteArray(101) { 1 }
        every { contentResolver.getType(uri) } returns "image/png"
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(oversizedBytes)

        val result = ingestor.ingest(uri, "image/png")

        assertTrue(result is IngestionResult.Failure)
        val failure = result as IngestionResult.Failure
        assertTrue(failure.reason.contains("limit"))
    }

    @Test
    fun `ingest rejects zero byte stream`() = runTest {
        val uri = Uri.parse("content://photos/empty")
        every { contentResolver.getType(uri) } returns "image/png"
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(ByteArray(0))

        val result = ingestor.ingest(uri, "image/png")

        assertTrue(result is IngestionResult.Failure)
        val failure = result as IngestionResult.Failure
        assertTrue(failure.reason.contains("empty"))
    }

    @Test
    fun `ingest handles SecurityException safely`() = runTest {
        val uri = Uri.parse("content://protected/photo")
        every { contentResolver.getType(uri) } returns "image/png"
        every { contentResolver.openInputStream(uri) } throws SecurityException("Permission denied")

        val result = ingestor.ingest(uri, "image/png")

        assertTrue(result is IngestionResult.Failure)
        val failure = result as IngestionResult.Failure
        assertEquals("SecurityException", failure.reason)
    }

    @Test
    fun `ingest handles FileNotFoundException safely`() = runTest {
        val uri = Uri.parse("content://vanished/photo")
        every { contentResolver.getType(uri) } returns "image/png"
        every { contentResolver.openInputStream(uri) } throws FileNotFoundException("File not found")

        val result = ingestor.ingest(uri, "image/png")

        assertTrue(result is IngestionResult.Failure)
        val failure = result as IngestionResult.Failure
        assertEquals("FileNotFoundException", failure.reason)
    }

    @Test
    fun `readBoundedStream stops reading immediately when stream exceeds limit`() {
        var bytesSupplied = 0
        val infiniteStream = object : InputStream() {
            override fun read(): Int {
                bytesSupplied++
                return 42
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val toRead = minOf(len, 64)
                for (i in 0 until toRead) {
                    b[off + i] = 42
                }
                bytesSupplied += toRead
                return toRead
            }
        }

        val result = readBoundedStream(infiniteStream, maxBytes = 50)

        assertNull(result)
        // Ensure we didn't read endlessly: bounded reading stopped around limit + buffer size
        assertTrue("bytesSupplied ($bytesSupplied) was too high", bytesSupplied <= 50 + 8192)
    }
}
