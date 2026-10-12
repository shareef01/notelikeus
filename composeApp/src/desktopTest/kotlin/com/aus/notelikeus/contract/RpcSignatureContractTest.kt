package com.aus.notelikeus.contract

import com.aus.notelikeus.data.attachments.ATTACHMENT_R2_PREFIX
import com.aus.notelikeus.data.remote.OperationRemoteIdentity
import com.aus.notelikeus.data.remote.SupabaseNoteTransport
import com.aus.notelikeus.data.remote.SupabaseRpcClient
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The argument objects the Kotlin transport sends must name a function the database actually has.
 *
 * PostgREST resolves `/rpc/<name>` by the *set of argument names*: an extra key, or a missing one
 * without a default, matches no overload and the call fails with PGRST202 before any SQL runs. A test
 * double that answers any body hides that completely, which is how `restore_note` came to be sent an
 * argument only `apply_note_change` accepts. So the signatures here are read from the migrations
 * themselves, and the bodies from the real transport.
 */
class RpcSignatureContractTest {

    private data class Overload(val required: Set<String>, val all: Set<String>)

    @Test
    fun `apply_note_change arguments match a deployed overload`() = runTest {
        val rpc = RecordingRpc()
        SupabaseNoteTransport(rpc).putNotes(IDENTITY, listOf(note()))
        assertMatchesAnOverload(rpc.single("apply_note_change"), "apply_note_change")
    }

    @Test
    fun `restore_note arguments match a deployed overload`() = runTest {
        val rpc = RecordingRpc()
        SupabaseNoteTransport(rpc).restoreNote(IDENTITY, note())
        assertMatchesAnOverload(rpc.single("restore_note"), "restore_note")
    }

    @Test
    fun `apply_note_delete arguments match a deployed overload`() = runTest {
        val rpc = RecordingRpc()
        SupabaseNoteTransport(rpc).deleteNote(IDENTITY, NOTE_ID, 3L)
        assertMatchesAnOverload(rpc.single("apply_note_delete"), "apply_note_delete")
    }

    private fun assertMatchesAnOverload(body: JsonObject, function: String) {
        val overloads = deployedOverloads()[function]
            ?: fail("no migration defines public.$function")
        val sent = body.keys
        assertTrue(
            overloads.any { sent.containsAll(it.required) && it.all.containsAll(sent) },
            "public.$function has no overload accepting exactly ${sent.sorted()}; " +
                "deployed parameter sets: ${overloads.map { it.all.sorted() }}",
        )
    }

    /** A note carrying a remote attachment, so any attachment-id argument is actually sent. */
    private fun note() = Note(
        id = NOTE_ID,
        title = "note",
        content = "body",
        timestamp = 1_000L,
        color = 0,
        attachments = listOf(
            Attachment(
                id = "att-remote",
                noteId = NOTE_ID,
                storagePath = "$ATTACHMENT_R2_PREFIX owners/$OWNER/notes/$NOTE_ID/att-remote",
                type = "image",
                mimeType = "image/png",
                sizeBytes = 3,
            ),
        ),
    )

    private class RecordingRpc : SupabaseRpcClient {
        val calls = mutableListOf<Pair<String, JsonObject>>()

        fun single(function: String): JsonObject =
            calls.singleOrNull { it.first == function }?.second
                ?: fail("expected one $function call, saw ${calls.map { it.first }}")

        override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject =
            fail("live-session RPC $functionName used on an identity-bound path")

        override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement =
            fail("live-session RPC $functionName used on an identity-bound path")

        override suspend fun callRpcElement(
            identity: OperationRemoteIdentity,
            functionName: String,
            body: JsonObject,
        ): JsonElement {
            calls += functionName to body
            return JsonObject(
                mapOf(
                    "status" to JsonPrimitive("applied"),
                    "revision" to JsonPrimitive(7),
                    "server_updated_at" to JsonPrimitive(2_000L),
                ),
            )
        }
    }

