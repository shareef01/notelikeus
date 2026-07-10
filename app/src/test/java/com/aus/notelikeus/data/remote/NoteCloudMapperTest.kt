package com.aus.notelikeus.data.remote



import com.aus.notelikeus.data.remote.toCloudMap

import com.aus.notelikeus.data.remote.toCloudNote

import com.aus.notelikeus.data.remote.syncMetaMap

import com.aus.notelikeus.domain.model.ChecklistItem

import com.aus.notelikeus.domain.model.Label

import com.aus.notelikeus.domain.model.Note

import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals

import org.junit.Assert.assertFalse

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



        val cloudId = map["cloudId"] as String
        assertEquals(42L, map["localId"])
        assertTrue(CloudIds.isValid(cloudId))

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

    fun `toCloudNote roundtrips core fields`() = runTest {

        val original = Note(

            id = 7L,

            title = "Trip",

            content = "Pack bags",

            timestamp = 900L,

            color = 3,

            isPinned = true,

            checklist = listOf(ChecklistItem(text = "Passport", isChecked = true, position = 0)),

            labels = listOf(Label(id = 2L, name = "Travel"))

        )

        val cloud = original.toCloudMap()
        val cloudId = cloud["cloudId"] as String

        val restored = cloud.toCloudNote(7L, cloudId) { Label(id = 2L, name = it) }



        assertEquals("Trip", restored.title)

        assertEquals("Pack bags", restored.content)

        assertEquals(true, restored.isPinned)

        assertEquals("Passport", restored.checklist.first().text)

        assertEquals("Travel", restored.labels.first().name)

        assertTrue(restored.attachments.isEmpty())

    }



    @Test

    fun `isCloudSyncEligible excludes locked notes`() {

        val locked = Note(

            id = 1L,

            title = "Secret",

            content = "",

            timestamp = 1L,

            color = 0,

            isLocked = true

        )

        val unlocked = Note(

            id = 2L,

            title = "Open",

            content = "",

            timestamp = 2L,

            color = 0

        )



        assertFalse(locked.isCloudSyncEligible())

        assertTrue(unlocked.isCloudSyncEligible())

    }



    @Test

    fun `syncMetaMap includes note count`() {

        val meta = syncMetaMap(5)

        assertEquals(5, meta["noteCount"])

        assertEquals("android", meta["platform"])

        assertTrue((meta["lastSyncAt"] as Long) > 0L)

    }

}

