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
    fun `bytes that are not a picture decode to null`() {
        assertNull(decodeAttachmentPreview(byteArrayOf(1, 2, 3), maxWidthPx = 1280))
    }
}
