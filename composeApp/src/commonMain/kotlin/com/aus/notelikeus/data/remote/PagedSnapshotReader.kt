package com.aus.notelikeus.data.remote

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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The full snapshot, read a page at a time (AUD-008). Port of `web/src/lib/supabase/pagedSnapshot.ts`.
 *
 * `fetch_full_snapshot` returns every note and tombstone as one response, which an account near its
 * note limit can push past a timeout or a proxy's size limit. `fetch_snapshot_page` returns them in
 * pages, and every page reports the account's state (newest revision, wipe generation, note count,
 * tombstone count). Any write that changes a snapshot's contents moves that state, so pages that all
 * report the same state are exactly the snapshot at that state; otherwise they are read again.
 *
 * [read] returns the same JSON, in the same order, as `fetch_full_snapshot`, so everything that checks
 * it (note_count against the rows, the wipe generation, the unexplained-id guard) is unchanged. On a
 * database that predates the paged read it returns `fetch_full_snapshot`'s own answer.
 */
class PagedSnapshotReader(
    private val rpc: SupabaseRpcClient,
    private val pageLimit: Int = PAGE_LIMIT,
) {
    suspend fun read(): JsonObject {
        repeat(ATTEMPTS) {
            val states = mutableListOf<JsonElement?>()
            val notes = readAll("notes", states) ?: return fullSnapshot()
            val tombstones = readAll("tombstones", states) ?: return fullSnapshot()
            val first = states.firstOrNull()
            if (first != null && states.all { it == first }) {
                val state = first.jsonObject
                return buildJsonObject {
                    // The order fetch_full_snapshot returns: notes by position then local id,
                    // tombstones by revision.
                    put(
                        "notes",
                        JsonArray(notes.sortedWith(compareBy({ it.long("position") }, { it.long("local_id") }))),
                    )
                    put("tombstones", JsonArray(tombstones.sortedBy { it.long("revision") }))
                    put("note_count", state["note_count"] ?: JsonNull)
                    put("wipe_generation", state["wipe_generation"] ?: JsonNull)
                }
            }
        }
        throw SnapshotKeptChangingException()
    }

    /** One kind's pages in order, recording each page's state; null when the database is older. */
    private suspend fun readAll(kind: String, states: MutableList<JsonElement?>): List<JsonElement>? {
        val items = mutableListOf<JsonElement>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val page = try {
                rpc.callRpc(
                    "fetch_snapshot_page",
                    buildJsonObject {
                        put("p_kind", kind)
                        put("p_after", after)
                        put("p_limit", pageLimit)
                    },
                )
            } catch (missing: SupabaseTransportException) {
                if (missing.statusCode == HTTP_NOT_FOUND && "PGRST202" in missing.body) return null
                throw missing
            }
            val pageItems = page["items"] as? JsonArray
            val state = page["state"]
            if (pageItems == null || state !is JsonObject) {
                error("Incomplete snapshot: a $kind page had no items or state")
            }
            items.addAll(pageItems)
            states.add(state)
            after = (page["next_after"] as? JsonPrimitive)?.contentOrNull
            if (after == null) return items
        }
        error("Incomplete snapshot: more than $MAX_PAGES $kind pages")
    }

    private suspend fun fullSnapshot(): JsonObject = rpc.callRpc("fetch_full_snapshot", buildJsonObject { })

    private fun JsonElement.long(field: String): Long =
        (jsonObject[field] as? JsonPrimitive)?.longOrNull ?: 0L

    private companion object {
        /** Rows per request; the server also stops a notes page at 4 MiB of text. */
        const val PAGE_LIMIT = 500

        /** Full reads before giving up while the account keeps changing under them. */
        const val ATTEMPTS = 3

        /** A safety stop against a server that never says it is done. */
        const val MAX_PAGES = 10_000
        const val HTTP_NOT_FOUND = 404
    }
}

/** The account kept changing while its snapshot was being read; the next sync reads it again. */
class SnapshotKeptChangingException :
    IllegalStateException("The cloud notes kept changing while they were being read. Sync will try again.")
