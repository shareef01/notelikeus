package com.aus.notelikeus.data.sync

/**
 * A destructive cloud-wipe request the user accepted, and that the product has not yet proved finished.
 *
 * F-8: "sign out and delete my cloud notes" is accepted, the remote delete then fails, is interrupted, or
 * its result is ambiguous — and nothing durable said the request ever existed. The user could not tell
 * whether their cloud data was gone, and nothing would finish the job. This record is that memory.
 *
 * It deliberately carries no credential: a retry reacquires the bearer coherently for [ownerUid] and
 * refuses to run at all when somebody else is signed in. It is filed by account, not by local dataset
 * incarnation, because the intent outlives the local dataset it was made from — so it must not live in
 * state that dataset isolation erases.
 */
data class PendingCloudWipeIntent(
    /** The account whose cloud data was asked to be deleted. The only account that may execute this. */
    val ownerUid: String,
    /** When the request was accepted, for diagnostics and for a future policy (backoff, expiry). */
    val requestedAtMillis: Long,
)

/**
 * Durable storage for [PendingCloudWipeIntent], keyed by the account each request targets.
 *
 * Persist-first is the contract: [CloudWipeCoordinator] writes the intent *before* any destructive remote
 * call, and refuses to start one when the write fails, because a wipe with no record is exactly the state
 * F-8 describes. Implementations must survive process death, sign-out and local account isolation, which is
 * why the production implementations live on the platform side (preferences/file) rather than in the
 * account-scoped sync state that isolation clears.
 *
 * **Ownership is part of the storage model, not a check layered on top of it.** A request is filed under the
 * account it targets and replaces only that account's record, and a completion removes only that account's
 * record. One account's accepted deletion can therefore never be silently dropped — or cleared — by another
 * account signing in, requesting its own wipe, or finishing one. A single-slot store cannot express that,
 * which is why [read] returns every pending request rather than the one that happened to be written last.
 */
interface PendingCloudWipeIntentStore {

    /** Every account currently owed a destructive wipe, in no particular order. */
    suspend fun read(): List<PendingCloudWipeIntent>

    /** The request filed for [ownerUid], or null when that account is not owed one. */
    suspend fun find(ownerUid: String): PendingCloudWipeIntent?

    /**
     * Files (or updates) [intent] under [PendingCloudWipeIntent.ownerUid], leaving every other account's
     * record untouched. An existing record for the same owner keeps its `requestedAtMillis`: the obligation
     * is continuous, and a repeat request must not make the pending state look newer than it is.
     */
    suspend fun write(intent: PendingCloudWipeIntent)

    /**
     * Removes [ownerUid]'s record — and only that account's. Called only when destructive completion is
     * authoritative for that account.
     */
    suspend fun clear(ownerUid: String)
}
