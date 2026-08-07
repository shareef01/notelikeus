package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.sync.ChecklistItemData
import com.aus.notelikeus.data.sync.CloudNoteRecord
import com.aus.notelikeus.data.sync.CloudNoteTransport
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.util.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class DesktopFirestoreTransport(
    private val firebaseProject: String,
    private val idTokenProvider: suspend () -> String?
) : CloudNoteTransport {

    private val httpClient = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://firestore.googleapis.com/v1/projects/$firebaseProject/databases/(default)/documents"

    private suspend fun authHeader(): String = "Bearer ${idTokenProvider() ?: ""}"

    override suspend fun fetchNotes(uid: String): List<CloudNoteRecord> = withContext(Dispatchers.IO) {
        val req = HttpRequest.newBuilder().uri(URI("$baseUrl/users/$uid/notes"))
            .header("Authorization", authHeader()).GET().build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) return@withContext emptyList()
        val docs = json.decodeFromString<JsonObject>(resp.body())["documents"]?.jsonArray ?: return@withContext emptyList()
        docs.mapNotNull { parseDoc(it.jsonObject) }
    }

    override suspend fun fetchNote(uid: String, noteId: Long): CloudNoteRecord? = withContext(Dispatchers.IO) {
        val req = HttpRequest.newBuilder().uri(URI("$baseUrl/users/$uid/notes/$noteId"))
            .header("Authorization", authHeader()).GET().build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) return@withContext null
        parseDoc(json.decodeFromString<JsonObject>(resp.body()))
    }

    override suspend fun putNotes(uid: String, notes: List<Note>): Map<Long, Long?> = withContext(Dispatchers.IO) {
        if (notes.isEmpty()) return@withContext emptyMap()
        val writes = notes.mapNotNull { note ->
            val noteId = note.id ?: return@mapNotNull null
            val docPath = "projects/$firebaseProject/databases/(default)/documents/users/$uid/notes/$noteId"
            buildJsonObject {
                putJsonObject("update") {
                    put("name", docPath)
                    putJsonObject("fields") { putNoteFields(this, note) }
                }
            }
        }
        val body = buildJsonObject { put("writes", JsonArray(writes)) }
        val req = HttpRequest.newBuilder().uri(URI("$baseUrl:commit"))
            .header("Authorization", authHeader())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) return@withContext emptyMap()

        val results = json.decodeFromString<JsonObject>(resp.body())["writeResults"]?.jsonArray ?: return@withContext emptyMap()
        notes.mapNotNull { it.id }.zip(results).associate { (noteId, wr) ->
            val ts = wr.jsonObject["updateTime"]?.jsonPrimitive?.content
            noteId to ts?.let { parseTimestamp(it) }
        }
    }

    override suspend fun deleteNotes(uid: String, noteIds: List<Long>) = withContext(Dispatchers.IO) {
        if (noteIds.isEmpty()) return@withContext
        val writes = noteIds.map { id ->
            buildJsonObject { put("delete", "$baseUrl/users/$uid/notes/$id") }
        }
        val body = buildJsonObject { put("writes", JsonArray(writes)) }
        val req = HttpRequest.newBuilder().uri(URI("$baseUrl:commit"))
            .header("Authorization", authHeader())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        httpClient.send(req, HttpResponse.BodyHandlers.ofString())
    }

    override suspend fun fetchTombstones(uid: String): Map<Long, Long> = withContext(Dispatchers.IO) {
        val req = HttpRequest.newBuilder().uri(URI("$baseUrl/users/$uid/tombstones"))
            .header("Authorization", authHeader()).GET().build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) return@withContext emptyMap()
        val docs = json.decodeFromString<JsonObject>(resp.body())["documents"]?.jsonArray ?: return@withContext emptyMap()
        docs.mapNotNull { doc ->
            val obj = doc.jsonObject
            val noteId = obj["name"]?.jsonPrimitive?.content?.substringAfterLast("/")?.toLongOrNull() ?: return@mapNotNull null
            val deletedAt = obj["fields"]?.jsonObject?.get("deletedAt")?.jsonObject
                ?.get("integerValue")?.jsonPrimitive?.content?.toLongOrNull()
            noteId to (deletedAt ?: DateUtils.currentTimeMillis())
        }.toMap()
    }

    override suspend fun writeTombstone(uid: String, noteId: Long, deletedAt: Long) {
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                putJsonObject("fields") {
                    putJsonObject("deletedAt") { put("integerValue", deletedAt.toString()) }
                }
            }
            val req = HttpRequest.newBuilder().uri(URI("$baseUrl/users/$uid/tombstones/$noteId"))
                .header("Authorization", authHeader())
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString())).build()
            httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        }
    }

    override suspend fun deleteTombstones(uid: String, noteIds: List<Long>) = withContext(Dispatchers.IO) {
        if (noteIds.isEmpty()) return@withContext
        val writes = noteIds.map { id ->
            buildJsonObject { put("delete", "$baseUrl/users/$uid/tombstones/$id") }
        }
        val body = buildJsonObject { put("writes", JsonArray(writes)) }
        val req = HttpRequest.newBuilder().uri(URI("$baseUrl:commit"))
            .header("Authorization", authHeader())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        httpClient.send(req, HttpResponse.BodyHandlers.ofString())
    }

    override suspend fun writeSyncMeta(uid: String, noteCount: Int, platform: String) {
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                putJsonObject("fields") {
                    putJsonObject("lastSyncAt") { put("integerValue", DateUtils.currentTimeMillis().toString()) }
                    putJsonObject("noteCount") { put("integerValue", noteCount.toString()) }
                    putJsonObject("platform") { put("stringValue", platform) }
                }
            }
            val req = HttpRequest.newBuilder()
                .uri(URI("$baseUrl/users/$uid/_meta/sync?updateMask.fieldPaths=lastSyncAt&updateMask.fieldPaths=noteCount&updateMask.fieldPaths=platform"))
                .header("Authorization", authHeader())
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString())).build()
            httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        }
    }

    override suspend fun deleteSyncMeta(uid: String) {
        withContext(Dispatchers.IO) {
            val req = HttpRequest.newBuilder().uri(URI("$baseUrl/users/$uid/_meta/sync"))
                .header("Authorization", authHeader()).DELETE().build()
            httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        }
    }

    // ---- helpers ----

    private fun parseDoc(doc: JsonObject): CloudNoteRecord? {
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
            val values = f[k]?.jsonObject?.get("arrayValue")?.jsonObject?.get("values")?.jsonArray ?: return emptyList()
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
            labels = arrMaps("labels").mapNotNull { it["name"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content },
            checklistItems = arrMaps("checklist").mapIndexed { idx, item ->
                ChecklistItemData(
                    text = item["text"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: "",
                    isChecked = item["isChecked"]?.jsonObject?.get("booleanValue")?.jsonPrimitive?.content?.toBoolean() ?: false,
                    position = item["position"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.content?.toIntOrNull() ?: idx
                )
            }
        )
    }

    private fun putNoteFields(obj: kotlinx.serialization.json.JsonObjectBuilder, note: Note) {
        obj.putJsonObject("localId") { put("integerValue", note.id.toString()) }
        obj.putJsonObject("title") { put("stringValue", note.title) }
        obj.putJsonObject("content") { put("stringValue", note.content) }
        obj.putJsonObject("timestamp") { put("integerValue", note.timestamp.toString()) }
        obj.putJsonObject("color") { put("integerValue", note.color.toString()) }
        obj.putJsonObject("isPinned") { put("booleanValue", note.isPinned) }
        obj.putJsonObject("isArchived") { put("booleanValue", note.isArchived) }
        obj.putJsonObject("isTrashed") { put("booleanValue", note.isTrashed) }
        obj.putJsonObject("position") { put("integerValue", note.position.toString()) }
        obj.putJsonObject("isLocked") { put("booleanValue", false) }
        if (note.reminderTimestamp != null) {
            obj.putJsonObject("reminderTimestamp") { put("integerValue", note.reminderTimestamp.toString()) }
        }
        // Server-assigned commit time (like FieldValue.serverTimestamp() in the SDK).
        // This is what makes cross-device conflict resolution immune to clock skew.
        obj.putJsonObject("serverUpdatedAt") { put("setToServerValue", "REQUEST_TIME") }
        // Labels
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
            put("arrayValue", buildJsonObject {
                put("values", JsonArray(labelElements))
            })
        })
        // Checklist
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
            put("arrayValue", buildJsonObject {
                put("values", JsonArray(checklistElements))
            })
        })
    }

    private fun parseTimestamp(rfc3339: String): Long = try {
        java.time.Instant.parse(rfc3339.replace("Z", "+00:00")).toEpochMilli()
    } catch (_: Exception) { 0L }
}
