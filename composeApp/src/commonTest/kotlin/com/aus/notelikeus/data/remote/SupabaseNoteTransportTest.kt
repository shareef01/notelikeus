package com.aus.notelikeus.data.remote

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val UID = "11111111-1111-4111-8111-111111111111"

/** Records every RPC call and answers from a scripted queue keyed by function name. */
private class RecordingRpcClient(
    private val responses: MutableMap<String, MutableList<JsonObject>> = mutableMapOf(),
) : SupabaseRpcClient {
    val calls = mutableListOf<Pair<String, JsonObject>>()

    fun on(functionName: String, vararg response: JsonObject) = apply {
        responses.getOrPut(functionName) { mutableListOf() }.addAll(response)
    }

    override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject {
        calls += functionName to body
        val queue = responses[functionName] ?: return buildJsonObject { }
        return if (queue.size > 1) queue.removeFirst() else queue.firstOrNull() ?: buildJsonObject { }
    }

    override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement =
        callRpc(functionName, body)

    fun deleteCalls() = calls.filter { it.first == "apply_note_delete" }
}

private fun snapshotWith(noteId: Long, revision: Long): JsonObject = buildJsonObject {
    put(
        "notes",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("note_id", JsonPrimitive(noteId.toString()))
                    put("local_id", JsonPrimitive(noteId))
                    put("revision", JsonPrimitive(revision))
                    put("title", JsonPrimitive("n$noteId"))
                    put("content", JsonPrimitive(""))
                    put("client_timestamp", JsonPrimitive(1L))
                    put("color", JsonPrimitive(0))
                    put("position", JsonPrimitive(0))
                },
            )
        },
    )
    put("tombstones", buildJsonArray { })
    put("note_count", JsonPrimitive(1))
}

private fun emptySnapshot(): JsonObject = buildJsonObject {
    put("notes", buildJsonArray { })
    put("tombstones", buildJsonArray { })
    put("note_count", JsonPrimitive(0))
}

class SupabaseNoteTransportTest {

    /**
     * `NoteSyncEngine.deleteNote()` calls the transport directly, with no download first. Because
     * the revision map is per-instance and never persisted, a delete in a fresh process used to
     * find no base revision and return without calling the RPC at all — so the note stayed in the
     * cloud and reappeared on the next device that synced.
     */
    @Test
    fun deleteWithoutACachedRevisionStillReachesTheServer() = runTest {
        val rpc = RecordingRpcClient()
            .on("fetch_full_snapshot", snapshotWith(noteId = 7L, revision = 10_042L))
            .on("apply_note_delete", buildJsonObject {
                put("status", JsonPrimitive("applied"))
                put("revision", JsonPrimitive(10_043L))
            })

        SupabaseNoteTransport(rpc).deleteNotes(UID, listOf(7L))

        val deletes = rpc.deleteCalls()
        assertEquals(1, deletes.size, "expected the delete to reach the server")
        assertEquals("10042", deletes.single().second["p_base_revision"]?.jsonPrimitive?.content)
    }

    /** A note the server does not have needs no tombstone, and must not cost a wasted RPC. */
    @Test
    fun deleteOfANoteTheServerDoesNotHaveIsSkipped() = runTest {
        val rpc = RecordingRpcClient().on("fetch_full_snapshot", emptySnapshot())

        SupabaseNoteTransport(rpc).deleteNotes(UID, listOf(7L))

        assertTrue(rpc.deleteCalls().isEmpty())
    }

    /** One refresh covers the whole batch rather than one snapshot fetch per note. */
    @Test
    fun aBatchOfDeletesRefreshesTheRevisionMapOnce() = runTest {
        val rpc = RecordingRpcClient()
            .on("fetch_full_snapshot", snapshotWith(noteId = 7L, revision = 10_042L))
            .on("apply_note_delete", buildJsonObject { put("status", JsonPrimitive("applied")) })

        SupabaseNoteTransport(rpc).deleteNotes(UID, listOf(7L, 8L, 9L))

        assertEquals(1, rpc.calls.count { it.first == "fetch_full_snapshot" })
    }

    /**
     * The idempotent answer carries no `revision`. Keying the cleanup on it left the note's stale
     * revision in the map, so a later change would be sent against a revision the server had
     * already retired.
     */
    @Test
    fun anIdempotentDeleteClearsTheCachedRevision() = runTest {
        val rpc = RecordingRpcClient()
            .on("fetch_full_snapshot", snapshotWith(noteId = 7L, revision = 10_042L))
            .on("apply_note_delete", buildJsonObject {
                put("status", JsonPrimitive("applied"))
                put("idempotent", JsonPrimitive(true))
            })
        val transport = SupabaseNoteTransport(rpc)

        transport.deleteNotes(UID, listOf(7L))
        // A second delete must not re-fetch a revision the transport should have forgotten, and
        // must not resend the retired one.
        transport.deleteNotes(UID, listOf(7L))

        assertTrue(
            rpc.deleteCalls().all { it.second["p_base_revision"]?.jsonPrimitive?.content == "10042" },
        )
    }

