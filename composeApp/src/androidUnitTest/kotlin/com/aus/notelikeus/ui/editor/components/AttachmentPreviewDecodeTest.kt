package com.aus.notelikeus.ui.editor.components

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentPreviewDecodeTest {

    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    @Test
    fun `a picture just under twice the width is scaled down to the width, not kept at full size`() {
        // Halving 2500 would go below 1280, so sampling alone leaves this at 2500 px.
        val preview = checkNotNull(decodeAttachmentPreview(png(2500, 1000), maxWidthPx = 1280))

        assertEquals(1280, preview.width)
        assertEquals(512, preview.height)
    }

    @Test
    fun `a large photo ends at the width, keeping its aspect`() {
        val preview = checkNotNull(decodeAttachmentPreview(png(4000, 3000), maxWidthPx = 1280))

        assertEquals(1280, preview.width)
        assertEquals(960, preview.height)
    }

    @Test
    fun `a picture narrower than the width keeps its own size`() {
        val preview = checkNotNull(decodeAttachmentPreview(png(800, 600), maxWidthPx = 1280))

        assertEquals(800, preview.width)
        assertEquals(600, preview.height)
    }

    @Test
    fun `a narrow portrait is bounded by height and keeps its aspect`() {
        val preview = checkNotNull(decodeAttachmentPreview(png(800, 4000), maxWidthPx = 1280))

        assertEquals(256, preview.width)
        assertEquals(1280, preview.height)
    }

    @Test
    fun `a JPEG portrait follows its EXIF orientation`() {
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
        // APP1, little-endian TIFF, one orientation tag with value 6 (90 degrees clockwise).
        val exif = byteArrayOf(
            0xff.toByte(), 0xe1.toByte(), 0, 34, 69, 120, 105, 102, 0, 0,
            73, 73, 42, 0, 8, 0, 0, 0, 1, 0,
            18, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0,
        )
        val portrait = jpeg.copyOfRange(0, 2) + exif + jpeg.copyOfRange(2, jpeg.size)
        val preview = checkNotNull(decodeAttachmentPreview(portrait, maxWidthPx = 1280))

        assertEquals(20, preview.width)
        assertEquals(40, preview.height)
    }

    @Test
    fun `bytes that are not a picture decode to null`() {
        assertNull(decodeAttachmentPreview(byteArrayOf(1, 2, 3), maxWidthPx = 1280))
    }
}
