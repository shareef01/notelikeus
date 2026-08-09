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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A Firestore REST call that did not succeed.
 *
 * Every request in this transport goes through [DesktopFirestoreTransport.send], which throws this
 * rather than returning an empty result. That matters more than it looks: [com.aus.notelikeus.data
 * .sync.NoteSyncEngine.downloadAllNotes] treats "the cloud has no notes" as "they were deleted on
 * another device" and removes the local copies. Swallowing a 401 from an expired token therefore
 * used to mean silently deleting the user's notes.
 */
class FirestoreTransportException(
    val statusCode: Int,
    body: String
) : Exception("Firestore request failed: HTTP $statusCode ${body.take(500)}") {
    /** 401/403 — the ID token expired or was rejected. The caller should re-authenticate. */
    val isAuthFailure: Boolean get() = statusCode == 401 || statusCode == 403
}

class DesktopFirestoreTransport(
    private val firebaseProject: String,
    private val idTokenProvider: suspend () -> String?
) : CloudNoteTransport {

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Document *resource name* prefix — what the commit API expects, not a URL. */
    private val documentsPath = "projects/$firebaseProject/databases/(default)/documents"
    private val baseUrl = "https://firestore.googleapis.com/v1/$documentsPath"

    private suspend fun authHeader(): String = "Bearer ${idTokenProvider() ?: ""}"

    private fun docName(uid: String, collection: String, noteId: Long): String =
        "$documentsPath/users/$uid/$collection/$noteId"

    /**
     * Sends [request] and fails loudly on anything outside 2xx.
     *
     * @param treat404AsMissing returns null for 404 instead of throwing, for the "fetch one
     *   document that may not exist" case where absence is a legitimate answer.
     */
    private fun send(
        request: HttpRequest,
        treat404AsMissing: Boolean = false
    ): HttpResponse<String>? {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val code = response.statusCode()
        if (code == 404 && treat404AsMissing) return null
        if (code !in 200..299) throw FirestoreTransportException(code, response.body())
        return response
    }

    private suspend fun getRequest(url: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI(url))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Authorization", authHeader())
            .GET()
            .build()

    private suspend fun jsonRequest(url: String, method: String, body: JsonObject): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI(url))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Authorization", authHeader())
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

    /**
     * Walks every page of a `documents.list` response.
     *
     * The REST API caps a single page and hands back a `nextPageToken`; ignoring it silently
     * truncated the collection, which downstream reads as "these notes were deleted".
     */
    private suspend fun listDocuments(collectionUrl: String): List<JsonObject> {
        val documents = mutableListOf<JsonObject>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append(collectionUrl)
                append("?pageSize=").append(PAGE_SIZE)
                pageToken?.let {
                    append("&pageToken=").append(URLEncoder.encode(it, "UTF-8"))
                }
            }
            val response = send(getRequest(url)) ?: break
            val payload = json.decodeFromString<JsonObject>(response.body())
            payload["documents"]?.jsonArray?.forEach { documents.add(it.jsonObject) }
            pageToken = payload["nextPageToken"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return documents
    }

    /** Posts a `:commit` batch. Firestore caps a commit at 500 writes, so callers chunk first. */
    private suspend fun commit(writes: List<JsonObject>): List<JsonObject> {
        if (writes.isEmpty()) return emptyList()
        val body = buildJsonObject { put("writes", JsonArray(writes)) }
        val response = send(jsonRequest("$baseUrl:commit", "POST", body)) ?: return emptyList()
        return json.decodeFromString<JsonObject>(response.body())["writeResults"]
            ?.jsonArray
            ?.map { it.jsonObject }
            .orEmpty()
    }

    override suspend fun fetchNotes(uid: String): List<CloudNoteRecord> = withContext(Dispatchers.IO) {
        listDocuments("$baseUrl/users/$uid/notes").mapNotNull { parseDoc(it) }
    }

    override suspend fun fetchNote(uid: String, noteId: Long): CloudNoteRecord? = withContext(Dispatchers.IO) {
        val response = send(getRequest("$baseUrl/users/$uid/notes/$noteId"), treat404AsMissing = true)
            ?: return@withContext null
        parseDoc(json.decodeFromString<JsonObject>(response.body()))
    }

    override suspend fun putNotes(uid: String, notes: List<Note>): Map<Long, Long?> = withContext(Dispatchers.IO) {
        val withIds = notes.mapNotNull { note -> note.id?.let { it to note } }
        if (withIds.isEmpty()) return@withContext emptyMap()

        val resolved = mutableMapOf<Long, Long?>()
        for (chunk in withIds.chunked(COMMIT_LIMIT)) {
            val writes = chunk.map { (noteId, note) -> noteWrite(uid, noteId, note) }
            val results = commit(writes)
            chunk.map { it.first }.zip(results).forEach { (noteId, writeResult) ->
                resolved[noteId] = writeResult["updateTime"]?.jsonPrimitive?.content
                    ?.let { parseTimestamp(it) }
            }
        }
        resolved
    }

    override suspend fun deleteNotes(uid: String, noteIds: List<Long>) {
        deleteDocuments(uid, "notes", noteIds)
    }

    override suspend fun fetchTombstones(uid: String): Map<Long, Long> = withContext(Dispatchers.IO) {
        listDocuments("$baseUrl/users/$uid/tombstones").mapNotNull { doc ->
            val noteId = doc["name"]?.jsonPrimitive?.content
                ?.substringAfterLast("/")?.toLongOrNull() ?: return@mapNotNull null
            val deletedAt = doc["fields"]?.jsonObject?.get("deletedAt")?.jsonObject
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
            send(jsonRequest("$baseUrl/users/$uid/tombstones/$noteId", "PATCH", body))
        }
    }

    override suspend fun deleteTombstones(uid: String, noteIds: List<Long>) {
        deleteDocuments(uid, "tombstones", noteIds)
    }

    override suspend fun writeSyncMeta(uid: String, noteCount: Int, platform: String) {
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                putJsonObject("fields") {
                    putJsonObject("lastSyncAt") {
                        put("integerValue", DateUtils.currentTimeMillis().toString())
                    }
                    putJsonObject("noteCount") { put("integerValue", noteCount.toString()) }
                    putJsonObject("platform") { put("stringValue", platform) }
                }
            }
            val url = "$baseUrl/users/$uid/_meta/sync" +
                "?updateMask.fieldPaths=lastSyncAt" +
                "&updateMask.fieldPaths=noteCount" +
                "&updateMask.fieldPaths=platform"
            send(jsonRequest(url, "PATCH", body))
        }
    }

    override suspend fun deleteSyncMeta(uid: String) {
        withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder()
                .uri(URI("$baseUrl/users/$uid/_meta/sync"))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Authorization", authHeader())
                .DELETE()
                .build()
            // Already gone is a success for the caller's purposes.
            send(request, treat404AsMissing = true)
        }
    }

    private suspend fun deleteDocuments(uid: String, collection: String, noteIds: List<Long>) {
        if (noteIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            for (chunk in noteIds.chunked(COMMIT_LIMIT)) {
                commit(
                    chunk.map { noteId ->
                        // A resource name, NOT a URL. Passing the full https:// endpoint here made
                        // every delete fail with 400 while the caller saw success.
                        buildJsonObject { put("delete", docName(uid, collection, noteId)) }
                    }
                )
            }
        }
    }

    // ---- helpers ----

    /**
     * Builds one `Write` for a note.
     *
     * `serverUpdatedAt` goes in `updateTransforms`, which is a sibling of `update` — it is a
     * `DocumentTransform.FieldTransform`, and a `Value` inside `update.fields` has no
     * `setToServerValue` member, so putting it there got the whole commit rejected.
     */
    private fun noteWrite(uid: String, noteId: Long, note: Note): JsonObject = buildJsonObject {
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

    /**
     * Returns null rather than 0L on an unparseable timestamp: 0L is a *valid* epoch millis that
     * conflict resolution would read as "the server wrote this in 1970", losing every comparison.
     */
    private fun parseTimestamp(rfc3339: String): Long? = try {
        java.time.Instant.parse(rfc3339).toEpochMilli()
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** Firestore caps a single `:commit` at 500 writes. */
        const val COMMIT_LIMIT = 400
        const val PAGE_SIZE = 300
        const val REQUEST_TIMEOUT_SECONDS = 30L
    }
}
