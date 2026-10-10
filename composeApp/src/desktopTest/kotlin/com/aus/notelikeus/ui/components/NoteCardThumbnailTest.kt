package com.aus.notelikeus.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import com.aus.notelikeus.domain.model.Attachment
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NoteCardThumbnailTest {

    // ---- which picture stands for a note ----

    @Test
    fun `the first image attachment is the one shown`() {
        val attachments = listOf(attachment("a", mimeType = "image/png"), attachment("b", mimeType = "image/jpeg"))

        assertEquals("a", attachments.firstImageAttachment()?.id)
    }

    @Test
    fun `an attachment with no recorded type counts as an image`() {
        // Older rows carry no mime type; they were images, because images were all that could be attached.
        assertEquals("old", listOf(attachment("old", mimeType = null)).firstImageAttachment()?.id)
    }

    @Test
    fun `a non-image before an image is skipped`() {
        val attachments = listOf(attachment("doc", mimeType = "application/pdf"), attachment("photo", mimeType = "image/webp"))

        assertEquals("photo", attachments.firstImageAttachment()?.id)
    }

    @Test
    fun `no image means no thumbnail`() {
        assertNull(listOf(attachment("doc", mimeType = "application/pdf")).firstImageAttachment())
        assertNull(emptyList<Attachment>().firstImageAttachment())
    }

    // ---- the decoder ----

    @Test
    fun `a tall screenshot is cut down to the thumbnail size`() {
        val thumbnail = assertNotNull(decodeAttachmentThumbnail(png(900, 1800), 480, 360))

        assertEquals(480, thumbnail.width)
        assertEquals(360, thumbnail.height)
    }

    @Test
    fun `a wide photo is cut down to the thumbnail size`() {
        val thumbnail = assertNotNull(decodeAttachmentThumbnail(png(1600, 900), 480, 360))

        assertEquals(480, thumbnail.width)
        assertEquals(360, thumbnail.height)
    }

    @Test
    fun `a small image is not blown up`() {
        val thumbnail = assertNotNull(decodeAttachmentThumbnail(png(100, 50), 480, 360))

        assertTrue(thumbnail.width <= 100, "width ${thumbnail.width} exceeds the source")
        assertTrue(thumbnail.height <= 50, "height ${thumbnail.height} exceeds the source")
    }

    @Test
    fun `a portrait image is cropped from the top`() {
        // 900x1800 is cropped to its top 900x675. Red fills the top 1200 rows, blue the rest, so a
        // top crop is entirely red; a centred crop would reach the blue.
        val bytes = png(900, 1800) { _, y -> if (y < 1200) Color.RED else Color.BLUE }

        val thumbnail = assertNotNull(decodeAttachmentThumbnail(bytes, 480, 360))

        assertTrue(thumbnail.isMostlyRed(row = thumbnail.height - 1), "the bottom row should still be red")
    }

    @Test
    fun `a landscape image is cropped from the centre`() {
        // 1600x900 is cropped to its middle 1200x900. Blue fills the outer 200 columns each side.
        val bytes = png(1600, 900) { x, _ -> if (x < 200 || x >= 1400) Color.BLUE else Color.RED }

        val thumbnail = assertNotNull(decodeAttachmentThumbnail(bytes, 480, 360))

        assertTrue(thumbnail.isMostlyRed(row = 0, column = 0), "the left edge should be the red middle, not the blue margin")
    }

    @Test
    fun `bytes that are not an image give no thumbnail`() {
        assertNull(decodeAttachmentThumbnail(byteArrayOf(1, 2, 3, 4), 480, 360))
        assertNull(decodeAttachmentThumbnail(ByteArray(0), 480, 360))
    }

    // ---- the cache ----

    @Test
    fun `the cache returns what was stored`() {
        val cache = AttachmentThumbnailCache()
        val bitmap = ImageBitmap(2, 2)

        cache.put("k", bitmap)

        assertSame(bitmap, cache.get("k"))
        assertNull(cache.get("missing"))
    }

    @Test
    fun `the cache drops the least recently used picture once it is full`() {
        val cache = AttachmentThumbnailCache(maxEntries = 2)
        cache.put("a", ImageBitmap(1, 1))
        cache.put("b", ImageBitmap(1, 1))
        cache.get("a") // a is now newer than b
        cache.put("c", ImageBitmap(1, 1))

        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"), "b was the least recently used")
        assertNotNull(cache.get("c"))
        assertEquals(2, cache.size)
    }

    @Test
    fun `storing a picture again replaces it without growing the cache`() {
        val cache = AttachmentThumbnailCache(maxEntries = 2)
        val first = ImageBitmap(1, 1)
        val second = ImageBitmap(1, 1)
        cache.put("a", first)
        cache.put("a", second)

        assertNotSame(first, cache.get("a"))
        assertEquals(1, cache.size)
    }

    @Test
    fun `a failure is remembered, and forgotten once the picture loads`() {
        val cache = AttachmentThumbnailCache()

        assertFalse(cache.recentlyFailed("a"))
        cache.markFailed("a")
        assertTrue(cache.recentlyFailed("a"))

        cache.put("a", ImageBitmap(1, 1))
        assertFalse(cache.recentlyFailed("a"))
    }

    @Test
    fun `clearing empties pictures and failures`() {
        val cache = AttachmentThumbnailCache()
        cache.put("a", ImageBitmap(1, 1))
        cache.markFailed("b")

        cache.clear()

        assertEquals(0, cache.size)
        assertFalse(cache.recentlyFailed("b"))
    }

    @Test
    fun `after a clear nothing stored before it is served`() {
        val cache = AttachmentThumbnailCache()
        cache.put("a", ImageBitmap(1, 1))

        cache.clear()

        assertNull(cache.get("a"))
        assertEquals(0, cache.size)
    }

    @Test
    fun `a load that began before a clear is refused when it finishes`() {
        val cache = AttachmentThumbnailCache()
        val startedIn = cache.currentGeneration

        cache.clear() // the account changed while the picture was loading
        val kept = cache.put("a", ImageBitmap(1, 1), startedIn)

        assertFalse(kept, "a picture from before the clear must not be cached")
        assertNull(cache.get("a"))
    }

    @Test
    fun `a failure recorded before a clear is not remembered after it`() {
        val cache = AttachmentThumbnailCache()
        val startedIn = cache.currentGeneration
        cache.markFailed("a", startedIn)
        assertTrue(cache.recentlyFailed("a"))

        cache.clear()

        assertFalse(cache.recentlyFailed("a"))
        cache.markFailed("b", startedIn) // a late failure from before the clear
        assertFalse(cache.recentlyFailed("b"))
    }

    @Test
    fun `pictures stored before a clear do not use up room after it`() {
        val cache = AttachmentThumbnailCache(maxEntries = 2)
        cache.put("old1", ImageBitmap(1, 1))
        cache.put("old2", ImageBitmap(1, 1))

        cache.clear()
        cache.put("new1", ImageBitmap(1, 1))
        cache.put("new2", ImageBitmap(1, 1))

        assertNotNull(cache.get("new1"))
        assertNotNull(cache.get("new2"))
        assertEquals(2, cache.size)
    }

    @Test
    fun `the public clear empties the shared cache`() {
        SharedThumbnailCache.put("shared", ImageBitmap(1, 1))
        assertNotNull(SharedThumbnailCache.get("shared"))

        clearAttachmentThumbnailCache()

        assertNull(SharedThumbnailCache.get("shared"))
    }

    // ---- fixtures ----

    private fun attachment(id: String, mimeType: String?) =
        Attachment(id = id, noteId = 1L, storagePath = "pending:$id", mimeType = mimeType)

    private fun png(
        width: Int,
        height: Int,
        colorAt: (x: Int, y: Int) -> Color = { _, _ -> Color.GRAY },
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, colorAt(x, y).rgb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** Whether the pixel at [row], [column] is dominated by red. Defaults to the middle of the row. */
    private fun ImageBitmap.isMostlyRed(row: Int, column: Int = width / 2): Boolean {
        val pixels = IntArray(width * height)
        readPixels(pixels, 0, 0, width, height)
        val argb = pixels[row * width + column]
        val red = (argb shr 16) and 0xff
        val blue = argb and 0xff
        return red > 200 && blue < 80
    }
}
