package com.aus.notelikeus.ui.editor.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.ui.components.AttachmentThumbnailCache
import com.aus.notelikeus.ui.components.SharedThumbnailCache
import com.aus.notelikeus.ui.components.ConfirmDialog
import com.aus.notelikeus.ui.components.attachmentImageKey
import com.aus.notelikeus.ui.theme.NoteEmphasis
import com.aus.notelikeus.ui.theme.Size
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.util.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.cd_attachment
import notelikeus.composeapp.generated.resources.cd_remove_attachment
import notelikeus.composeapp.generated.resources.image_load_failed
import notelikeus.composeapp.generated.resources.cd_open_attachment
import notelikeus.composeapp.generated.resources.cd_window_close
import notelikeus.composeapp.generated.resources.image_viewer_fit
import notelikeus.composeapp.generated.resources.image_viewer_fit_label
import notelikeus.composeapp.generated.resources.image_viewer_fit_label_at_zoom
import notelikeus.composeapp.generated.resources.image_viewer_zoom_in
import notelikeus.composeapp.generated.resources.image_viewer_zoom_out
import notelikeus.composeapp.generated.resources.remove_attachment_message

@Composable
fun AttachmentImageStrip(
    attachments: List<Attachment>,
    contentColor: Color,
    loadPreviewBytes: suspend (Attachment) -> ByteArray?,
    onRemove: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: ((Attachment) -> Unit)? = null,
) {
    if (attachments.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        attachments.forEach { attachment ->
            key(attachmentImageKey(attachment)) {
                AttachmentImagePreview(
                    attachment = attachment,
                    contentColor = contentColor,
                    loadPreviewBytes = loadPreviewBytes,
                    onRemove = { onRemove(attachment) },
                    modifier = Modifier.padding(bottom = if (onAction == null) Spacing.lg else Spacing.xs),
                )
                if (actionText != null && onAction != null) {
                    TextButton(onClick = { onAction(attachment) }, modifier = Modifier.padding(bottom = Spacing.md)) {
                        Text(actionText, color = contentColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentImagePreview(
    attachment: Attachment,
    contentColor: Color,
    loadPreviewBytes: suspend (Attachment) -> ByteArray?,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
    val key = attachmentImageKey(attachment)
    // Opened earlier this session: the decoded preview is still held, and nothing is fetched again.
    val cached = remember(key) { SharedPreviewCache.get(key) }
    // Otherwise the note's card has almost always decoded its thumbnail already. It stands in at once,
    // instead of a spinner, until the sharper picture is ready.
    var bitmap by remember(key) { mutableStateOf(cached ?: SharedThumbnailCache.get(key)) }
    var failed by remember(key) { mutableStateOf(false) }
    var viewing by remember(key) { mutableStateOf(false) }
    var confirmRemoval by remember(key) { mutableStateOf(false) }

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

    val preview = bitmap
    val openLabel = stringResource(Res.string.cd_open_attachment)
    val removeLabel = stringResource(Res.string.cd_remove_attachment)
    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        when {
            preview != null -> {
                AttachmentPreviewImage(preview, openLabel) { viewing = true }
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
        AttachmentRemoveButton(failed, onRemove, { confirmRemoval = true }, Modifier.align(Alignment.TopEnd))
    }
    if (viewing && preview != null) {
        AttachmentViewer(
            preview = preview,
            removeLabel = removeLabel,
            onDismiss = { viewing = false },
            onRemove = { confirmRemoval = true },
        )
    }
    if (confirmRemoval) {
        ConfirmDialog(
            title = removeLabel,
            message = stringResource(Res.string.remove_attachment_message),
            confirmLabel = removeLabel,
            onConfirm = {
                confirmRemoval = false
                viewing = false
                onRemove()
            },
            onDismiss = { confirmRemoval = false },
            destructive = true,
        )
    }
}

@Composable
private fun AttachmentRemoveButton(
    failed: Boolean,
    onRemove: () -> Unit,
    onConfirmRemoval: () -> Unit,
    modifier: Modifier,
) {
    if (!AppConfig.isDesktop && !failed) return
    IconButton(
        onClick = if (AppConfig.isDesktop) onRemove else onConfirmRemoval,
        modifier = modifier.size(Size.touchTarget),
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
                    modifier = Modifier.size(Size.iconMedium),
                )
            }
        }
    }
}

@Composable
private fun AttachmentPreviewImage(preview: ImageBitmap, openLabel: String, onOpen: () -> Unit) {
    val aspect = preview.width.toFloat() / preview.height
    val imageLayout = if (AppConfig.isDesktop) {
        Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(MaterialTheme.shapes.medium)
    } else {
        Modifier
            .widthIn(max = 320.dp * aspect)
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen)
    }
    Image(
        bitmap = preview,
        contentDescription = stringResource(Res.string.cd_attachment),
        modifier = imageLayout,
        contentScale = if (AppConfig.isDesktop) ContentScale.Crop else ContentScale.Fit,
    )
}

@Composable
private fun AttachmentViewer(
    preview: ImageBitmap,
    removeLabel: String,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
) {
    var zoom by remember { mutableStateOf(AttachmentZoom()) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val image = IntSize(preview.width, preview.height)
    Dialog(
        onDismissRequest = onDismiss,
        properties = attachmentViewerDialogProperties(),
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            AttachmentZoomControls(
                zoom = zoom,
                onZoom = { zoom = zoom.transform(viewport, image, it) },
                onDismiss = onDismiss,
            )
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()
                    .onSizeChanged {
                        viewport = it
                        zoom = zoom.transform(it, image, zoom.scale)
                    }
                    .pointerInput(image) {
                        detectTransformGestures(panZoomLock = true) { centroid, pan, scale, _ ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            zoom = zoom.transform(size, image, zoom.scale * scale, centroid - center, pan)
                        }
                    },
            ) {
                Image(
                    bitmap = preview,
                    contentDescription = stringResource(Res.string.cd_attachment),
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.offset.x
                        translationY = zoom.offset.y
                    },
                    contentScale = ContentScale.Fit,
                )
            }
            TextButton(onClick = onRemove, modifier = Modifier.align(Alignment.End)) {
                Text(removeLabel, color = Color.White)
            }
        }
    }
}

internal expect fun attachmentViewerDialogProperties(): DialogProperties

@Composable
private fun AttachmentZoomControls(zoom: AttachmentZoom, onZoom: (Float) -> Unit, onDismiss: () -> Unit) {
    val fitted = zoom.scale == AttachmentZoom.Fit
    val percent = "${(zoom.scale * 100).roundToInt()}%"
    val fitLabel = if (fitted) {
        stringResource(Res.string.image_viewer_fit_label)
    } else {
        stringResource(Res.string.image_viewer_fit_label_at_zoom, percent)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onZoom(zoom.step(false)) }, enabled = !fitted) {
            Icon(
                Icons.Default.Remove,
                stringResource(Res.string.image_viewer_zoom_out),
                tint = Color.White.copy(alpha = if (fitted) 0.4f else 1f),
            )
        }
        TextButton(
            onClick = { onZoom(AttachmentZoom.Fit) },
            enabled = !fitted,
            modifier = Modifier.semantics { contentDescription = fitLabel },
        ) {
            Text(
                if (fitted) stringResource(Res.string.image_viewer_fit) else percent,
                color = Color.White.copy(alpha = if (fitted) 0.6f else 1f),
            )
        }
        val canZoomIn = zoom.scale < AttachmentZoom.Maximum
        IconButton(onClick = { onZoom(zoom.step(true)) }, enabled = canZoomIn) {
            Icon(
                Icons.Default.Add,
                stringResource(Res.string.image_viewer_zoom_in),
                tint = Color.White.copy(alpha = if (canZoomIn) 1f else 0.4f),
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, stringResource(Res.string.cd_window_close), tint = Color.White)
        }
    }
}

/**
 * Maximum preview edge on Android, and maximum width on the legacy desktop. The editor is
 * the pixels of a 4000 px photo beyond this would only cost memory and decode time.
 */
internal const val AttachmentPreviewMaxWidthPx = 1280

/**
 * Pictures already decoded for the editor, so reopening a note shows them at once. Few entries: each is
 * far larger than a card thumbnail. Cleared with the thumbnails when the signed-in account changes.
 */
internal val SharedPreviewCache = AttachmentThumbnailCache(maxEntries = 6)

/**
 * Decodes [bytes] to at most [maxWidthPx] wide, and bounds the height on Android too, without first
 * holding the full-size bitmap of a large photo. Keeps the picture's own aspect. `null` for non-images.
 */
expect fun decodeAttachmentPreview(bytes: ByteArray, maxWidthPx: Int): ImageBitmap?
