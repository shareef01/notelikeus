package com.aus.notelikeus.ui.editor.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

actual fun decodeAttachmentImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching {
        ImageIO.read(ByteArrayInputStream(bytes))?.toComposeImageBitmap()
    }.getOrNull()
