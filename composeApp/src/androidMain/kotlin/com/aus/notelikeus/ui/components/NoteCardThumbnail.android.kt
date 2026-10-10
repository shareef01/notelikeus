package com.aus.notelikeus.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeAttachmentThumbnail(bytes: ByteArray, maxWidthPx: Int, maxHeightPx: Int): ImageBitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        // Sample while decoding, so the full-size bitmap never exists: the decoder reads it at a
        // power-of-two fraction that is still at least as wide as the thumbnail.
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxWidthPx) sampleSize *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return@runCatching null

        val cropped = cropToAspect(decoded, maxWidthPx, maxHeightPx)
        val result = if (cropped.width > maxWidthPx) {
            Bitmap.createScaledBitmap(cropped, maxWidthPx, maxHeightPx, true)
        } else {
            cropped
        }
        // Free whatever intermediate bitmaps are not the one being returned.
        if (cropped !== decoded && cropped !== result) cropped.recycle()
        if (decoded !== result) decoded.recycle()
        result.asImageBitmap()
    }.getOrNull()

/** Portrait images keep their top, landscape ones their centre; the result has the aspect of the target. */
private fun cropToAspect(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
    val targetAspect = targetWidth.toFloat() / targetHeight
    val sourceAspect = source.width.toFloat() / source.height
    return if (sourceAspect >= targetAspect) {
        val width = (source.height * targetAspect).toInt().coerceIn(1, source.width)
        Bitmap.createBitmap(source, (source.width - width) / 2, 0, width, source.height)
    } else {
        val height = (source.width / targetAspect).toInt().coerceIn(1, source.height)
        Bitmap.createBitmap(source, 0, 0, source.width, height)
    }
}
