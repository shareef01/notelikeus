package com.aus.notelikeus.domain.repository

/**
 * The identity of the local-dataset incarnation a piece of work belongs to.
 *
 * This is a **repository contract type**, so it lives in the domain layer: `NoteRepository` exposes
 * it, which means the domain must own it. The implementation that produces and validates it —
 * `com.aus.notelikeus.data.sync.LocalCommitGate` — is an infrastructure concern and depends on this,
 * not the other way round.
 *
 * [generation] is the load-bearing part. A `uid` alone cannot answer the question this exists for:
 * after `A → sign out → A`, the uid is identical on both sides of the boundary, but the second
 * session is a different local dataset and work captured in the first must not commit into it.
 * [initiatingUid] is carried so the repository fence can reason about *which* account the intent
 * belonged to, and for diagnostics; generation validation deliberately does not reject on it,
 * because a `null` (guest) initiator legitimately commits into the first account that adopts the
 * guest library.
 *
 * **This is not a coherent account snapshot on its own.** A caller that reads the session uid and
 * then captures the generation does so in two steps, so an account transition can land between
 * them. Coherence between the initiating-uid read and generation capture is an editor-integration
 * concern, deliberately left open rather than papered over here.
 */
data class LocalCommitToken(
    val generation: Long,
    val initiatingUid: String?,
)

/**
 * Outcome of a validated local commit.
 *
 * [StaleGeneration] is a deliberate account-boundary refusal, **not** a persistence failure: a save
 * that loses this way must not be reported to the new account as "save failed". Exceptions raised by
 * the mutation block are never converted into it — they propagate.
 */
sealed interface LocalCommitResult<out T> {
    data class Applied<T>(val value: T) : LocalCommitResult<T>
    data object StaleGeneration : LocalCommitResult<Nothing>
}

/**
 * Supplies the account/dataset identity a piece of user intent belongs to.
 *
 * A domain-facing seam: the editor depends on this, never on the session manager or the gate, so
 * the capture stays infrastructure's job while the contract stays in the layer that uses it.
 */
fun interface LocalCommitTokenProvider {
    fun capture(): LocalCommitToken
}
