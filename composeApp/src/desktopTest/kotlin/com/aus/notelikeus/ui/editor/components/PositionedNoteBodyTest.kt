package com.aus.notelikeus.ui.editor.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.aus.notelikeus.data.attachments.PlacementBody
import com.aus.notelikeus.data.attachments.positionedAttachments
import com.aus.notelikeus.domain.model.Attachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PositionedNoteBodyTest {
    @Test
    fun imageControlsAreBetweenTextBlocksAndCanReturnTheImageToTheStrip() = runComposeUiTest {
        val body = "**First 🧭**\n\nSecond"
        val image = Attachment("image", 1, "pending:image", placement = PlacementBody(body).placementAfter(0))
        var returned: Attachment? = null
        setContent {
            MaterialTheme {
                PositionedNoteBody(
                    body = body,
                    positioned = positionedAttachments(body, listOf(image)),
                    contentColor = Color.Black,
                    loadPreviewBytes = { null },
                    onRemove = {},
                    onUnplace = { returned = it },
                    revealImageId = null,
                )
            }
        }
        val first = onNodeWithText("First 🧭").getUnclippedBoundsInRoot()
        val button = onNodeWithText("Move to attachment strip")
        val action = button.getUnclippedBoundsInRoot()
        val second = onNodeWithText("Second").getUnclippedBoundsInRoot()
        assertTrue(first.bottom < action.top && action.bottom < second.top)
        button.performClick()
        assertEquals(image, returned)
    }
}
