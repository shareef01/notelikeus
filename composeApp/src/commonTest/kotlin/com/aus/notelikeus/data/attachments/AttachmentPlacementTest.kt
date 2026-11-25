package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.domain.model.Attachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AttachmentPlacementTest {
    @Test
    fun positionsUseScalarsAndKeepPictureOrder() {
        val body = "First 🧭\nSecond"
        val selection = "First 🧭".length
        val placement = assertNotNull(PlacementBody(body).placementAfter(selection))
        assertEquals(8L, placement.offset)
        val pictures = listOf(
            Attachment("later", 1, "pending:later", placement = placement.copy(ordinal = 1)),
            Attachment("earlier", 1, "pending:earlier", placement = placement),
        )
        val positioned = positionedAttachments(body, pictures)
        assertEquals(listOf("earlier", "later"), positioned.map { it.second.id })
        assertTrue(positioned.all { it.first == selection + 1 })
    }

    @Test
    fun textEditsKeepSurvivingPositionsAndUndoRestoresRemovedBoundaries() {
        val body = "First 🧭\nSecond"
        val revised = "First 🧭 revised\nSecond"
        val merged = "First 🧭 revisedSecond"
        val placement = PlacementBody(body).placementAfter(0)
        val images = listOf(Attachment("image", 1, "pending:image", placement = placement))
        val moved = rebaseAttachmentPlacements(images, body, revised)
        assertEquals(revised.indexOf('\n') + 1, positionedAttachments(revised, moved).single().first)
        val hidden = rebaseAttachmentPlacements(moved, revised, merged)
        assertEquals(moved, hidden)
        assertTrue(positionedAttachments(merged, hidden).isEmpty())
        val restored = rebaseAttachmentPlacements(hidden, merged, revised)
        assertEquals(moved, restored)
        assertEquals(1, positionedAttachments(revised, restored).size)
    }
}
