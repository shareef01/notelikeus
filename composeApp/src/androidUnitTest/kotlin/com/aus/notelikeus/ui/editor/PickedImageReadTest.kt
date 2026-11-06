package com.aus.notelikeus.ui.editor

import android.content.ContentResolver
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
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
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PickedImageReadTest {
    private val resolver = mockk<ContentResolver>()
    private val uri = Uri.parse("content://audit-images/image")

    @Test
    fun providerMetadataAndGrantFailuresAreRefused() {
        every { resolver.getType(uri) } throws SecurityException("grant revoked")
        assertNull(readPickedImage(resolver, uri))
        every { resolver.getType(uri) } returns "image/png"
        every { resolver.openInputStream(uri) } throws FileNotFoundException("provider disappeared")
        assertNull(readPickedImage(resolver, uri))
    }

    @Test
    fun brokenProviderStreamIsClosedAndRefused() {
        var closed = false
        every { resolver.getType(uri) } returns "image/png"
        every { resolver.openInputStream(uri) } returns object : InputStream() {
            override fun read(): Int = throw IOException("provider failed")
            override fun close() { closed = true }
        }
        assertNull(readPickedImage(resolver, uri))
        assertTrue(closed)
    }

    @Test
    fun validImageStillReachesTheEditor() {
        every { resolver.getType(uri) } returns "image/png"
        every { resolver.openInputStream(uri) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val picked = requireNotNull(readPickedImage(resolver, uri))
        assertArrayEquals(byteArrayOf(1, 2, 3), picked.first)
        assertEquals("image/png", picked.second)
    }
}
