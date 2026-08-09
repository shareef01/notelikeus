package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.sync.ChecklistItemData
import com.aus.notelikeus.data.sync.CloudNoteRecord
import com.aus.notelikeus.domain.model.Note
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Builds Firestore REST request bodies and reads its documents back.
 *
 * Split out of [DesktopFirestoreTransport] so the wire format can be asserted directly. The two
 * bugs that made desktop writes silently fail were both pure shape errors — a field transform in
 * the wrong place and a URL where a resource name belongs — and neither is visible from the
 * transport's own behaviour, because a rejected commit looked exactly like a successful one.
 *
 * @param documentsPath `projects/{id}/databases/(default)/documents`, with no host.
 */
internal class FirestoreRestCodec(private val documentsPath: String) {

    /** The `projects/…/documents/…` resource name a Write refers to. Never a URL. */
    fun docName(uid: String, collection: String, noteId: Long): String =
        "$documentsPath/users/$uid/$collection/$noteId"

    /**
     * One `Write` for a note.
     *
     * `serverUpdatedAt` is a `DocumentTransform.FieldTransform` and belongs in the Write's
     * `updateTransforms`, a sibling of `update` — a `Value` inside `update.fields` has no
     * `setToServerValue` member, and including one there gets the whole commit rejected.
     */
    fun noteWrite(uid: String, noteId: Long, note: Note): JsonObject = buildJsonObject {
        putJsonObject("update") {
            put("name", docName(uid, "notes", noteId))
            putJsonObject("fields") { putNoteFields(this, note) }
        }
        put(
            "updateTransforms",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("fieldPath", "serverUpdatedAt")
                        put("setToServerValue", "REQUEST_TIME")
                    }
                )
            }
        )
    }

    fun deleteWrite(uid: String, collection: String, noteId: Long): JsonObject =
        buildJsonObject { put("delete", docName(uid, collection, noteId)) }

    fun commitBody(writes: List<JsonObject>): JsonObject =
        buildJsonObject { put("writes", JsonArray(writes)) }

    fun tombstoneBody(deletedAt: Long): JsonObject = buildJsonObject {
        putJsonObject("fields") {
            putJsonObject("deletedAt") { put("integerValue", deletedAt.toString()) }
        }
    }

    fun syncMetaBody(noteCount: Int, platform: String, now: Long): JsonObject = buildJsonObject {
        putJsonObject("fields") {
            putJsonObject("lastSyncAt") { put("integerValue", now.toString()) }
            putJsonObject("noteCount") { put("integerValue", noteCount.toString()) }
            putJsonObject("platform") { put("stringValue", platform) }
        }
    }

    fun parseNoteDocument(doc: JsonObject): CloudNoteRecord? {
        val name = doc["name"]?.jsonPrimitive?.content ?: return null
        val noteId = name.substringAfterLast("/").toLongOrNull() ?: return null
        val f = doc["fields"]?.jsonObject ?: return null

        fun s(k: String) = f[k]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content
        fun l(k: String) = f[k]?.jsonObject?.get("integerValue")?.jsonPrimitive?.content?.toLongOrNull()
        fun i(k: String) = f[k]?.jsonObject?.get("integerValue")?.jsonPrimitive?.content?.toIntOrNull()
        fun b(k: String) = f[k]?.jsonObject?.get("booleanValue")?.jsonPrimitive?.content?.toBoolean() ?: false
        fun ts(k: String): Long? {
            val v = f[k]?.jsonObject?.get("timestampValue")?.jsonPrimitive?.content ?: return null
            return parseTimestamp(v)
        }
        fun arrMaps(k: String): List<JsonObject> {
            val values = f[k]?.jsonObject?.get("arrayValue")?.jsonObject?.get("values")?.jsonArray
                ?: return emptyList()
            return values.mapNotNull { it.jsonObject["mapValue"]?.jsonObject?.get("fields")?.jsonObject }
        }

        return CloudNoteRecord(
            noteId = noteId,
            serverUpdatedAt = ts("serverUpdatedAt"),
            clientTimestamp = l("timestamp"),
            title = s("title") ?: "",
            content = s("content") ?: "",
            timestamp = l("timestamp") ?: 0L,
            color = i("color") ?: 0,
            isPinned = b("isPinned"),
            isArchived = b("isArchived"),
            isTrashed = b("isTrashed"),
            position = i("position") ?: 0,
            reminderTimestamp = l("reminderTimestamp"),
            labels = arrMaps("labels").mapNotNull {
                it["name"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content
            },
            checklistItems = arrMaps("checklist").mapIndexed { index, item ->
                ChecklistItemData(
                    text = item["text"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: "",
                    isChecked = item["isChecked"]?.jsonObject?.get("booleanValue")
                        ?.jsonPrimitive?.content?.toBoolean() ?: false,
                    position = item["position"]?.jsonObject?.get("integerValue")
                        ?.jsonPrimitive?.content?.toIntOrNull() ?: index
                )
            }
        )
    }

    /** The note id a tombstone document refers to, or null if the name does not end in one. */
    fun parseTombstone(doc: JsonObject): Pair<Long, Long?>? {
        val noteId = doc["name"]?.jsonPrimitive?.content
            ?.substringAfterLast("/")?.toLongOrNull() ?: return null
        val deletedAt = doc["fields"]?.jsonObject?.get("deletedAt")?.jsonObject
            ?.get("integerValue")?.jsonPrimitive?.content?.toLongOrNull()
        return noteId to deletedAt
    }

    /**
     * Returns null rather than 0L on an unparseable timestamp: 0L is a *valid* epoch millis that
     * conflict resolution would read as "the server wrote this in 1970", losing every comparison.
     */
    fun parseTimestamp(rfc3339: String): Long? = try {
        java.time.Instant.parse(rfc3339).toEpochMilli()
    } catch (_: Exception) {
        null
    }

    private fun putNoteFields(obj: JsonObjectBuilder, note: Note) {
        obj.putJsonObject("localId") { put("integerValue", note.id.toString()) }
        obj.putJsonObject("title") { put("stringValue", note.title) }
        obj.putJsonObject("content") { put("stringValue", note.content) }
        obj.putJsonObject("timestamp") { put("integerValue", note.timestamp.toString()) }
        obj.putJsonObject("color") { put("integerValue", note.color.toString()) }
        obj.putJsonObject("isPinned") { put("booleanValue", note.isPinned) }
        obj.putJsonObject("isArchived") { put("booleanValue", note.isArchived) }
        obj.putJsonObject("isTrashed") { put("booleanValue", note.isTrashed) }
        obj.putJsonObject("position") { put("integerValue", note.position.toString()) }
        // Locking was removed, but the deployed rules still accept (and older clients still send)
        // this key.
        obj.putJsonObject("isLocked") { put("booleanValue", false) }
        if (note.reminderTimestamp != null) {
            obj.putJsonObject("reminderTimestamp") {
                put("integerValue", note.reminderTimestamp.toString())
            }
        }
        val labelElements = note.labels.map { label ->
            buildJsonObject {
                putJsonObject("mapValue") {
                    putJsonObject("fields") {
                        putJsonObject("name") { put("stringValue", label.name) }
                    }
                }
            }
        }
        obj.put("labels", buildJsonObject {
            put("arrayValue", buildJsonObject { put("values", JsonArray(labelElements)) })
        })
        val checklistElements = note.checklist.map { item ->
            buildJsonObject {
                putJsonObject("mapValue") {
                    putJsonObject("fields") {
                        putJsonObject("text") { put("stringValue", item.text) }
                        putJsonObject("isChecked") { put("booleanValue", item.isChecked) }
                        putJsonObject("position") { put("integerValue", item.position.toString()) }
                    }
                }
            }
        }
        obj.put("checklist", buildJsonObject {
            put("arrayValue", buildJsonObject { put("values", JsonArray(checklistElements)) })
        })
    }
}
