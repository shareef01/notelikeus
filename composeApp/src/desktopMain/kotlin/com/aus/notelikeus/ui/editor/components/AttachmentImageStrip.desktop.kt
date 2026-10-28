package com.aus.notelikeus.ui.editor.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

actual fun decodeAttachmentPreview(bytes: ByteArray, maxWidthPx: Int): ImageBitmap? =
    runCatching {
        val source = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@runCatching null
        if (source.width <= 0 || source.height <= 0) return@runCatching null
        if (source.width <= maxWidthPx) return@runCatching source.toComposeImageBitmap()

        // ImageIO cannot sample while decoding, so scale afterwards: the held copy is the small one.
        val outHeight = (source.height.toLong() * maxWidthPx / source.width).toInt().coerceAtLeast(1)
        val output = BufferedImage(maxWidthPx, outHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, maxWidthPx, outHeight, null)
        } finally {
            graphics.dispose()
        }
        output.toComposeImageBitmap()
    }.getOrNull()
