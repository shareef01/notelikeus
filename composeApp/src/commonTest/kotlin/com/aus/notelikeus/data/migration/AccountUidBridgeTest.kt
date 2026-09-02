package com.aus.notelikeus.data.migration

import com.aus.notelikeus.data.sync.FakeNoteSyncStateStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountUidBridgeTest {

  @Test
  fun linkedFirebaseUidAndSupabaseUuidAreSameAccount() {
    val store = FakeNoteSyncStateStore()
    val bridge = AccountUidBridge(store)
    val firebaseUid = "firebaseUid28charsabcdefghij"
    val supabaseUid = "11111111-2222-4333-8444-555555555555"

    bridge.linkAccounts(firebaseUid, supabaseUid)

    assertTrue(bridge.accountsMatch(firebaseUid, supabaseUid))
    assertTrue(bridge.accountsMatch(supabaseUid, firebaseUid))
    assertFalse(bridge.accountsMatch("other-user", supabaseUid))
  }
}
