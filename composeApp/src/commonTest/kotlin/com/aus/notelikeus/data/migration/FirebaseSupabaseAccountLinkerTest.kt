package com.aus.notelikeus.data.migration

import com.aus.notelikeus.data.remote.RemoteBackend
import com.aus.notelikeus.data.remote.SupabaseRpcClient
import com.aus.notelikeus.data.sync.FakeNoteSyncStateStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE_FIREBASE = "aliceFirebaseUid28charsabcd"
private const val BOB_FIREBASE = "bobFirebaseUid28charsabcdef"
private const val ALICE_SUPABASE = "11111111-1111-4111-8111-111111111111"
private const val BOB_SUPABASE = "22222222-2222-4222-8222-222222222222"

private class RecordingRpc(private val serverMapping: String? = null) : SupabaseRpcClient {
    val calls = mutableListOf<Pair<String, JsonObject>>()

    override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject {
        calls += functionName to body
        return buildJsonObject { }
    }

    override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement {
        calls += functionName to body
        if (functionName == "get_firebase_uid_link" && serverMapping != null) {
            return buildJsonObject {
                put("firebase_uid", JsonPrimitive(serverMapping))
                put("verified", JsonPrimitive(false))
            }
        }
        return buildJsonObject { }
    }

    fun linkedUids() = calls.filter { it.first == "link_firebase_uid" }
        .mapNotNull { (_, body) -> (body["p_firebase_uid"] as? JsonPrimitive)?.content }
}

private fun linker(
    store: FakeNoteSyncStateStore,
    rpc: SupabaseRpcClient?,
    activeFirebaseUid: String? = null,
    session: FirebaseSessionIdentity? = FirebaseSessionIdentity { activeFirebaseUid },
) = FirebaseSupabaseAccountLinker(
    remoteBackend = RemoteBackend.SUPABASE,
    accountUidBridge = AccountUidBridge(store),
    syncStateStore = store,
    supabaseRpc = rpc,
    firebaseSession = session,
)

class FirebaseSupabaseAccountLinkerTest {

    /**
     * The shared-device case. Alice used this device with Firebase, so `lastMergedUserId` still
     * names her. Bob signs in to Supabase with no Firebase session at all — nothing on the device
     * says the uid is his, so it must not be claimed on his behalf.
     */
    @Test
    fun aBreadcrumbFromAPreviousUserIsNotClaimed() = runTest {
        val store = FakeNoteSyncStateStore()
        store.setLastMergedUserId(ALICE_FIREBASE)
        val rpc = RecordingRpc()

        linker(store, rpc, activeFirebaseUid = null).linkAfterSupabaseSignIn(BOB_SUPABASE)

        assertTrue(rpc.linkedUids().isEmpty(), "no uid should have been claimed")
        assertNull(store.linkedFirebaseUid())
    }

    /** With a live Firebase session for the same uid, the breadcrumb is corroborated. */
    @Test
    fun aBreadcrumbCorroboratedByALiveSessionIsClaimed() = runTest {
        val store = FakeNoteSyncStateStore()
        store.setLastMergedUserId(ALICE_FIREBASE)
        val rpc = RecordingRpc()

        linker(store, rpc, activeFirebaseUid = ALICE_FIREBASE)
            .linkAfterSupabaseSignIn(ALICE_SUPABASE)

        assertEquals(listOf(ALICE_FIREBASE), rpc.linkedUids())
        assertEquals(ALICE_FIREBASE, store.linkedFirebaseUid())
    }

    /** A live session outranks a stale breadcrumb naming somebody else. */
    @Test
    fun aLiveSessionWinsOverAStaleBreadcrumb() = runTest {
        val store = FakeNoteSyncStateStore()
        store.setLastMergedUserId(ALICE_FIREBASE)
        val rpc = RecordingRpc()

        linker(store, rpc, activeFirebaseUid = BOB_FIREBASE).linkAfterSupabaseSignIn(BOB_SUPABASE)

        assertEquals(listOf(BOB_FIREBASE), rpc.linkedUids())
    }

    /** A link this device already recorded is not a new claim. */
    @Test
    fun anExistingLocalLinkKeepsWorkingOffline() = runTest {
        val store = FakeNoteSyncStateStore()
        store.setLinkedFirebaseUid(ALICE_FIREBASE)
        val rpc = RecordingRpc()

        linker(store, rpc, activeFirebaseUid = null).linkAfterSupabaseSignIn(ALICE_SUPABASE)

        assertEquals(listOf(ALICE_FIREBASE), rpc.linkedUids())
    }

    /** A mapping the server already holds for this account is likewise an existing claim. */
    @Test
    fun aMappingTheServerAlreadyHoldsIsHonoured() = runTest {
        val store = FakeNoteSyncStateStore()
        val rpc = RecordingRpc(serverMapping = ALICE_FIREBASE)

        linker(store, rpc, activeFirebaseUid = null).linkAfterSupabaseSignIn(ALICE_SUPABASE)

        assertEquals(listOf(ALICE_FIREBASE), rpc.linkedUids())
        assertEquals(ALICE_FIREBASE, store.linkedFirebaseUid())
    }

    /** No way to see a Firebase session means breadcrumbs are refused, not trusted. */
    @Test
    fun aPlatformWithNoSessionVisibilityFailsClosed() = runTest {
        val store = FakeNoteSyncStateStore()
        store.setLastMergedUserId(ALICE_FIREBASE)
        val rpc = RecordingRpc()

        linker(store, rpc, session = null).linkAfterSupabaseSignIn(BOB_SUPABASE)

        assertTrue(rpc.linkedUids().isEmpty())
    }

    /** Firebase is still the production backend; the linker must do nothing there. */
    @Test
    fun nothingHappensWhenFirebaseIsTheRemoteBackend() = runTest {
        val store = FakeNoteSyncStateStore()
        store.setLastMergedUserId(ALICE_FIREBASE)
        val rpc = RecordingRpc()

        FirebaseSupabaseAccountLinker(
            remoteBackend = RemoteBackend.FIREBASE,
            accountUidBridge = AccountUidBridge(store),
            syncStateStore = store,
            supabaseRpc = rpc,
            firebaseSession = { ALICE_FIREBASE },
        ).linkAfterSupabaseSignIn(ALICE_SUPABASE)

        assertTrue(rpc.calls.isEmpty())
    }

    /** A Supabase-only user has no legacy identity to link. */
    @Test
    fun aSupabaseOnlyUserLinksNothing() = runTest {
        val store = FakeNoteSyncStateStore()
        val rpc = RecordingRpc()

        linker(store, rpc, activeFirebaseUid = null).linkAfterSupabaseSignIn(ALICE_SUPABASE)

        assertTrue(rpc.linkedUids().isEmpty())
    }
}
