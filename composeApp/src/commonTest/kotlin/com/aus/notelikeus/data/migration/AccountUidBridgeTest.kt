package com.aus.notelikeus.data.migration

import com.aus.notelikeus.data.sync.FakeNoteSyncStateStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountUidBridgeTest {

    @Test
    fun equalIdsAreTheSameAccount() {
        val bridge = AccountUidBridge(FakeNoteSyncStateStore())
        assertTrue(bridge.accountsMatch(null, "alice"))
        assertTrue(bridge.accountsMatch("alice", "alice"))
        assertFalse(bridge.accountsMatch("alice", "bob"))
    }
}
