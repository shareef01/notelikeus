package com.aus.notelikeus.ui.editor.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentZoomTest {
    private val viewport = IntSize(360, 480)
    private val portrait = IntSize(500, 1000)

    @Test
    fun `pinching keeps the point under the fingers in place`() {
        val result = AttachmentZoom().transform(viewport, portrait, 2f, focalPoint = Offset(20f, -60f))
        assertEquals(2f, result.scale)
        assertEquals(Offset(-20f, 60f), result.offset)
    }

    @Test
    fun `panning is bounded to the fitted image after scaling and resizing`() {
        val zoomed = AttachmentZoom().transform(viewport, portrait, 2f, drag = Offset(200f, -500f))
        assertEquals(Offset(60f, -240f), zoomed.offset)
        val resized = zoomed.transform(IntSize(800, 400), portrait, zoomed.scale)
        assertEquals(0f, resized.offset.x)
        assertEquals(-200f, resized.offset.y)
        assertEquals(Offset.Zero, resized.transform(viewport, portrait, 1f).offset)
    }

    @Test
    fun `button steps work between pinch levels and the scale is capped`() {
        val zoomed = AttachmentZoom().transform(viewport, portrait, 1.7f)
        assertEquals(1.5f, zoomed.step(false))
        assertEquals(2f, zoomed.step(true))
        assertEquals(AttachmentZoom.Maximum, zoomed.transform(viewport, portrait, 10f).scale)
        assertEquals(AttachmentZoom.Fit, zoomed.transform(viewport, portrait, 0.5f).scale)
    }
}