    /** A concurrent edit bumps the revision; the delete should follow it rather than give up. */
    @Test
    fun aRevisionConflictIsRetriedAgainstTheServerRevision() = runTest {
        val rpc = RecordingRpcClient()
            .on("fetch_full_snapshot", snapshotWith(noteId = 7L, revision = 10_042L))
            .on(
                "apply_note_delete",
                buildJsonObject {
                    put("status", JsonPrimitive("conflict"))
                    put("current", buildJsonObject { put("revision", JsonPrimitive(10_099L)) })
                },
                buildJsonObject {
                    put("status", JsonPrimitive("applied"))
                    put("revision", JsonPrimitive(10_100L))
                },
            )

        SupabaseNoteTransport(rpc).deleteNotes(UID, listOf(7L))

        val deletes = rpc.deleteCalls()
        assertEquals(2, deletes.size)
        assertEquals("10099", deletes[1].second["p_base_revision"]?.jsonPrimitive?.content)
    }

    /** A cached revision from a prior download is used directly, with no extra snapshot. */
    @Test
    fun aCachedRevisionIsUsedWithoutRefetching() = runTest {
        val rpc = RecordingRpcClient()
            .on("fetch_full_snapshot", snapshotWith(noteId = 7L, revision = 10_042L))
            .on("apply_note_delete", buildJsonObject { put("status", JsonPrimitive("applied")) })
        val transport = SupabaseNoteTransport(rpc)

        transport.fetchNotes(UID)
        transport.deleteNotes(UID, listOf(7L))

        assertEquals(1, rpc.calls.count { it.first == "fetch_full_snapshot" })
        assertEquals(1, rpc.deleteCalls().size)
    }

    /**
     * `note_count` is a separate COUNT(*) from the notes jsonb_agg. If they disagree the payload
     * was truncated, and treating it as the full library would tombstone everything missing.
     */
    @Test
    fun aTruncatedSnapshotIsRejected() = runTest {
        val rpc = RecordingRpcClient().on(
            "fetch_full_snapshot",
            buildJsonObject {
                put(
                    "notes",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("note_id", JsonPrimitive("7"))
                                put("local_id", JsonPrimitive(7L))
                                put("revision", JsonPrimitive(1L))
                            },
                        )
                    },
                )
                put("tombstones", buildJsonArray { })
                put("note_count", JsonPrimitive(5))
            },
        )

        assertFailsWith<IllegalStateException> {
            SupabaseNoteTransport(rpc).fetchNotes(UID)
        }
    }

    /**
     * Pre-migration `fetch_full_snapshot` returned SQL NULL when the owner had zero notes, which
     * PostgREST surfaces as `{}`. That dropped tombstones. Missing `note_count` is the same class.
     */
    @Test
    fun aSnapshotWithoutNoteCountIsRejected() = runTest {
        val rpc = RecordingRpcClient().on(
            "fetch_full_snapshot",
            buildJsonObject {
                put("notes", buildJsonArray { })
                put("tombstones", buildJsonArray { })
            },
        )

        assertFailsWith<IllegalStateException> {
            SupabaseNoteTransport(rpc).fetchNotes(UID)
        }
    }

    /**
     * `note_count` agreeing with the raw `notes` array proves the aggregate was not truncated, but
     * the row mapping still drops anything whose id cannot be read. The engine reconciles against
     * the *records*, so the count has to travel out with them rather than being consumed here —
     * otherwise a snapshot that lost a note to an unreadable id looks complete to the engine and
     * every previously-known id it omits is applied as a deletion.
     */
    @Test
    fun aRowWithNoReadableIdIsReportedAsAShortSnapshot() = runTest {
        val rpc = RecordingRpcClient().on(
            "fetch_full_snapshot",
            buildJsonObject {
                put(
                    "notes",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("note_id", JsonPrimitive("7"))
                                put("local_id", JsonPrimitive(7L))
                                put("revision", JsonPrimitive(1L))
                                put("title", JsonPrimitive("readable"))
                            },
                        )
                        add(
                            buildJsonObject {
                                put("note_id", JsonPrimitive("not-a-number"))
                                put("revision", JsonPrimitive(2L))
                                put("title", JsonPrimitive("unreadable id"))
                            },
                        )
                    },
                )
                put("tombstones", buildJsonArray { })
                put("note_count", JsonPrimitive(2))
            },
        )

        val snapshot = SupabaseNoteTransport(rpc).fetchNotesSnapshot(UID)

        assertEquals(1, snapshot.records.size, "the unreadable row cannot become a record")
        assertEquals(
            2,
            snapshot.authoritativeNoteCount,
            "so the server count must reach the engine, which is what refuses the reconcile",
        )
    }

    @Test
    fun aCompleteSnapshotReportsAMatchingCount() = runTest {
        val rpc = RecordingRpcClient()
            .on("fetch_full_snapshot", snapshotWith(noteId = 7L, revision = 10_042L))

        val snapshot = SupabaseNoteTransport(rpc).fetchNotesSnapshot(UID)

        assertEquals(1, snapshot.records.size)
        assertEquals(1, snapshot.authoritativeNoteCount)
    }
}
