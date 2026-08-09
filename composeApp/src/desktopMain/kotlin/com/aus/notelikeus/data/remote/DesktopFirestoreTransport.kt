package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.sync.CloudNoteRecord
import com.aus.notelikeus.data.sync.CloudNoteTransport
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.util.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * rather than returning an empty result. That matters more than it looks:
 * [com.aus.notelikeus.data.sync.NoteSyncEngine.downloadAllNotes] treats "the cloud has no notes"
 * as "they were deleted on another device" and removes the local copies. Swallowing a 401 from an
 * expired token therefore used to mean silently deleting the user's notes.
 */
class FirestoreTransportException(
    val statusCode: Int,
    body: String
) : Exception("Firestore request failed: HTTP $statusCode ${body.take(500)}") {
    /** 401/403 — the ID token expired or was rejected. The caller should re-authenticate. */
    val isAuthFailure: Boolean get() = statusCode == 401 || statusCode == 403
}

class DesktopFirestoreTransport(
    firebaseProject: String,
    private val idTokenProvider: suspend () -> String?
) : CloudNoteTransport {

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    private val documentsPath = "projects/$firebaseProject/databases/(default)/documents"
    private val baseUrl = "https://firestore.googleapis.com/v1/$documentsPath"
    private val codec = FirestoreRestCodec(documentsPath)

    private suspend fun authHeader(): String = "Bearer ${idTokenProvider() ?: ""}"

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
                pageToken?.let { append("&pageToken=").append(URLEncoder.encode(it, "UTF-8")) }
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
        val response = send(jsonRequest("$baseUrl:commit", "POST", codec.commitBody(writes)))
            ?: return emptyList()
        return json.decodeFromString<JsonObject>(response.body())["writeResults"]
            ?.jsonArray
            ?.map { it.jsonObject }
            .orEmpty()
    }

    override suspend fun fetchNotes(uid: String): List<CloudNoteRecord> = withContext(Dispatchers.IO) {
        listDocuments("$baseUrl/users/$uid/notes").mapNotNull { codec.parseNoteDocument(it) }
    }

    override suspend fun fetchNote(uid: String, noteId: Long): CloudNoteRecord? = withContext(Dispatchers.IO) {
        val response = send(getRequest("$baseUrl/users/$uid/notes/$noteId"), treat404AsMissing = true)
            ?: return@withContext null
        codec.parseNoteDocument(json.decodeFromString<JsonObject>(response.body()))
    }

    override suspend fun putNotes(uid: String, notes: List<Note>): Map<Long, Long?> = withContext(Dispatchers.IO) {
        val withIds = notes.mapNotNull { note -> note.id?.let { it to note } }
        if (withIds.isEmpty()) return@withContext emptyMap()

        val resolved = mutableMapOf<Long, Long?>()
        for (chunk in withIds.chunked(COMMIT_LIMIT)) {
            val results = commit(chunk.map { (noteId, note) -> codec.noteWrite(uid, noteId, note) })
            chunk.map { it.first }.zip(results).forEach { (noteId, writeResult) ->
                resolved[noteId] = writeResult["updateTime"]?.jsonPrimitive?.content
                    ?.let { codec.parseTimestamp(it) }
            }
        }
        resolved
    }

    override suspend fun deleteNotes(uid: String, noteIds: List<Long>) {
        deleteDocuments(uid, "notes", noteIds)
    }

    override suspend fun fetchTombstones(uid: String): Map<Long, Long> = withContext(Dispatchers.IO) {
        listDocuments("$baseUrl/users/$uid/tombstones").mapNotNull { doc ->
            val (noteId, deletedAt) = codec.parseTombstone(doc) ?: return@mapNotNull null
            noteId to (deletedAt ?: DateUtils.currentTimeMillis())
        }.toMap()
    }

    override suspend fun writeTombstone(uid: String, noteId: Long, deletedAt: Long) {
        withContext(Dispatchers.IO) {
            send(
                jsonRequest(
                    "$baseUrl/users/$uid/tombstones/$noteId",
                    "PATCH",
                    codec.tombstoneBody(deletedAt)
                )
            )
        }
    }

    override suspend fun deleteTombstones(uid: String, noteIds: List<Long>) {
        deleteDocuments(uid, "tombstones", noteIds)
    }

    override suspend fun writeSyncMeta(uid: String, noteCount: Int, platform: String) {
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/users/$uid/_meta/sync" +
                "?updateMask.fieldPaths=lastSyncAt" +
                "&updateMask.fieldPaths=noteCount" +
                "&updateMask.fieldPaths=platform"
            val body = codec.syncMetaBody(noteCount, platform, DateUtils.currentTimeMillis())
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
                commit(chunk.map { codec.deleteWrite(uid, collection, it) })
            }
        }
    }

    private companion object {
        /** Firestore caps a single `:commit` at 500 writes. */
        const val COMMIT_LIMIT = 400
        const val PAGE_SIZE = 300
        const val REQUEST_TIMEOUT_SECONDS = 30L
    }
}
