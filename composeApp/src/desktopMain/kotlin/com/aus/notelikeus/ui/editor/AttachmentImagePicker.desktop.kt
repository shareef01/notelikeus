package com.aus.notelikeus.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.aus.notelikeus.data.attachments.readBoundedAttachment
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.attachment_picker_title
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun rememberAttachmentImagePicker(
    enabled: Boolean,
    onPicked: (bytes: ByteArray, mimeType: String) -> Unit,
): () -> Unit {
    // Read here, in composition: the dialog itself opens later on the Swing thread.
    val dialogTitle = stringResource(Res.string.attachment_picker_title)
    return remember(enabled, dialogTitle) {
        {
            if (enabled) {
                SwingUtilities.invokeLater { pickImage(dialogTitle, onPicked) }
            }
        }
    }
}

private fun pickImage(dialogTitle: String, onPicked: (bytes: ByteArray, mimeType: String) -> Unit) {
    val dialog = FileDialog(null as Frame?, dialogTitle, FileDialog.LOAD).apply {
        file = "*.jpg;*.jpeg;*.png;*.webp;*.gif"
        isVisible = true
    }
    val selected = dialog.file ?: return
    val directory = dialog.directory ?: return
    val file = File(directory, selected)
    if (!file.exists()) return
    val mimeType = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }
    val bytes = runCatching { file.inputStream().use { readBoundedAttachment(it) } }
        .getOrNull() ?: return
    onPicked(bytes, mimeType)
}
