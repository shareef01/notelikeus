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
import com.aus.notelikeus.ui.components.AttachmentThumbnailCache
import com.aus.notelikeus.ui.components.SharedThumbnailCache
import com.aus.notelikeus.ui.components.attachmentImageKey
import com.aus.notelikeus.ui.theme.NoteEmphasis
import com.aus.notelikeus.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val key = attachmentImageKey(attachment)
    // Opened earlier this session: the decoded preview is still held, and nothing is fetched again.
    val cached = remember(key) { SharedPreviewCache.get(key) }
    // Otherwise the note's card has almost always decoded its thumbnail already. It stands in at once,
    // instead of a spinner, until the sharper picture is ready.
    var bitmap by remember(key) { mutableStateOf(cached ?: SharedThumbnailCache.get(key)) }
    var failed by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key) {
        if (cached != null) return@LaunchedEffect
        val startedIn = SharedPreviewCache.currentGeneration
        // Decoding a photo takes long enough to drop frames, so it never runs on the main thread.
        val decoded = loadPreviewBytes(attachment)?.let { bytes ->
            withContext(Dispatchers.Default) { decodeAttachmentPreview(bytes, AttachmentPreviewMaxWidthPx) }
        }
        when {
            // Refused only if the account's data was cleared while this loaded: not this library's picture.
            decoded != null -> if (SharedPreviewCache.put(key, decoded, startedIn)) bitmap = decoded
            // A thumbnail already showing is better than the failure icon.
            bitmap == null -> failed = true
        }
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
                        // It says the image failed, so it is meaningful: the icon tier, for 3:1.
                        tint = contentColor.copy(alpha = NoteEmphasis.Icon),
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
        // 48dp touch target around the 32dp chip: centring the chip in it leaves the same 8dp
        // (Spacing.sm) inset from the corner the chip had before.
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp),
        ) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = MaterialTheme.shapes.small,
                color = Color.Black.copy(alpha = 0.55f),
            ) {
                Box(contentAlignment = Alignment.Center) {
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
}

/**
 * Widest the editor decodes a picture. The editor is never wider than a phone or a desktop column, so
 * the pixels of a 4000 px photo beyond this would only cost memory and decode time.
 */
internal const val AttachmentPreviewMaxWidthPx = 1280

/**
 * Pictures already decoded for the editor, so reopening a note shows them at once. Few entries: each is
 * far larger than a card thumbnail. Cleared with the thumbnails when the signed-in account changes.
 */
internal val SharedPreviewCache = AttachmentThumbnailCache(maxEntries = 6)

/**
 * Decodes [bytes] to roughly [maxWidthPx] wide (never narrower than the picture allows) without first
 * holding the full-size bitmap of a large photo. Keeps the picture's own aspect. `null` for non-images.
 */
expect fun decodeAttachmentPreview(bytes: ByteArray, maxWidthPx: Int): ImageBitmap?
