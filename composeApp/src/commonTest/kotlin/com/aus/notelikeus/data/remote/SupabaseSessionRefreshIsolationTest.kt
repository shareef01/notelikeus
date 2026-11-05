package com.aus.notelikeus.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SupabaseSessionRefreshIsolationTest {
    private fun session(owner: String, expiry: Long = 0) =
        SupabaseAuthSession("$owner-token", "$owner-refresh", owner, null, expiry)

    @Test
    fun refreshCannotRestoreSignedOutAccount() = runTest {
        val store = SupabaseSessionStore().apply { save(session("A")) }
        val response = CompletableDeferred<SupabaseAuthSession>()
        val refreshing = async(start = CoroutineStart.UNDISPATCHED) { store.validAccessToken { response.await() } }
        store.clear()
        response.complete(session("A", Long.MAX_VALUE))
        assertNull(refreshing.await())
        assertNull(store.userId())
    }

    @Test
    fun refreshCannotReplaceNewAccountOrSameOwnerNewSession() = runTest {
        for (replacement in listOf("B", "A")) {
            val store = SupabaseSessionStore().apply { save(session("A")) }
            val response = CompletableDeferred<SupabaseAuthSession>()
            val refreshing = async(start = CoroutineStart.UNDISPATCHED) { store.validAccessToken { response.await() } }
            store.save(session(replacement, Long.MAX_VALUE).copy(accessToken = "new-session"))
            response.complete(session("A", Long.MAX_VALUE))
            assertNull(refreshing.await())
            assertEquals(replacement, store.userId())
            assertEquals("new-session", store.validAccessToken { error("must not refresh") })
        }
    }
}
