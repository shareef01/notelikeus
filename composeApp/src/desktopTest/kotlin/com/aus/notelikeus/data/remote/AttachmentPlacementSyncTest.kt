package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.attachments.ATTACHMENT_PENDING_PREFIX
import com.aus.notelikeus.data.attachments.ATTACHMENT_R2_PREFIX
import com.aus.notelikeus.data.attachments.PlacementBody
import com.aus.notelikeus.data.attachments.attachmentsKey
import com.aus.notelikeus.data.attachments.mergeAttachmentsIntoNotes
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.AttachmentPlacement
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentPlacementSyncTest {

    private val identity = OperationRemoteIdentity("11111111-1111-1111-1111-111111111111", "token-a")
    private val body = "Before 🧭\n\nAfter"
    private val validPlacement = PlacementBody(body).placementAfter("Before 🧭".length)!!

    @Test
    fun `putNotes sends valid remote placements and omits stale and pending ones`() = runTest {
        val rpc = RecordingRpc()
        val transport = SupabaseNoteTransport(rpc)
        val note = Note(
            id = 42L,
            title = "Image note",
            content = body,
            timestamp = 1_000L,
            color = 0,
            attachments = listOf(
                Attachment(
                    id = "att-valid",
                    noteId = 42L,
                    storagePath = "${ATTACHMENT_R2_PREFIX}owners/u/notes/42/att-valid",
                    type = "image",
                    placement = validPlacement,
                ),
                Attachment(
                    id = "att-stale",
                    noteId = 42L,
                    storagePath = "${ATTACHMENT_R2_PREFIX}owners/u/notes/42/att-stale",
                    type = "image",
                    placement = validPlacement.copy(bodySha256 = "0".repeat(64)),
                ),
                Attachment(
                    id = "att-pending",
                    noteId = 42L,
                    storagePath = "${ATTACHMENT_PENDING_PREFIX}att-pending",
                    type = "image",
                    placement = validPlacement,
                ),
            ),
        )

        val result = transport.putNotes(identity, listOf(note))
        assertEquals(7L, result[42L]?.revision)
        assertEquals(listOf("apply_note_change_with_placements"), rpc.calls.map { it.first })
        val sent = rpc.calls.single().second
        val ids = sent["p_attachment_ids"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("att-valid", "att-stale"), ids)
        val placements = sent["p_attachment_placements"]!!.jsonArray
        assertEquals(1, placements.size)
        val entry = placements.single().jsonObject
        assertEquals("att-valid", entry["attachmentId"]!!.jsonPrimitive.content)
        assertEquals(validPlacement.bodySha256, entry["bodySha256"]!!.jsonPrimitive.content)
        assertEquals(validPlacement.offset, entry["offset"]!!.jsonPrimitive.long)
        assertEquals(validPlacement.ordinal, entry["ordinal"]!!.jsonPrimitive.long)
    }

    @Test
    fun `putNotes falls back to apply_note_change on PGRST202`() = runTest {
        val rpc = RecordingRpc(failPlacementsWithPgrst202 = true)
        val transport = SupabaseNoteTransport(rpc)
        val note = Note(
            id = 42L,
            title = "Image note",
            content = body,
            timestamp = 1_000L,
            color = 0,
            attachments = listOf(
                Attachment(
                    id = "att-valid",
                    noteId = 42L,
                    storagePath = "${ATTACHMENT_R2_PREFIX}owners/u/notes/42/att-valid",
                    type = "image",
                    placement = validPlacement,
                ),
            ),
        )

        val result = transport.putNotes(identity, listOf(note))
        assertEquals(7L, result[42L]?.revision)
        assertEquals(listOf("apply_note_change_with_placements", "apply_note_change"), rpc.calls.map { it.first })
        assertTrue("p_attachment_placements" !in rpc.calls.last().second)
    }

    @Test
    fun `listUserAttachments and mergeAttachmentsIntoNotes hydrate and preserve pending placements`() = runTest {
        val rpc = RecordingRpc(
            listResponse = buildJsonArray {
                add(attachmentRow("att-1", "42", validPlacement))
                add(attachmentRow("att-2", "43", null))
            },
        )
        val rows = SupabaseAttachmentMetadata(rpc).listUserAttachments(identity)
        assertEquals(validPlacement, rows[0].placement)
        assertNull(rows[1].placement)

        val localA = Note(
            id = 42L,
            title = "A",
            content = body,
            timestamp = 1_000L,
            color = 0,
            attachments = listOf(
                Attachment("att-1", 42L, "${ATTACHMENT_R2_PREFIX}owners/u/notes/42/att-1", placement = null),
            ),
        )
        val localB = Note(
            id = 43L,
            title = "B",
            content = body,
            timestamp = 1_000L,
            color = 0,
            attachments = listOf(
                Attachment("att-2", 43L, "${ATTACHMENT_R2_PREFIX}owners/u/notes/43/att-2", placement = validPlacement),
            ),
        )

        val synced = mergeAttachmentsIntoNotes(listOf(localA, localB), rows)
        assertEquals(validPlacement, synced[0].attachments.single().placement)
        assertNull(synced[1].attachments.single().placement)
        assertNotEquals(attachmentsKey(localA.attachments), attachmentsKey(synced[0].attachments))

        val pending = mergeAttachmentsIntoNotes(listOf(localA), rows, awaitingCommit = { it.id == 42L })
        assertNull(pending.single().attachments.single().placement)
    }

    private fun attachmentRow(id: String, noteId: String, placement: AttachmentPlacement?): JsonObject =
        buildJsonObject {
            put("attachment_id", id)
            put("note_id", noteId)
            put("object_key", "owners/u/notes/$noteId/$id")
            put("mime_type", "image/png")
            put("size_bytes", 3)
            put("attachment_type", "image")
            put("created_at", 1_000)
            if (placement != null) {
                put(
                    "placement",
                    buildJsonObject {
                        put("bodySha256", placement.bodySha256)
                        put("offset", placement.offset)
                        put("ordinal", placement.ordinal)
                    },
                )
            }
        }

    private class RecordingRpc(
        private val failPlacementsWithPgrst202: Boolean = false,
        private val listResponse: JsonArray = JsonArray(emptyList()),
    ) : SupabaseRpcClient {
        val calls = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject =
            error("unexpected live call")

        override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement =
            error("unexpected live call")

        override suspend fun callRpcElement(
            identity: OperationRemoteIdentity,
            functionName: String,
            body: JsonObject,
        ): JsonElement {
            calls += functionName to body
            if (functionName == "list_user_attachments") return listResponse
            if (failPlacementsWithPgrst202 && functionName == "apply_note_change_with_placements") {
                throw SupabaseTransportException(
                    functionName = functionName,
                    statusCode = 404,
                    body = """{"code":"PGRST202","message":"Could not find the function"}""",
                )
            }
            return JsonObject(
                mapOf(
                    "status" to JsonPrimitive("applied"),
                    "revision" to JsonPrimitive(7),
                    "server_updated_at" to JsonPrimitive(2_000L),
                ),
            )
        }
    }
}
