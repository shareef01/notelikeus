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
import com.aus.notelikeus.util.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
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

/**
 * How long to wait before each retry of a picture that could not be fetched. A picture that fails once
 * is usually a transient problem (a slow first start after an update, a network blip, a token that was
 * still refreshing), and without a retry the card stayed blank for the rest of the session. The list is
 * the retry count: after the last delay one final attempt is made, and then the card gives up.
 *
 * A composition local so tests can use milliseconds; production reads the default.
 */
val LocalThumbnailRetryDelays = staticCompositionLocalOf { listOf(2.seconds, 5.seconds, 10.seconds) }

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
 * The map is only touched from the main thread — the composable reads and writes it from its effect,
 * and the decode itself runs elsewhere and hands its result back. [clear] is the exception: it is
 * called when the signed-in account changes, from wherever that happens, so it must not touch the map.
 * It advances a generation instead. Every entry and every failure carries the generation it was made
 * in, anything older reads as absent, and the main thread drops it on the next access. A load that
 * began before a clear presents the generation it started under and is refused when it finishes, so
 * one account's pictures can neither be served to the next nor slip back in after the clear.
 */
internal class AttachmentThumbnailCache(private val maxEntries: Int = DefaultMaxEntries) {
    private class Entry(val bitmap: ImageBitmap, val generation: Int)
    private class Failure(val at: TimeMark, val generation: Int)

    private val entries = LinkedHashMap<String, Entry>()
    private val failures = HashMap<String, Failure>()

    @Volatile
    private var generation = 0

    /** The generation to hand back to [put] and [markFailed] for a load that starts now. */
    val currentGeneration: Int get() = generation

    fun get(key: String): ImageBitmap? {
        val entry = entries.remove(key) ?: return null
        if (entry.generation != generation) return null
        entries[key] = entry // re-insert: now the most recently used
        return entry.bitmap
    }

    /**
     * Stores [bitmap] unless the cache was cleared since the load that produced it began ([startedIn]).
     * Returns whether it was kept.
     */
    fun put(key: String, bitmap: ImageBitmap, startedIn: Int = generation): Boolean {
        if (startedIn != generation) return false
        dropStale()
        entries.remove(key)
        entries[key] = Entry(bitmap, startedIn)
        failures.remove(key)
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
        return true
    }

    /** Remembers that [key] could not be loaded, so scrolling it in and out of view does not retry it each time. */
    fun markFailed(key: String, startedIn: Int = generation) {
        if (startedIn != generation) return
        failures[key] = Failure(TimeSource.Monotonic.markNow(), startedIn)
    }

    fun recentlyFailed(key: String): Boolean {
        val failure = failures[key] ?: return false
        if (failure.generation != generation || failure.at.elapsedNow() >= FailureMemory) {
            failures.remove(key)
            return false
        }
        return true
    }

    /** Safe from any thread. Everything stored so far stops being served, and is released on the next access. */
    fun clear() {
        generation += 1
    }

    val size: Int get() = entries.values.count { it.generation == generation }

    private fun dropStale() {
        if (entries.values.any { it.generation != generation }) {
            entries.entries.removeAll { it.value.generation != generation }
        }
        if (failures.values.any { it.generation != generation }) {
            failures.entries.removeAll { it.value.generation != generation }
        }
    }

    companion object {
        /** 48 x 480 x 360 x 4 bytes is roughly 33 MB at the very most; typical cards are a fraction of that. */
        const val DefaultMaxEntries = 48
        val FailureMemory = 60.seconds
    }
}

internal val SharedThumbnailCache = AttachmentThumbnailCache()

/**
 * Forgets every decoded thumbnail. Called when the signed-in account changes: thumbnails are decoded
 * copies of a library's pictures, and one account's must not stay reachable for the next. Safe to call
 * from any thread.
 */
fun clearAttachmentThumbnailCache() = SharedThumbnailCache.clear()

/** Loads at most this many pictures at once, so a screen of image notes does not open dozens of downloads. */
private val LoadSlots = Semaphore(permits = 3)

private fun thumbnailKey(attachment: Attachment) = "${attachment.id}|${attachment.storagePath}"

/**
 * The picture for one card.
 *
 * Decorative: the card's own description already covers the note, and announcing every picture
 * would only repeat it. Until the bitmap arrives it reserves its space (the caller sizes it), so a
 * grid does not jump as pictures land. A fetch that fails is retried a few times, after a delay (see
 * [LocalThumbnailRetryDelays]); a picture that still cannot be loaded, or that is not an image, draws
 * nothing, and says why in the log.
 */
@Composable
fun NoteCardThumbnail(attachment: Attachment, modifier: Modifier = Modifier) {
    val loader = LocalAttachmentThumbnailLoader.current ?: return
    val key = thumbnailKey(attachment)
    var bitmap by remember(key) { mutableStateOf(SharedThumbnailCache.get(key)) }
    var failed by remember(key) { mutableStateOf(SharedThumbnailCache.recentlyFailed(key)) }

    val retryDelays = LocalThumbnailRetryDelays.current

    LaunchedEffect(key, loader) {
        if (bitmap != null || failed) return@LaunchedEffect
        val startedIn = SharedThumbnailCache.currentGeneration

        fun giveUp(reason: String) {
            // Ids and counts only, never content: this is how a silent blank card gets diagnosed.
            AppLog.warn("NoteCardThumbnail", "Thumbnail for attachment ${attachment.id} $reason")
            SharedThumbnailCache.markFailed(key, startedIn)
            failed = true
        }

        var attempt = 0
        while (true) {
            val (bytes, decoded) = LoadSlots.withPermit {
                val loaded = loader.load(attachment)
                loaded to loaded?.let {
                    withContext(Dispatchers.Default) {
                        decodeAttachmentThumbnail(it, NoteThumbnailWidthPx, NoteThumbnailHeightPx)
                    }
                }
            }
            when {
                decoded != null -> {
                    // Refused only if the account's data was cleared while this loaded: the picture then
                    // belongs to a library that is no longer the one signed in, so it is neither shown nor kept.
                    if (SharedThumbnailCache.put(key, decoded, startedIn)) bitmap = decoded
                    return@LaunchedEffect
                }
                // The bytes arrived but are not an image. Fetching them again cannot change that.
                bytes != null -> return@LaunchedEffect giveUp("downloaded but is not a decodable image")
                attempt >= retryDelays.size ->
                    return@LaunchedEffect giveUp("could not be loaded after ${attempt + 1} attempts")
            }
            // The card keeps its placeholder while it waits, so the grid does not jump.
            delay(retryDelays[attempt])
            attempt += 1
            // The account's data was cleared while waiting: this is no longer anyone's picture to fetch.
            if (SharedThumbnailCache.currentGeneration != startedIn) return@LaunchedEffect
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
