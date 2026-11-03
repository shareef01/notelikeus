package com.aus.notelikeus.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AUD-004: the account's note limits are reported as something to act on, not a raw HTTP body. */
class NoteLimitMessageTest {
    private fun refusal(body: String) = SupabaseTransportException("apply_note_change", 400, body)

    @Test
    fun `the database sentence is shown without its prefix`() {
        val error = refusal(
            """{"code":"NL005","message":"account_note_limit: this account has reached its limit of 10000 notes. Delete notes you no longer need to sync new ones."}""",
        )

        assertEquals(
            "This account has reached its limit of 10000 notes. Delete notes you no longer need to sync new ones.",
            error.noteLimitMessage,
        )
    }

    @Test
    fun `the code alone still gives a general sentence`() {
        assertTrue(refusal("""{"code":"NL005"}""").noteLimitMessage!!.contains("note limit"))
    }

    @Test
    fun `other failures are not the note limit`() {
        listOf(
            """{"code":"22023","message":"note title exceeds 2000 characters"}""",
            """{"code":"NL003","message":"account_deleted: gone"}""",
            """{"code":"22023","message":"a title that mentions account_note_limit: in passing"}""",
            "upstream",
        ).forEach { assertNull(refusal(it).noteLimitMessage, it) }
    }
}
