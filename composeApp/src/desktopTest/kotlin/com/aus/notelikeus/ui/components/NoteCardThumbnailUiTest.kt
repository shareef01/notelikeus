package com.aus.notelikeus.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The real [NoteCard] with a real picture, rendered and read back as pixels.
 *
 * The decoder and the cache have their own tests; these answer the question a user would ask: does an
 * image note's card actually show the picture, in both layouts, and does a note without one stay as it was?
 */
@OptIn(ExperimentalTestApi::class)
class NoteCardThumbnailUiTest {

    @BeforeTest
    fun freshCache() = SharedThumbnailCache.clear()

    /** Three retries, milliseconds apart, so a card gives up in well under a second of real time. */
    private val fastRetries = List(3) { 20.milliseconds }

    private val redPicture: ByteArray = run {
        val image = BufferedImage(900, 1800, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 1800) for (x in 0 until 900) image.setRGB(x, y, Color.RED.rgb)
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun note(vararg attachments: Attachment) = Note(
        id = 1L,
        title = "Receipts",
        content = "",
        timestamp = 0L,
        color = 0,
        attachments = attachments.toList(),
    )

    private fun picture(id: String = "pic", mimeType: String? = "image/png") =
        Attachment(id = id, noteId = 1L, storagePath = "r2:owners/o/notes/1/$id", mimeType = mimeType)

    private fun androidx.compose.ui.test.ComposeUiTest.render(
        note: Note,
        loader: AttachmentThumbnailLoader?,
        listStyle: Boolean,
    ) = setContent {
        MaterialTheme {
            CompositionLocalProvider(
                LocalAttachmentThumbnailLoader provides loader,
                LocalThumbnailRetryDelays provides fastRetries,
            ) {
                NoteCard(
                    note = note,
                    isSelected = false,
                    listStyle = listStyle,
                    onClick = {},
                    onLongClick = {},
                )
            }
        }
    }

    // The card merges its children's semantics into one node for screen readers, so the tagged
    // thumbnail is only visible in the unmerged tree.
    private fun androidx.compose.ui.test.ComposeUiTest.thumbnails() =
        onAllNodesWithTag(NoteCardThumbnailTag, useUnmergedTree = true).fetchSemanticsNodes()

    /** Waits for the picture to be drawn, then reports whether the thumbnail's centre is red. */
    private fun androidx.compose.ui.test.ComposeUiTest.thumbnailShowsPicture(): Boolean {
        repeat(100) {
            waitForIdle()
            val image = onNodeWithTag(NoteCardThumbnailTag, useUnmergedTree = true).captureToImage()
            val pixels = IntArray(image.width * image.height)
            image.readPixels(pixels, 0, 0, image.width, image.height)
            val argb = pixels[(image.height / 2) * image.width + image.width / 2]
            if (((argb shr 16) and 0xff) > 200 && (argb and 0xff) < 80) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun `a grid card shows the picture of an image note`() = runComposeUiTest {
        render(note(picture()), loader = { redPicture }, listStyle = false)

        waitUntil(timeoutMillis = 5_000) { thumbnails().isNotEmpty() }
        assertTrue(thumbnailShowsPicture(), "the card should show the picture, not just a placeholder")
    }

    @Test
    fun `the grid thumbnail is a 4 to 3 banner the full width of the card`() = runComposeUiTest {
        render(note(picture()), loader = { redPicture }, listStyle = false)

        waitUntil(timeoutMillis = 5_000) { thumbnails().isNotEmpty() }
        val box = thumbnails().single().size
        assertEquals(4f / 3f, box.width.toFloat() / box.height, absoluteTolerance = 0.02f)
    }

    @Test
    fun `a list row shows the picture as a square beside the text`() = runComposeUiTest {
        render(note(picture()), loader = { redPicture }, listStyle = true)

        waitUntil(timeoutMillis = 5_000) { thumbnails().isNotEmpty() }
        val box = thumbnails().single().size
        assertEquals(box.width, box.height, "list thumbnails are square")
        assertTrue(thumbnailShowsPicture(), "the row should show the picture")
    }

    @Test
    fun `a note without images has no thumbnail`() = runComposeUiTest {
        render(note(), loader = { redPicture }, listStyle = false)

        waitForIdle()
        assertEquals(0, thumbnails().size)
    }

    @Test
    fun `a non-image attachment gets no thumbnail and is never loaded`() = runComposeUiTest {
        var loads = 0
        render(
            note(picture(mimeType = "application/pdf")),
            loader = { loads += 1; redPicture },
            listStyle = false,
        )

        waitForIdle()
        assertEquals(0, thumbnails().size)
        assertEquals(0, loads)
    }

    @Test
    fun `without a loader the card shows no thumbnail`() = runComposeUiTest {
        render(note(picture()), loader = null, listStyle = false)

        waitForIdle()
        assertEquals(0, thumbnails().size)
    }

    @Test
    fun `a picture that cannot be loaded leaves no empty box behind`() = runComposeUiTest {
        render(note(picture()), loader = { null }, listStyle = false)

        waitUntil(timeoutMillis = 5_000) { thumbnails().isEmpty() }
        assertEquals(0, thumbnails().size)
    }

    @Test
    fun `bytes that are not an image leave no empty box behind`() = runComposeUiTest {
        render(note(picture()), loader = { byteArrayOf(1, 2, 3) }, listStyle = false)

        waitUntil(timeoutMillis = 5_000) { thumbnails().isEmpty() }
        assertEquals(0, thumbnails().size)
    }

    @Test
    fun `after the cache is cleared the same picture is loaded again`() = runComposeUiTest {
        var loads = 0
        val loader = AttachmentThumbnailLoader { loads += 1; redPicture }

        render(note(picture()), loader = loader, listStyle = false)
        waitUntil(timeoutMillis = 5_000) { thumbnails().isNotEmpty() }
        assertTrue(thumbnailShowsPicture())
        assertEquals(1, loads)

        clearAttachmentThumbnailCache() // sign-out, or another account signing in

        render(note(picture()), loader = loader, listStyle = false)
        waitUntil(timeoutMillis = 5_000) { loads == 2 }
        assertEquals(2, loads, "a picture kept past a clear would be served to the next account")
    }

    @Test
    fun `a fetch that fails a few times is retried and the picture then shows`() = runComposeUiTest {
        var loads = 0
        val loader = AttachmentThumbnailLoader { loads += 1; if (loads <= 2) null else redPicture }

        render(note(picture()), loader = loader, listStyle = false)

        waitUntil(timeoutMillis = 5_000) { loads >= 3 }
        assertTrue(thumbnailShowsPicture(), "after two failures the third fetch should show the picture")
        assertEquals(3, loads)
    }

    @Test
    fun `a fetch that never succeeds is retried a bounded number of times and then given up`() = runComposeUiTest {
        var loads = 0
        render(note(picture()), loader = { loads += 1; null }, listStyle = false)

        waitUntil(timeoutMillis = 5_000) { thumbnails().isEmpty() }
        assertEquals(1 + fastRetries.size, loads, "one attempt, then one per retry delay, then it stops")
    }

    @Test
    fun `bytes that are not an image are not fetched again`() = runComposeUiTest {
        var loads = 0
        render(note(picture()), loader = { loads += 1; byteArrayOf(1, 2, 3) }, listStyle = false)

        waitUntil(timeoutMillis = 5_000) { thumbnails().isEmpty() }
        // Time for any retry to have happened, were there going to be one.
        Thread.sleep(300)
        waitForIdle()
        assertEquals(1, loads, "downloading the same non-image again cannot change the answer")
    }

    @Test
    fun `clearing the cache while a card waits to retry stops the retries`() = runComposeUiTest {
        var loads = 0
        val loader = AttachmentThumbnailLoader {
            loads += 1
            clearAttachmentThumbnailCache() // the account changed while this was loading
            null
        }
        render(note(picture()), loader = loader, listStyle = false)

        waitUntil(timeoutMillis = 5_000) { loads >= 1 }
        Thread.sleep(300)
        waitForIdle()
        assertEquals(1, loads, "a picture of a signed-out account must not be fetched again")
    }

    @Test
    fun `a second card for the same picture does not load it again`() = runComposeUiTest {
        var loads = 0
        val loader = AttachmentThumbnailLoader { loads += 1; redPicture }

        render(note(picture()), loader = loader, listStyle = false)
        waitUntil(timeoutMillis = 5_000) { thumbnails().isNotEmpty() }
        thumbnailShowsPicture()
        assertEquals(1, loads)

        // A fresh composition of the same attachment — scrolled away and back — is served from memory.
        val again = SharedThumbnailCache.get("pic|r2:owners/o/notes/1/pic")
        assertTrue(again != null, "the decoded picture should have been kept")
    }
}
