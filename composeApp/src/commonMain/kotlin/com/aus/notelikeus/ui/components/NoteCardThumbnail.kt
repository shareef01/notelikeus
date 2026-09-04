package com.aus.notelikeus.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.ui.editor.components.decodeAttachmentImageBitmap

@Composable
fun NoteCardThumbnail(
    attachment: Attachment,
    listStyle: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val loadPreviewBytes = LocalAttachmentPreviewLoader.current
    var bitmap by remember(attachment.id, attachment.storagePath) {
        mutableStateOf<ImageBitmap?>(null)
    }

    LaunchedEffect(attachment.id, attachment.storagePath) {
        val bytes = loadPreviewBytes(attachment) ?: return@LaunchedEffect
        bitmap = decodeAttachmentImageBitmap(bytes)
    }

    val ready = bitmap ?: return
    val thumbModifier = if (listStyle) {
        Modifier.size(if (compact) 40.dp else 56.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .height(if (compact) 88.dp else 128.dp)
    }

    Image(
        bitmap = ready,
        contentDescription = null,
        modifier = modifier
            .then(thumbModifier)
            .clip(RoundedCornerShape(12.dp)),
        contentScale = ContentScale.Crop,
    )
}
