package com.aus.notelikeus.ui.editor.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayInputStream

internal actual fun attachmentViewerDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

actual fun decodeAttachmentPreview(bytes: ByteArray, maxWidthPx: Int): ImageBitmap? =
    runCatching {
        require(maxWidthPx > 0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        // Sample while decoding, so a camera photo's full-size bitmap never exists: the decoder reads
        // it at a power-of-two fraction before resizing. Bound the long edge so narrow,
        // very tall photos cannot bypass the allocation limit.
        val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (longestSide / sampleSize / 2 >= maxWidthPx) sampleSize *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return@runCatching null
        // Sampling stops at powers of two, so a 2500 px photo is still 2500 px wide here. Scale the rest
        // of the way: the editor caches several of these, and each one is held at this size.
        val decodedSide = maxOf(decoded.width, decoded.height)
        val preview = if (decodedSide > maxWidthPx) {
            val outWidth = (decoded.width.toLong() * maxWidthPx / decodedSide).toInt().coerceAtLeast(1)
            val outHeight = (decoded.height.toLong() * maxWidthPx / decodedSide).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, outWidth, outHeight, true).also { scaled ->
                if (scaled !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }
        val upright = orientJpegPreview(preview, bytes)
        // Uploads it to the GPU here, off the main thread, rather than on the first frame that draws it.
        upright.prepareToDraw()
        upright.asImageBitmap()
    }.getOrNull()

private fun orientJpegPreview(preview: Bitmap, bytes: ByteArray): Bitmap {
    if (bytes.size < 2 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()) return preview
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val transform = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            transform.setRotate(90f)
            transform.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            transform.setRotate(-90f)
            transform.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(-90f)
        else -> return preview
    }
    return Bitmap.createBitmap(preview, 0, 0, preview.width, preview.height, transform, true).also {
        if (it !== preview) preview.recycle()
    }
}
