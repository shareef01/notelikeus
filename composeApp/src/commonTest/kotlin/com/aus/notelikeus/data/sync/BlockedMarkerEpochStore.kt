package com.aus.notelikeus.data.sync

import kotlinx.coroutines.CompletableDeferred

/**
 * A durable epoch store whose marker write can be observed and held, for R16.2's publication lanes.
 *
 * The ordering R16.2 is about is *inside* `DatasetEpochAuthority.beginIsolation`: it persists the
 * durable `Isolating` marker and publishes the in-memory mirror that answers every in-process
 * quarantine question. To pin that order deterministically a test has to be able to stand at the one
 * instant that matters — "this transition is durable, and `beginIsolation` has not returned yet" —
 * which is what [blockNextSave] provides: the write really lands in the durable backing, the test is
 * signalled, and only then does `save` suspend until it is released. No sleeps, no racing threads.
 *
 * [failNextSave] is the other half: a write that fails *before* touching the backing must leave no
 * marker behind, so the authority can restore the dataset that is still current.
 *
 * [failAfterCommit] models the case that makes "save threw, therefore nothing committed" an
 * *assumption* rather than a fact: the write lands durably and the caller still sees an exception —
 * a post-commit `CancellationException` (which `DataStore.edit` can surface after the file swap) or
 * any ordinary failure raised by an implementation after its commit. The durable backing really does
 * contain the marker in this case, so a caller that rolls its in-memory quarantine back is wrong, and
 * only a read of the backing can tell the difference.
 *
 * Both hooks apply to the **marker** write only — a write whose state is `Isolating`. The authority's
 * first write is the initial epoch it mints when the store has none, and that one must go through
 * untouched: otherwise a lane would be blocking the wrong write and its preconditions would be
 * measuring the eager-initialisation path instead of the transition.
 */
class BlockedMarkerEpochStore(
    private val delegate: InMemoryDatasetEpochStore = InMemoryDatasetEpochStore(),
) : DatasetEpochStore {

    /** The durable state, as a restarted process would read it. */
    val durable: DatasetPending get() = delegate.durable

    /** Every value `save` was asked to persist, successful or not. */
    val writes: List<DatasetPending> get() = delegate.writes

    /** Counts durable reads, so "no I/O under the gate" style probes keep working through this double. */
    val loadCount: Int get() = delegate.loadCount

    /** Completes once a marker write has landed durably, before `save` returns. */
    val markerCommitted = CompletableDeferred<Unit>()

    /** Release for a held [blockNextSave] write. */
    val releaseSave = CompletableDeferred<Unit>()

    /** Hold the *next* marker write durably-applied-but-not-returned. Consumed by that one write. */
    var blockNextSave: Boolean = false

    /** Fail the *next* marker write before it lands. Consumed by that one write. */
    var failNextSave: Boolean = false

    /**
     * Commit the next marker write durably, then throw this. Consumed by that one write.
     *
     * `CancellationException` models a cancellation observed after the durable commit; an ordinary
     * exception models any implementation that reports failure after its commit succeeded.
     */
    var failAfterCommit: Throwable? = null

    /**
     * When set with [failAfterCommit], the reconciliation read that follows the ambiguous write fails
     * too — so nothing about the durable record can be established at all.
     */
    var failReloadAfterCommit: Boolean = false

    private var failNextLoad: Boolean = false

    override suspend fun load(): DatasetPending {
        if (failNextLoad) {
            failNextLoad = false
            error("durable reload failed")
        }
        return delegate.load()
    }

    override suspend fun save(pending: DatasetPending) {
        val isMarkerWrite = pending.isIsolating
        if (isMarkerWrite && failNextSave) {
            failNextSave = false
            error("durable marker write failed")
        }
        delegate.save(pending)
        if (isMarkerWrite) {
            failAfterCommit?.let { failure ->
                failAfterCommit = null
                if (failReloadAfterCommit) failNextLoad = true
                throw failure
            }
        }
        if (isMarkerWrite && blockNextSave) {
            blockNextSave = false
            markerCommitted.complete(Unit)
            releaseSave.await()
        }
    }
}
