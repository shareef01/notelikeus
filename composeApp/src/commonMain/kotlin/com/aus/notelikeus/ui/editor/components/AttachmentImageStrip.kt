package com.aus.notelikeus.ui.editor.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.cd_attachment
import notelikeus.composeapp.generated.resources.cd_remove_attachment
import notelikeus.composeapp.generated.resources.image_load_failed

@Composable
fun AttachmentImageStrip(
    attachments: List<Attachment>,
    contentColor: Color,
    loadPreviewBytes: suspend (Attachment) -> ByteArray?,
    onRemove: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        attachments.forEach { attachment ->
            AttachmentImagePreview(
                attachment = attachment,
                contentColor = contentColor,
                loadPreviewBytes = loadPreviewBytes,
                onRemove = { onRemove(attachment) },
            )
        }
    }
}

@Composable
private fun AttachmentImagePreview(
    attachment: Attachment,
    contentColor: Color,
    loadPreviewBytes: suspend (Attachment) -> ByteArray?,
    onRemove: () -> Unit,
) {
    var bitmap by remember(attachment.id, attachment.storagePath) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(attachment.id, attachment.storagePath) { mutableStateOf(false) }

    LaunchedEffect(attachment.id, attachment.storagePath) {
        failed = false
        bitmap = null
        val bytes = loadPreviewBytes(attachment) ?: run {
            failed = true
            return@LaunchedEffect
        }
        bitmap = decodeAttachmentImageBitmap(bytes)
        if (bitmap == null) failed = true
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.lg),
    ) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = stringResource(Res.string.cd_attachment),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Crop,
                )
            }
            failed -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.ImageNotSupported,
                        contentDescription = stringResource(Res.string.image_load_failed),
                        tint = contentColor.copy(alpha = 0.4f),
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = contentColor.copy(alpha = 0.6f),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(Spacing.sm)
                .size(32.dp),
            shape = MaterialTheme.shapes.small,
            color = Color.Black.copy(alpha = 0.55f),
        ) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(Res.string.cd_remove_attachment),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

expect fun decodeAttachmentImageBitmap(bytes: ByteArray): ImageBitmap?
