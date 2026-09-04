package com.aus.notelikeus.data.migration

import com.aus.notelikeus.data.remote.RemoteBackend
import com.aus.notelikeus.data.remote.SupabaseRpcClient
import com.aus.notelikeus.data.sync.NoteSyncStateStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Evidence that this device is holding an authenticated Firebase session right now.
 *
 * A uid read from device-local state proves nothing: that state outlives sign-out, so on a shared
 * device it names whoever used the app last. A live session names whoever is signed in.
 */
fun interface FirebaseSessionIdentity {
    /** uid of the live Firebase session, or null when nobody is signed in to Firebase. */
    fun activeFirebaseUid(): String?
}

/**
 * Where a candidate Firebase uid came from. Only [FIREBASE_SESSION] is self-proving; the rest are
 * device-local breadcrumbs, or a claim this account already made.
 */
internal enum class FirebaseUidSource {
    /** A live Firebase session on this device. */
    FIREBASE_SESSION,

    /** A link this device previously recorded for this account. */
    LOCAL_LINK,

    /** A mapping the server already holds for this Supabase account. */
    SERVER_MAPPING,

    /** `lastMergedUserId` — survives sign-out, so it describes the previous user on a shared device. */
    DEVICE_BREADCRUMB,
}

internal data class FirebaseUidCandidate(
    val firebaseUid: String,
    val source: FirebaseUidSource,
)

/**
 * Links a legacy Firebase uid to a Supabase uuid after Supabase sign-in (Phase 6).
 *
 * The invariant, matching the web client: a Supabase account may claim Firebase uid F only when the
 * app can show the same user owns authenticated Firebase identity F. `link_firebase_uid` accepts
 * whatever uid it is sent, so this class is where that is decided on Kotlin platforms.
 *
 * Verified linking (handing a Firebase ID token to the attachments Worker, which checks its RS256
 * signature before recording a proven link) is web-only for now — see BACKEND_MIGRATION.md. Kotlin
 * writes unverified claims, which are deliberately not exclusive and so cannot lock anyone out.
 */
class FirebaseSupabaseAccountLinker(
    private val remoteBackend: RemoteBackend,
    private val accountUidBridge: AccountUidBridge,
    private val syncStateStore: NoteSyncStateStore,
    private val supabaseRpc: SupabaseRpcClient?,
    /**
     * Null when the platform cannot report a Firebase session. That is the fail-closed posture: a
     * breadcrumb-sourced uid is then never claimed, because nothing can corroborate it.
     */
    private val firebaseSession: FirebaseSessionIdentity? = null,
) {
    suspend fun linkAfterSupabaseSignIn(supabaseUid: String) {
        if (remoteBackend != RemoteBackend.SUPABASE) return

        val candidate = resolveCandidate(supabaseUid) ?: return
        if (!mayClaim(candidate)) return

        accountUidBridge.linkAccounts(candidate.firebaseUid, supabaseUid)
        registerMappingOnServer(candidate.firebaseUid)
    }

    private suspend fun resolveCandidate(supabaseUid: String): FirebaseUidCandidate? {
        // A live session wins: when the user is still signed in to Firebase, that uid is the one
        // that is actually theirs, whatever stale state the device is carrying.
        firebaseSession?.activeFirebaseUid()
            ?.takeIf { it.isNotBlank() && it != supabaseUid }
            ?.let { return FirebaseUidCandidate(it, FirebaseUidSource.FIREBASE_SESSION) }

        syncStateStore.linkedFirebaseUid()
            ?.let { return FirebaseUidCandidate(it, FirebaseUidSource.LOCAL_LINK) }

        fetchServerMapping()
            ?.let { return FirebaseUidCandidate(it, FirebaseUidSource.SERVER_MAPPING) }

        val last = syncStateStore.lastMergedUserId()
        if (last != null && AccountUidPatterns.isLikelyFirebaseUid(last) && last != supabaseUid) {
            return FirebaseUidCandidate(last, FirebaseUidSource.DEVICE_BREADCRUMB)
        }
        return null
    }

    /**
     * A claim this account already holds is not a new claim — it is safe to keep honouring offline.
     * A breadcrumb is only usable once a live Firebase session for the same uid corroborates it.
     */
    internal fun mayClaim(candidate: FirebaseUidCandidate): Boolean = when (candidate.source) {
        FirebaseUidSource.FIREBASE_SESSION,
        FirebaseUidSource.LOCAL_LINK,
        FirebaseUidSource.SERVER_MAPPING,
        -> true

        FirebaseUidSource.DEVICE_BREADCRUMB ->
            firebaseSession?.activeFirebaseUid() == candidate.firebaseUid
    }

    /** The mapping the server already holds for the signed-in account, if any. */
    private suspend fun fetchServerMapping(): String? {
        val rpc = supabaseRpc ?: return null
        return runCatching {
            rpc.callRpcElement("get_firebase_uid_link")
                .jsonObject["firebase_uid"]
                ?.jsonPrimitive
                ?.content
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
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
