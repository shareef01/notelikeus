package com.aus.notelikeus.ui.editor.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeAttachmentPreview(bytes: ByteArray, maxWidthPx: Int): ImageBitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        // Sample while decoding, so a camera photo's full-size bitmap never exists: the decoder reads
        // it at a power-of-two fraction that is still at least as wide as the editor needs.
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxWidthPx) sampleSize *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return@runCatching null
        // Sampling stops at powers of two, so a 2500 px photo is still 2500 px wide here. Scale the rest
        // of the way: the editor caches several of these, and each one is held at this size.
        val preview = if (decoded.width > maxWidthPx) {
            val outHeight = (decoded.height.toLong() * maxWidthPx / decoded.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, maxWidthPx, outHeight, true).also { scaled ->
                if (scaled !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }
        // Uploads it to the GPU here, off the main thread, rather than on the first frame that draws it.
        preview.prepareToDraw()
        preview.asImageBitmap()
    }.getOrNull()
