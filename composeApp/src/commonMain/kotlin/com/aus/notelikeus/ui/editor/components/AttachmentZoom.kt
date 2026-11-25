package com.aus.notelikeus.ui.editor.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

internal data class AttachmentZoom(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    fun transform(
        viewport: IntSize,
        image: IntSize,
        requestedScale: Float,
        focalPoint: Offset = Offset.Zero,
        drag: Offset = Offset.Zero,
    ): AttachmentZoom {
        val zoom = requestedScale.coerceIn(Fit, Maximum)
        if (zoom == Fit) return AttachmentZoom()
        val fitted = minOf(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
        val width = image.width * fitted * zoom
        val height = image.height * fitted * zoom
        val maxX = ((width - viewport.width) / 2).coerceAtLeast(0f)
        val maxY = ((height - viewport.height) / 2).coerceAtLeast(0f)
        // Coordinates are relative to the viewport center, which is also the graphics layer pivot.
        val moved = focalPoint + (offset - focalPoint) * (zoom / scale) + drag
        val x = if (maxX > 0f) moved.x.coerceIn(-maxX, maxX) else 0f
        val y = if (maxY > 0f) moved.y.coerceIn(-maxY, maxY) else 0f
        return AttachmentZoom(zoom, Offset(x, y))
    }

    fun step(increase: Boolean): Float = if (increase) {
        Levels.firstOrNull { it > scale } ?: Maximum
    } else {
        Levels.lastOrNull { it < scale } ?: Fit
    }

    companion object {
        const val Fit = 1f
        const val Maximum = 4f
        private val Levels = listOf(Fit, 1.5f, 2f, 3f, Maximum)
    }
}
