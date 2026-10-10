package com.aus.notelikeus.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

actual fun decodeAttachmentThumbnail(bytes: ByteArray, maxWidthPx: Int, maxHeightPx: Int): ImageBitmap? =
    runCatching {
        val source = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@runCatching null
        if (source.width <= 0 || source.height <= 0) return@runCatching null

        // Portrait images keep their top, landscape ones their centre.
        val targetAspect = maxWidthPx.toFloat() / maxHeightPx
        val cropWidth: Int
        val cropHeight: Int
        val cropX: Int
        if (source.width.toFloat() / source.height >= targetAspect) {
            cropWidth = (source.height * targetAspect).toInt().coerceIn(1, source.width)
            cropHeight = source.height
            cropX = (source.width - cropWidth) / 2
        } else {
            cropWidth = source.width
            cropHeight = (source.width / targetAspect).toInt().coerceIn(1, source.height)
            cropX = 0
        }
        val outWidth = minOf(maxWidthPx, cropWidth)
        val outHeight = (outWidth / targetAspect).toInt().coerceAtLeast(1)

        val output = BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(
                source.getSubimage(cropX, 0, cropWidth, cropHeight),
                0,
                0,
                outWidth,
                outHeight,
                null,
            )
        } finally {
            graphics.dispose()
        }
        output.toComposeImageBitmap()
    }.getOrNull()
