package com.aus.notelikeus.ui.main.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.model.ThemeBase
import com.aus.notelikeus.domain.model.ThemePreference
import com.aus.notelikeus.ui.theme.colorSchemeFor
import com.aus.notelikeus.ui.main.ConflictChoice
import com.aus.notelikeus.ui.main.ConflictReview
import com.aus.notelikeus.ui.main.ConflictReviewState
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ConflictReviewDialogUiTest {

    private val original = Note(id = 1L, title = "Groceries", content = "milk\neggs (from phone)\ncoffee", timestamp = 1_791_990_000_000L, color = 0)
    private val copy = Note(
        id = 2L, title = "Groceries (conflicted copy)", content = "milk\nbread (typed here)\ncoffee",
        timestamp = 1_791_993_600_000L, color = 0,
    )

    private fun ComposeUiTest.show(review: ConflictReviewState, dark: Boolean = false, onChoice: (ConflictChoice) -> Unit = {}) {
        setContent {
            // The app's own schemes, so a saved shot looks like the app.
            val base = if (dark) ThemeBase.DARK else ThemeBase.LIGHT
            MaterialTheme(colorScheme = colorSchemeFor(ThemePreference(base = base), systemDark = dark)) {
                ConflictReviewDialog(review = review, onChoice = onChoice, onDismiss = {})
            }
        }
        waitForIdle()
    }

    /** Writes the dialog as rendered, when NOTELIKEUS_SHOTS_DIR is set, for a human to look at. */
    private fun ComposeUiTest.saveShot(name: String) {
        val dir = System.getenv("NOTELIKEUS_SHOTS_DIR") ?: return
        val image = onAllNodes(isRoot()).onLast().captureToImage().toAwtImage()
        ImageIO.write(image, "png", File(dir, "$name.png").apply { parentFile.mkdirs() })
    }

    @Test
    fun `shows both versions and marks the lines only one has`() = runComposeUiTest {
        show(ConflictReviewState(copy, original))

        onNodeWithText("This note changed on two devices").assertExists()
        onNodeWithText("CURRENT NOTE").assertExists()
        onNodeWithText("CONFLICTED COPY").assertExists()
        // The differing lines are announced as such, not only tinted.
        assertEquals(1, onAllNodesWithContentDescription("eggs (from phone) (only in this version)").fetchSemanticsNodes().size)
        assertEquals(1, onAllNodesWithContentDescription("bread (typed here) (only in this version)").fetchSemanticsNodes().size)
        saveShot("compose-review-light")
    }

    @Test
    fun `each choice reaches the handler`() = runComposeUiTest {
        val chosen = mutableListOf<ConflictChoice>()
        show(ConflictReviewState(copy, original), dark = true) { chosen += it }
        saveShot("compose-review-dark")

        onNodeWithText("Keep conflicted copy").performClick()
        onNodeWithText("Keep current note").performClick()
        onNodeWithText("Keep both").performClick()

        assertEquals(listOf(ConflictChoice.KEEP_COPY, ConflictChoice.KEEP_CURRENT, ConflictChoice.KEEP_BOTH), chosen)
    }

    @Test
    fun `without a single original it says so and offers only the copy`() = runComposeUiTest {
        val chosen = mutableListOf<ConflictChoice>()
        show(ConflictReviewState(copy, original = null)) { chosen += it }

        onNodeWithText("Keep conflicted copy").assertDoesNotExist()
        onNodeWithText("Move copy to Trash").performClick()

        assertEquals(listOf(ConflictChoice.KEEP_CURRENT), chosen)
        assertEquals(null, ConflictReview.findOriginal(copy, listOf(copy)))
    }
}
