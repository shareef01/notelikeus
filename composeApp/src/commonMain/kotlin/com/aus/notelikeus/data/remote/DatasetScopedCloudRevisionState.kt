package com.aus.notelikeus.data.remote

/**
 * Cloud-derived state that belongs to one *local dataset* and must not outlive it — D11.
 *
 * The local account database is replaced whole: a same-UID isolation, a wipe-and-sign-in, or a recovery
 * that finishes an interrupted isolation. Everything derived from the cloud in the generation being
 * replaced describes *that* library — its notes, its revisions, its conflict outcomes — and none of it
 * may parameterize work in the next generation, even when the account uid is identical. Note ids are
 * reused across generations (the replacement library has a note 42 too), so uid equality can never be
 * the test.
 *
 * The boundary is the dataset isolation itself, not a sign-out: signing back in to the same library
 * without replacing it keeps its revisions, because those revisions still describe the notes on disk.
 * Implementations clear in-memory state only; nothing here is persisted, so a restart starts empty.
 */
interface DatasetScopedCloudRevisionState {

    /**
     * Invalidates everything learned from the previous dataset's cloud state.
     *
     * Called at the dataset boundary, before the replacement generation can run any work. Idempotent:
     * recovery re-runs isolation, and a second clear over an empty map is the cheap way to guarantee the
     * boundary is re-entered rather than half-applied.
     *
     * Suspending because the implementation must advance the epoch and discard the state atomically: the
     * reset is serialized with every revision read and write, so no continuation can pass a generation
     * check on the departing dataset and then mutate the replacement's state.
     */
    suspend fun clearDatasetScopedState() {

    }

    /**
     * The dataset generation that is active right now.
     *
     * Read by the identity capture, while the originating [LocalCommitToken] is still authoritative, so a
     * logical operation carries the generation it *started* under. Asking later — at the physical RPC —
     * would let an operation issued before an isolation adopt the replacement dataset's generation, which
     * is exactly what the per-operation binding exists to prevent.
     */
    suspend fun currentRevisionEpoch(): Long
}
