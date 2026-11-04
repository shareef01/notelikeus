package com.aus.notelikeus.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * For fakes written against `fetch_full_snapshot`: answers `fetch_snapshot_page` (AUD-008) as one page
 * cut from the fake's full-snapshot payload. The page carries that payload's rows and the state they
 * imply, so a test's edge case (a missing note_count, an empty `{}`) reaches the transport exactly as
 * it would through the paged read.
 */
internal object PagedSnapshotTestSupport {
    const val PAGE_FUNCTION = "fetch_snapshot_page"
    const val FULL_FUNCTION = "fetch_full_snapshot"

    /** The notes page starts a snapshot read; the tombstones page that follows belongs to the same one. */
    fun startsRead(body: JsonObject): Boolean = (body["p_kind"] as? JsonPrimitive)?.contentOrNull != "tombstones"

    fun page(full: JsonObject, body: JsonObject): JsonObject {
        val notes = full["notes"] as? JsonArray ?: JsonArray(emptyList())
        val tombstones = full["tombstones"] as? JsonArray ?: JsonArray(emptyList())
        val revisions = (notes + tombstones).mapNotNull { (it.jsonObject["revision"] as? JsonPrimitive)?.longOrNull }
        return buildJsonObject {
            put("items", if (startsRead(body)) notes else tombstones)
            put("next_after", JsonNull)
            put(
                "state",
                buildJsonObject {
                    put("head_revision", revisions.maxOrNull() ?: 0L)
                    put("wipe_generation", full["wipe_generation"] ?: JsonPrimitive(0))
                    put("note_count", full["note_count"] ?: JsonNull)
                    put("tombstone_count", tombstones.size)
                },
            )
        }
    }
}
