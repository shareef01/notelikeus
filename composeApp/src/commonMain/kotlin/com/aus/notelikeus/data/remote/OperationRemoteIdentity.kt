package com.aus.notelikeus.data.remote

/**
 * The account identity one logical operation executes under, captured once for that operation.
 *
 * This exists because of F-1 from the D11 adversarial audit: both `SupabaseRpcClient`
 * implementations resolved the bearer token **at send time** from `SupabaseAccessTokenProvider`,
 * i.e. from whatever session happened to be live when the request was finally built. A request
 * authorised under account A but sent after account B signed in therefore went out bearing **B's**
 * token while its arguments still carried A's note ids — and colliding ids across accounts are the
 * normal case, not a pathological one. The generation grant could not help: the grant authorises
 * *when* a mutation may start, and says nothing about *whose credentials* it uses.
 *
 * The value is deliberately plain, immutable and operation-scoped:
 *
 *  - it is not stored in `LocalCommitGate`, and nothing global holds it;
 *  - it is never refreshed or re-read at send time — an expired token must fail authentication
 *    rather than silently borrow the live session's;
 *  - [ownerId] travels *with* the credential, so a protected call cannot be told to act for one
 *    account while authenticating as another: the identity-bound transport methods take this value
 *    instead of a separate owner argument, which removes the possibility by construction.
 *
 * It mirrors the accepted R2 model (`AttachmentRemoteContext`) on purpose — same idea, separate
 * type, so the two services stay independent.
 */
data class OperationRemoteIdentity(
    /** The account this operation belongs to. Never read from the live session after capture. */
    val ownerId: String,
    /** The bearer token captured for that account. Never refreshed against the live session. */
    val accessToken: String,
    /**
     * The dataset generation this operation's revision knowledge belongs to — null when the capture did
     * not bind one (uid-only and test call sites), which reads the active generation instead.
     *
     * Captured with the credential, for the same reason the credential is captured: both describe what
     * this operation may act on, and both would otherwise be resolved later — at send time for the token,
     * at RPC time for the dataset. Isolation replaces the local dataset while an issued operation may
     * still run; without this binding that operation would ask which dataset is current *then* and so act
     * on the replacement's revision state, consuming or discarding revisions it never learned and never
     * was entitled to touch.
     */
    val revisionEpoch: Long? = null,
)
