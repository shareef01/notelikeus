package com.aus.notelikeus.data.remote

import com.aus.notelikeus.domain.repository.LocalCommitResult
import com.aus.notelikeus.domain.repository.LocalCommitToken
import com.aus.notelikeus.data.sync.LocalCommitGate

/**
 * Captures the [OperationRemoteIdentity] a protected note operation will execute under — R13A of the
 * D11 remediation, closing F-1.
 *
 * Capturing is not a plain lookup, because the credential read **suspends**: the access-token
 * provider may refresh, and a refresh is exactly where an account switch can land. So the sequence
 * is deliberately:
 *
 * ```
 * originating generation N already known (LocalCommitToken)
 *     -> read the live session's owner      (no network)
 *     -> read the access token              (may suspend, may refresh)
 *     -> read the live session's owner again
 *     -> if the owner changed across that suspension -> refuse (no identity)
 *     -> revalidate generation N under LocalCommitGate -> refuse if stale
 *     -> return OperationRemoteIdentity(owner, token)
 * ```
 *
 * Two properties come out of that ordering:
 *
 *  - **coherence.** `SupabaseAccessTokenProvider` returns a bare token with no owner binding, so the
 *    owner is proved by a before/after read around the credential acquisition rather than assumed.
 *    A session that moved during a refresh yields a mismatched pair and is refused instead of
 *    producing `Identity(A, tokenB)` — the F-1 shape itself.
 *  - **generation authority after the suspension**, never before. The gate is taken *last* and only
 *    for the validation, so no credential refresh, session I/O or network ever runs under
 *    `LocalCommitGate.mutex`; isolation can therefore never wait on an auth round trip.
 *
 * A refusal returns `null`: the caller must not fall back to the live session's token. Failing closed
 * is the point — an expired or revoked capture must produce an authentication failure for the
 * *originating* account, never a silent substitution for whichever account is current.
 */
class RemoteIdentityProvider(
    private val accessTokenProvider: SupabaseAccessTokenProvider,
    /**
     * The live session's owner id. Injected as a function rather than a session store so this class
     * stays free of session/credential architecture, and so the before/after coherence read is
     * testable without a live backend.
     */
    private val sessionOwnerId: suspend () -> String?,
    /**
     * The active dataset generation, read *inside* the gate below so the identity carries the generation
     * whose token validated it. Injected as a function for the same reason as the session read, and
     * defaulted to no binding so constructions that have no revision state stay unchanged.
     */
    private val revisionEpochProvider: (suspend () -> Long)? = null,
) {

    suspend fun capture(commitToken: LocalCommitToken): OperationRemoteIdentity? {
        val ownerBefore = sessionOwnerId() ?: return null
        val token = accessTokenProvider.accessToken() ?: return null
        val ownerAfter = sessionOwnerId() ?: return null
        if (ownerBefore != ownerAfter) return null

        // Taken last, and only for the decision: the credential work above is deliberately outside it.
        // The revision epoch is read *here*, in the same held section, so an operation's dataset binding
        // is decided by the same token validation that authorises it: after that section ends an
        // isolation may run, and a capture taken later would belong to the replacement dataset.
        // Lock order stays one-way — LocalCommitGate, then the revision-state mutex.
        return when (val epoch = LocalCommitGate.commit(commitToken) { revisionEpochProvider?.invoke() }) {
            is LocalCommitResult.Applied -> OperationRemoteIdentity(ownerAfter, token, epoch.value)
            LocalCommitResult.StaleGeneration -> null
        }
    }
}
