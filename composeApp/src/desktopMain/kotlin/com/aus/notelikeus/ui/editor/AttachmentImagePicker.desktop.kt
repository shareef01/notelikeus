package com.aus.notelikeus.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities

@Composable
actual fun rememberAttachmentImagePicker(
    enabled: Boolean,
    onPicked: (bytes: ByteArray, mimeType: String) -> Unit,
): () -> Unit = remember(enabled) {
    {
        if (!enabled) return@remember
        SwingUtilities.invokeLater {
            val dialog = FileDialog(null as Frame?, "Choose an image", FileDialog.LOAD).apply {
                file = "*.jpg;*.jpeg;*.png;*.webp;*.gif"
                isVisible = true
            }
            val selected = dialog.file ?: return@invokeLater
            val directory = dialog.directory ?: return@invokeLater
            val file = File(directory, selected)
            if (!file.exists()) return@invokeLater
            val mimeType = when (file.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@invokeLater
            onPicked(bytes, mimeType)
        }
    }
}
