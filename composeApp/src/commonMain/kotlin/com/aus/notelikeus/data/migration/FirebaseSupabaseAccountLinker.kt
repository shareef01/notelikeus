package com.aus.notelikeus.data.migration

import com.aus.notelikeus.data.remote.RemoteBackend
import com.aus.notelikeus.data.remote.SupabaseRpcClient
import com.aus.notelikeus.data.sync.NoteSyncStateStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Links a legacy Firebase uid to a Supabase uuid after Supabase sign-in (Phase 6).
 */
class FirebaseSupabaseAccountLinker(
    private val remoteBackend: RemoteBackend,
    private val accountUidBridge: AccountUidBridge,
    private val syncStateStore: NoteSyncStateStore,
    private val supabaseRpc: SupabaseRpcClient?,
) {
    suspend fun linkAfterSupabaseSignIn(supabaseUid: String) {
        if (remoteBackend != RemoteBackend.SUPABASE) return

        val candidateFirebaseUid = resolveCandidateFirebaseUid(supabaseUid) ?: return
        accountUidBridge.linkAccounts(candidateFirebaseUid, supabaseUid)
        registerMappingOnServer(candidateFirebaseUid)
    }

    private fun resolveCandidateFirebaseUid(supabaseUid: String): String? {
        syncStateStore.linkedFirebaseUid()?.let { return it }
        val last = syncStateStore.lastMergedUserId()
        if (last != null && AccountUidPatterns.isLikelyFirebaseUid(last) && last != supabaseUid) {
            return last
        }
        return null
    }

    private suspend fun registerMappingOnServer(firebaseUid: String) {
        val rpc = supabaseRpc ?: return
        runCatching {
            rpc.callRpc(
                "link_firebase_uid",
                buildJsonObject { put("p_firebase_uid", firebaseUid) },
            )
        }
    }
}