    private companion object {
        const val OWNER = "11111111-1111-1111-1111-111111111111"
        const val NOTE_ID = 42L
        val IDENTITY = OperationRemoteIdentity(OWNER, "token-a")

        private val createFunction =
            Regex("""CREATE\s+(?:OR\s+REPLACE\s+)?FUNCTION\s+public\.(\w+)\s*\(""", RegexOption.IGNORE_CASE)
        private val dropFunction =
            Regex("""DROP\s+FUNCTION\s+(?:IF\s+EXISTS\s+)?public\.(\w+)\s*\(""", RegexOption.IGNORE_CASE)

        /**
         * Every `public` function overload the migrations leave behind, replayed in migration order.
         *
         * An overload is identified the way Postgres identifies it — by name and argument types — so a
         * later `CREATE OR REPLACE` of the same types replaces it and a `DROP FUNCTION` removes it.
         */
        fun deployedOverloads(): Map<String, List<Overload>> {
            val byIdentity = linkedMapOf<Pair<String, String>, Overload>()
            val migrations = migrationsDir().listFiles { file -> file.name.endsWith(".sql") }!!
                .sortedBy { it.name }
            for (file in migrations) {
                val sql = file.readText()
                val events = (createFunction.findAll(sql).map { it to true } +
                    dropFunction.findAll(sql).map { it to false })
                    .sortedBy { it.first.range.first }
                for ((match, isCreate) in events) {
                    val name = match.groupValues[1].lowercase()
                    val params = splitTopLevel(parenthesised(sql, match.range.last))
                    val types = params.joinToString(",") { typeOf(it, named = isCreate) }
                    if (isCreate) {
                        val names = params.map { it.trim().split(Regex("\\s+")).first().lowercase() }
                        val required = params.filterNot { it.contains(Regex("\\bDEFAULT\\b|=", RegexOption.IGNORE_CASE)) }
                            .map { it.trim().split(Regex("\\s+")).first().lowercase() }
                        byIdentity[name to types] = Overload(required.toSet(), names.toSet())
                    } else {
                        byIdentity.remove(name to types)
                    }
                }
            }
            return byIdentity.entries.groupBy({ it.key.first }, { it.value })
        }

        /** The text between the `(` at [openIndex] and its matching `)`. */
        private fun parenthesised(sql: String, openIndex: Int): String {
            var depth = 0
            for (i in openIndex until sql.length) {
                when (sql[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return sql.substring(openIndex + 1, i)
                }
            }
            error("unbalanced parameter list at offset $openIndex")
        }

        private fun splitTopLevel(list: String): List<String> {
            if (list.isBlank()) return emptyList()
            val parts = mutableListOf<String>()
            var depth = 0
            var start = 0
            list.forEachIndexed { i, c ->
                when (c) {
                    '(' -> depth++
                    ')' -> depth--
                    ',' -> if (depth == 0) {
                        parts += list.substring(start, i)
                        start = i + 1
                    }
                }
            }
            parts += list.substring(start)
            return parts.map { it.trim() }.filter { it.isNotEmpty() }
        }

        /** The normalised type of one parameter; `DROP FUNCTION` lists carry types only. */
        private fun typeOf(param: String, named: Boolean): String {
            val withoutDefault = param.split(Regex("\\bDEFAULT\\b|=", RegexOption.IGNORE_CASE)).first().trim()
            val tokens = withoutDefault.split(Regex("\\s+"))
            val type = if (named && tokens.size > 1) tokens.drop(1) else tokens
            return type.joinToString(" ").uppercase()
        }

        private fun migrationsDir(): File {
            var dir: File? = File(".").canonicalFile
            repeat(12) {
                val candidate = dir?.resolve("supabase/migrations")
                if (candidate != null && candidate.isDirectory) return candidate
                dir = dir?.parentFile
            }
            error("No supabase/migrations within 12 levels of ${File(".").canonicalPath}")
        }
    }
}
