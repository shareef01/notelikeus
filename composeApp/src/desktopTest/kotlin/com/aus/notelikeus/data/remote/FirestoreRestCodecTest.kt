package com.aus.notelikeus.data.remote

import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-format tests for the desktop Firestore REST calls.
 *
 * These exist because both write bugs were invisible from behaviour: a rejected commit and a
 * successful one looked identical to the caller, so the only way to catch a malformed request is
 * to assert its shape.
 */
class FirestoreRestCodecTest {

    private val documentsPath = "projects/notelikeus/databases/(default)/documents"
    private val codec = FirestoreRestCodec(documentsPath)
    private val json = Json { ignoreUnknownKeys = true }

    private fun note(
        id: Long = 7L,
        reminderTimestamp: Long? = null,
        labels: List<Label> = emptyList(),
        checklist: List<ChecklistItem> = emptyList()
    ) = Note(
        id = id,
        title = "Title",
        content = "Body",
        timestamp = 1_700_000_000_000L,
        color = 42,
        isPinned = true,
        isArchived = false,
        isTrashed = false,
        position = 3,
        reminderTimestamp = reminderTimestamp,
        labels = labels,
        attachments = emptyList(),
        checklist = checklist
    )

    // ---- the two shape bugs ----

    @Test
    fun `serverUpdatedAt is a Write transform, not a document field`() {
        val write = codec.noteWrite("uid1", 7L, note())

        val fields = write["update"]!!.jsonObject["fields"]!!.jsonObject
        assertTrue(
            "serverUpdatedAt" !in fields,
            "a Value has no setToServerValue member; putting one in fields gets the commit rejected"
        )

        val transforms = write["updateTransforms"]!!.jsonArray
        assertEquals(1, transforms.size)
        val transform = transforms.first().jsonObject
        assertEquals("serverUpdatedAt", transform["fieldPath"]!!.jsonPrimitive.content)
        assertEquals("REQUEST_TIME", transform["setToServerValue"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deletes address a resource name, never a URL`() {
        val write = codec.deleteWrite("uid1", "notes", 7L)
        val target = write["delete"]!!.jsonPrimitive.content

        assertEquals("$documentsPath/users/uid1/notes/7", target)
        assertTrue(!target.startsWith("http"), "the commit API rejects a full URL here")
    }

    @Test
    fun `tombstone deletes address the tombstones collection`() {
        val write = codec.deleteWrite("uid1", "tombstones", 11L)
        assertEquals(
            "$documentsPath/users/uid1/tombstones/11",
            write["delete"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `note write targets the note document by name`() {
        val write = codec.noteWrite("uid1", 7L, note())
        assertEquals(
            "$documentsPath/users/uid1/notes/7",
            write["update"]!!.jsonObject["name"]!!.jsonPrimitive.content
        )
    }

    // ---- field encoding ----

    @Test
    fun `note fields use the typed Value wrappers Firestore expects`() {
        val fields = codec.noteWrite("uid1", 7L, note())["update"]!!
            .jsonObject["fields"]!!.jsonObject

        assertEquals("7", fields["localId"]!!.jsonObject["integerValue"]!!.jsonPrimitive.content)
        assertEquals("Title", fields["title"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content)
        assertEquals("Body", fields["content"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content)
        assertEquals("42", fields["color"]!!.jsonObject["integerValue"]!!.jsonPrimitive.content)
        assertEquals("3", fields["position"]!!.jsonObject["integerValue"]!!.jsonPrimitive.content)
        assertEquals(true, fields["isPinned"]!!.jsonObject["booleanValue"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, fields["isTrashed"]!!.jsonObject["booleanValue"]!!.jsonPrimitive.content.toBoolean())
        // The rules still accept isLocked and older clients still send it.
        assertEquals(false, fields["isLocked"]!!.jsonObject["booleanValue"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `a null reminder is omitted rather than written as null`() {
        val fields = codec.noteWrite("uid1", 7L, note(reminderTimestamp = null))["update"]!!
            .jsonObject["fields"]!!.jsonObject
        assertTrue("reminderTimestamp" !in fields)

        val withReminder = codec.noteWrite("uid1", 7L, note(reminderTimestamp = 999L))["update"]!!
            .jsonObject["fields"]!!.jsonObject
        assertEquals(
            "999",
            withReminder["reminderTimestamp"]!!.jsonObject["integerValue"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `labels and checklist encode as arrays of maps`() {
        val write = codec.noteWrite(
            "uid1",
            7L,
            note(
                labels = listOf(Label(id = 1L, name = "Work")),
                checklist = listOf(ChecklistItem(text = "Milk", isChecked = true, position = 2))
            )
        )
        val fields = write["update"]!!.jsonObject["fields"]!!.jsonObject

        val label = fields["labels"]!!.jsonObject["arrayValue"]!!.jsonObject["values"]!!
            .jsonArray.single().jsonObject["mapValue"]!!.jsonObject["fields"]!!.jsonObject
        assertEquals("Work", label["name"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content)

        val item = fields["checklist"]!!.jsonObject["arrayValue"]!!.jsonObject["values"]!!
            .jsonArray.single().jsonObject["mapValue"]!!.jsonObject["fields"]!!.jsonObject
        assertEquals("Milk", item["text"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content)
        assertEquals(true, item["isChecked"]!!.jsonObject["booleanValue"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("2", item["position"]!!.jsonObject["integerValue"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sync meta only carries the three fields the rules allow`() {
        val fields = codec.syncMetaBody(5, "desktop", 1234L)["fields"]!!.jsonObject
        assertEquals(setOf("lastSyncAt", "noteCount", "platform"), fields.keys)
        assertEquals("1234", fields["lastSyncAt"]!!.jsonObject["integerValue"]!!.jsonPrimitive.content)
        assertEquals("5", fields["noteCount"]!!.jsonObject["integerValue"]!!.jsonPrimitive.content)
        assertEquals("desktop", fields["platform"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tombstone body only carries deletedAt`() {
        val fields = codec.tombstoneBody(77L)["fields"]!!.jsonObject
        assertEquals(setOf("deletedAt"), fields.keys)
        assertEquals("77", fields["deletedAt"]!!.jsonObject["integerValue"]!!.jsonPrimitive.content)
    }

    // ---- reading documents back ----

    @Test
    fun `parses a note document`() {
        val doc = json.decodeFromString<JsonObject>(
            """
            {
              "name": "$documentsPath/users/uid1/notes/12",
              "fields": {
                "title": {"stringValue": "Hello"},
                "content": {"stringValue": "World"},
                "timestamp": {"integerValue": "1700000000000"},
                "color": {"integerValue": "3"},
                "isPinned": {"booleanValue": true},
                "isArchived": {"booleanValue": false},
                "isTrashed": {"booleanValue": false},
                "position": {"integerValue": "1"},
                "serverUpdatedAt": {"timestampValue": "1970-01-02T03:04:05Z"},
                "labels": {"arrayValue": {"values": [
                  {"mapValue": {"fields": {"name": {"stringValue": "Work"}}}}
                ]}},
                "checklist": {"arrayValue": {"values": [
                  {"mapValue": {"fields": {
                    "text": {"stringValue": "Buy milk"},
                    "isChecked": {"booleanValue": true},
                    "position": {"integerValue": "0"}
                  }}}
                ]}}
              }
            }
            """.trimIndent()
        )

        val record = codec.parseNoteDocument(doc)
        assertNotNull(record)
        assertEquals(12L, record.noteId)
        assertEquals("Hello", record.title)
        assertEquals(1_700_000_000_000L, record.timestamp)
        assertEquals(listOf("Work"), record.labels)
        assertEquals("Buy milk", record.checklistItems.single().text)
        assertTrue(record.checklistItems.single().isChecked)
        // 1 day + 3h 4m 5s past the epoch, so the expected value is checkable by hand.
        assertEquals((86_400L + 3 * 3600 + 4 * 60 + 5) * 1000, record.serverUpdatedAt)
    }

    @Test
    fun `a document without fields is skipped rather than half-parsed`() {
        val doc = json.decodeFromString<JsonObject>(
            """{"name": "$documentsPath/users/uid1/notes/12"}"""
        )
        assertNull(codec.parseNoteDocument(doc))
    }

    @Test
    fun `an unparseable timestamp yields null, not epoch zero`() {
        // 0L is a real epoch millis; returning it would make conflict resolution treat the
        // remote copy as written in 1970 and lose every comparison.
        assertNull(codec.parseTimestamp("not a timestamp"))
        assertEquals(0L, codec.parseTimestamp("1970-01-01T00:00:00Z"))
    }

    @Test
    fun `parses a tombstone document`() {
        val doc = json.decodeFromString<JsonObject>(
            """
            {
              "name": "$documentsPath/users/uid1/tombstones/44",
              "fields": {"deletedAt": {"integerValue": "555"}}
            }
            """.trimIndent()
        )
        assertEquals(44L to 555L, codec.parseTombstone(doc))
    }

    @Test
    fun `commit body wraps writes in a writes array`() {
        val body = codec.commitBody(
            listOf(codec.deleteWrite("uid1", "notes", 1L), codec.deleteWrite("uid1", "notes", 2L))
        )
        assertEquals(2, body["writes"]!!.jsonArray.size)
    }
}
