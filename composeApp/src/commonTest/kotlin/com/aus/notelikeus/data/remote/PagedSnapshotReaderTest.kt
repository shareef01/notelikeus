package com.aus.notelikeus.data.remote

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** AUD-008: the full snapshot, read in pages that must all describe the same account state. */
class PagedSnapshotReaderTest {

    private fun note(id: String, position: Int) = buildJsonObject {
        put("note_id", id)
        put("local_id", id.toLong())
        put("position", position)
        put("revision", id.toLong())
    }

    private fun state(headRevision: Long = 9, tombstones: Int = 1, generation: Long = 0) = buildJsonObject {
        put("head_revision", headRevision)
        put("wipe_generation", generation)
        put("note_count", 3)
        put("tombstone_count", tombstones)
    }

    /** A database answering in pages of [size], with [stateFor] giving the state each call reports. */
    private inner class PagingRpc(
        private val notes: List<JsonObject>,
        private val tombstones: List<JsonObject>,
        private val size: Int,
        private val stateFor: (Int) -> JsonObject = { state() },
        private val pagedMissing: Boolean = false,
    ) : SupabaseRpcClient {
        val calls = mutableListOf<String>()

        override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject {
            val kind = body["p_kind"]?.jsonPrimitive?.contentOrNull
            val after = (body["p_after"] as? JsonPrimitive)?.contentOrNull
            calls += if (functionName == "fetch_snapshot_page") "$kind:$after" else functionName
            if (functionName == "fetch_full_snapshot") {
                return buildJsonObject {
                    put("notes", JsonArray(notes))
                    put("tombstones", JsonArray(tombstones))
                    put("note_count", notes.size)
                }
            }
            if (pagedMissing) {
                throw SupabaseTransportException(functionName, 404, """{"code":"PGRST202","message":"Could not find"}""")
            }
            val rows = (if (kind == "notes") notes else tombstones)
                .sortedBy { it["note_id"]!!.jsonPrimitive.content }
                .filter { after == null || it["note_id"]!!.jsonPrimitive.content > after }
            val page = rows.take(size)
            return buildJsonObject {
                put("items", JsonArray(page))
                put("next_after", if (rows.size > size) page.last()["note_id"]!! else JsonNull)
                put("state", stateFor(calls.size - 1))
            }
        }

        override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement =
            callRpc(functionName, body)
    }

    private fun JsonObject.ids(field: String) = this[field]!!.jsonArray.map { it.jsonObject["note_id"]!!.jsonPrimitive.content }

    @Test
    fun `every page is assembled in the order the one-response snapshot returns`() = runTest {
        val rpc = PagingRpc(
            notes = listOf(note("3", 0), note("1", 2), note("2", 1)),
            tombstones = listOf(buildJsonObject { put("note_id", "7"); put("revision", 8) }),
            size = 1,
        )

        val snapshot = PagedSnapshotReader(rpc).read()

        assertEquals(listOf("3", "2", "1"), snapshot.ids("notes"))
        assertEquals(listOf("7"), snapshot.ids("tombstones"))
        assertEquals(JsonPrimitive(3), snapshot["note_count"])
        assertEquals(listOf("notes:null", "notes:1", "notes:2", "tombstones:null"), rpc.calls)
    }

    @Test
    fun `a write between pages makes it read again, and the still read is accepted`() = runTest {
        val rpc = PagingRpc(
            notes = listOf(note("1", 0), note("2", 1), note("3", 2)),
            tombstones = emptyList(),
            size = 2,
            stateFor = { call -> if (call == 1) state(headRevision = 10) else state() },
        )

        val snapshot = PagedSnapshotReader(rpc).read()

        assertEquals(3, snapshot["notes"]!!.jsonArray.size)
        assertEquals(2, rpc.calls.count { it == "notes:null" })
    }

    @Test
    fun `pages from different states are never accepted`() = runTest {
        val rpc = PagingRpc(
            notes = listOf(note("1", 0), note("2", 1)),
            tombstones = emptyList(),
            size = 1,
            stateFor = { call -> state(headRevision = call.toLong()) },
        )

        assertFailsWith<SnapshotKeptChangingException> { PagedSnapshotReader(rpc).read() }
        assertEquals(3, rpc.calls.count { it == "notes:null" })
    }

    @Test
    fun `a changed tombstone count or generation is a different state too`() = runTest {
        listOf(state(tombstones = 2), state(generation = 1)).forEach { changed ->
            val rpc = PagingRpc(
                notes = listOf(note("1", 0)),
                tombstones = listOf(buildJsonObject { put("note_id", "5"); put("revision", 5) }),
                size = 1,
                stateFor = { call -> if (call % 2 == 1) changed else state() },
            )
            assertFailsWith<SnapshotKeptChangingException> { PagedSnapshotReader(rpc).read() }
        }
    }

    @Test
    fun `an older database answers with its one-response snapshot`() = runTest {
        val rpc = PagingRpc(notes = listOf(note("1", 0)), tombstones = emptyList(), size = 1, pagedMissing = true)

        val snapshot = PagedSnapshotReader(rpc).read()

        assertEquals(listOf("1"), snapshot.ids("notes"))
        assertEquals(listOf("notes:null", "fetch_full_snapshot"), rpc.calls)
    }

    @Test
    fun `any other failure reaches the caller, so a failed read never looks like an empty account`() = runTest {
        val rpc = object : SupabaseRpcClient {
            override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject =
                throw SupabaseTransportException(functionName, 400, """{"code":"NL003","message":"account_deleted: gone"}""")

            override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement =
                callRpc(functionName, body)
        }

        val failure = assertFailsWith<SupabaseTransportException> { PagedSnapshotReader(rpc).read() }
        assertEquals(true, failure.isAccountDeleted)
    }

    @Test
    fun `a page without its state is refused`() = runTest {
        val rpc = object : SupabaseRpcClient {
            override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject =
                buildJsonObject { put("items", JsonArray(emptyList())) }

            override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement =
                callRpc(functionName, body)
        }

        assertFailsWith<IllegalStateException> { PagedSnapshotReader(rpc).read() }
    }
}
