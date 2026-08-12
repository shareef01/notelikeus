package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.remote.toCloudMap
import com.aus.notelikeus.data.remote.syncMetaMap
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteCloudMapperTest {

    @Test
    fun `toCloudMap includes note fields and nested data`() {
        val note = Note(
            id = 42L,
            title = "Groceries",
            content = "Milk",
            timestamp = 1000L,
            color = -1,
            isPinned = true,
            checklist = listOf(
                ChecklistItem(text = "Milk", isChecked = false, position = 0)
            ),
            labels = listOf(Label(id = 1L, name = "Home"))
        )

        val map = note.toCloudMap()

        assertEquals(42L, map["localId"])
        assertEquals("Groceries", map["title"])
        assertEquals(true, map["isPinned"])
        @Suppress("UNCHECKED_CAST")
        val checklist = map["checklist"] as List<Map<String, Any>>
        assertEquals("Milk", checklist.first()["text"])
        @Suppress("UNCHECKED_CAST")
        val labels = map["labels"] as List<Map<String, String>>
        assertEquals("Home", labels.first()["name"])
    }

    @Test
    fun `syncMetaMap includes note count and platform`() {
        val meta = syncMetaMap(5, "android")
        assertEquals(5, meta["noteCount"])
        assertEquals("android", meta["platform"])
        assertTrue((meta["lastSyncAt"] as Long) > 0L)
    }
}
