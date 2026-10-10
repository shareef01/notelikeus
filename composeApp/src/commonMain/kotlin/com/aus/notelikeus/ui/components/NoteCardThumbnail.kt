package com.aus.notelikeus.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import com.aus.notelikeus.domain.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Where a note card gets an attachment's bytes from.
 *
 * Provided once at the app root so the cards need no service of their own, and absent in previews
 * and tests, where [LocalAttachmentThumbnailLoader] stays `null` and cards simply show no picture.
 */
fun interface AttachmentThumbnailLoader {
    suspend fun load(attachment: Attachment): ByteArray?
}

/** Test tag on a card's thumbnail, so tests can find it without giving the picture an accessibility label. */
const val NoteCardThumbnailTag = "note-card-thumbnail"

val LocalAttachmentThumbnailLoader = staticCompositionLocalOf<AttachmentThumbnailLoader?> { null }

/** Width and height of the stored thumbnail: 4:3, the shape cards crop to, so no unseen pixels are kept. */
const val NoteThumbnailWidthPx = 480
const val NoteThumbnailHeightPx = 360

/** The first attachment on a note that is a picture — what stands for the note on its card. */
fun Iterable<Attachment>.firstImageAttachment(): Attachment? =
    firstOrNull { it.mimeType == null || it.mimeType.startsWith("image/") }

/**
 * Decodes [bytes] straight to at most [maxWidthPx] x [maxHeightPx], cropped to that aspect.
 *
 * A grid of cards must not hold full-size bitmaps: a 1080x2400 screenshot is about 10 MB decoded, and
 * a screen of cards would run a phone out of memory. Portrait images are cropped from the top (a
 * screenshot reads from the top down), landscape ones from the centre. Returns `null` for bytes
 * that are not an image.
 */
expect fun decodeAttachmentThumbnail(bytes: ByteArray, maxWidthPx: Int, maxHeightPx: Int): ImageBitmap?

/**
 * Decoded thumbnails, newest-used last, bounded so scrolling a long library cannot grow without limit.
 *
 * Only the main thread touches this: the composable reads and writes it from its effect, and the
 * decode itself runs elsewhere and hands its result back.
 */
internal class AttachmentThumbnailCache(private val maxEntries: Int = DefaultMaxEntries) {
    private val entries = LinkedHashMap<String, ImageBitmap>()
    private val failures = HashMap<String, TimeMark>()

    fun get(key: String): ImageBitmap? {
        val bitmap = entries.remove(key) ?: return null
        entries[key] = bitmap // re-insert: now the most recently used
        return bitmap
    }

    fun put(key: String, bitmap: ImageBitmap) {
        entries.remove(key)
        entries[key] = bitmap
        failures.remove(key)
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
    }

    /** Remembers that [key] could not be loaded, so scrolling it in and out of view does not retry it each time. */
    fun markFailed(key: String) {
        failures[key] = TimeSource.Monotonic.markNow()
    }

    fun recentlyFailed(key: String): Boolean {
        val mark = failures[key] ?: return false
        if (mark.elapsedNow() < FailureMemory) return true
        failures.remove(key)
        return false
    }

    fun clear() {
        entries.clear()
        failures.clear()
    }

    val size: Int get() = entries.size

    companion object {
        /** 48 x 480 x 360 x 4 bytes is roughly 33 MB at the very most; typical cards are a fraction of that. */
        const val DefaultMaxEntries = 48
        val FailureMemory = 60.seconds
    }
}

internal val SharedThumbnailCache = AttachmentThumbnailCache()

/** Loads at most this many pictures at once, so a screen of image notes does not open dozens of downloads. */
private val LoadSlots = Semaphore(permits = 3)

private fun thumbnailKey(attachment: Attachment) = "${attachment.id}|${attachment.storagePath}"

/**
 * The picture for one card.
 *
 * Decorative: the card's own description already covers the note, and announcing every picture
 * would only repeat it. Until the bitmap arrives it reserves its space (the caller sizes it), so a
 * grid does not jump as pictures land; and a picture that cannot be loaded draws nothing.
 */
@Composable
fun NoteCardThumbnail(attachment: Attachment, modifier: Modifier = Modifier) {
    val loader = LocalAttachmentThumbnailLoader.current ?: return
    val key = thumbnailKey(attachment)
    var bitmap by remember(key) { mutableStateOf(SharedThumbnailCache.get(key)) }
    var failed by remember(key) { mutableStateOf(SharedThumbnailCache.recentlyFailed(key)) }

    LaunchedEffect(key, loader) {
        if (bitmap != null || failed) return@LaunchedEffect
        val decoded = LoadSlots.withPermit {
            val bytes = loader.load(attachment)
            bytes?.let {
                withContext(Dispatchers.Default) {
                    decodeAttachmentThumbnail(it, NoteThumbnailWidthPx, NoteThumbnailHeightPx)
                }
            }
        }
        if (decoded == null) {
            SharedThumbnailCache.markFailed(key)
            failed = true
        } else {
            SharedThumbnailCache.put(key, decoded)
            bitmap = decoded
        }
    }

    if (failed) return
    val loaded = bitmap
    Box(
        modifier = modifier
            .testTag(NoteCardThumbnailTag)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    ) {
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
