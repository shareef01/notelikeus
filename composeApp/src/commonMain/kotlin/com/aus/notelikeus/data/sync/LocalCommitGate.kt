package com.aus.notelikeus.data.sync

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import com.aus.notelikeus.domain.repository.LocalCommitResult
import com.aus.notelikeus.domain.repository.LocalCommitToken
import com.aus.notelikeus.util.AppLog


/**
 * The process-wide gate that makes account isolation and device-local commits one critical section.
 *
 * `NoteSyncEngine` starts every operation with the account it was authorised under, then crosses
 * suspension points before writing device-local rows and sync metadata. A session check placed
 * *immediately before* such a write is not enough, because the check and the write are two separate
 * suspend calls: the account can sign out and another sign in in between, and the write then lands
 * on the new account's state. Note ids are small per-device autoincrements and attachment ids are
 * caller-generated strings, so "the same id" for two different accounts is the normal case, not a
 * pathological one.
 *
 * This is a Kotlin `object`, so [mutex] and [generation] are one instance per process. That is
 * deliberate: a per-object gate would silently protect nothing at any wiring site that constructed
 * its own — see the D8 notes in docs/AUDIT_DEEPSEEK_2026-09-20.md.
 *
 * It is never held across a network call. Every region wrapped by [commit] or [isolate] is local
 * database and preference work only, so isolation can be delayed by a write but never by a round
 * trip.
 *
 * **The invariant, stated exactly (R15.2/R15.3).** No remote or network I/O, and no ordinary queue
 * enqueue, ever runs while this gate is held. The bounded exception is the dataset-authority
 * *transition*, which is the account-owned isolation commit itself:
 *
 *  - `DatasetEpochAuthority.beginIsolation` writes the durable marker **before** the boundary is
 *    crossed, outside the gate. If it fails, nothing has happened: the device is still on the dataset
 *    it was on, and that dataset's authority remains valid.
 *  - `DatasetEpochAuthority.completeIsolation` writes the new epoch and drops the previous dataset's
 *    queue **inside** an [isolate] block, after the wipe. It has to be in there: an epoch that moved
 *    before the generation advanced would let a token still current *by generation* be stamped with
 *    it, and one that moved after would leave a crash window in which the store claims the new dataset
 *    while the old dataset's rows are still on disk.
 *
 * Everything else the gate covers is local database and preference work. The scheduling path does the
 * opposite of the transition on purpose: the durable read that populates the epoch mirror happens
 * before the gate is taken, the conditional enqueue after it is released, and the gate block itself
 * only compares values already in memory — see [resolveSchedulingEpoch], [resolveScheduledOrigin], and
 * the probes in `ScheduledSyncDatasetEpochFenceTest` that assert no store or session I/O happens under
 * it. Lock order is likewise fixed and one-way: [mutex] → `DatasetEpochAuthority.mutex`, never the
 * reverse.
 *
 * [commit] additionally consults that authority's quarantine: while an isolation is unfinished the
 * device is between datasets, and the rows still on disk belong to the one being left, so
 * account-owned local writes are refused as well as scheduled ones.
 *
 * **Not reentrant.** [commit] and [isolate] hold [mutex] for the whole block, so a block must not
 * call back into either — that would deadlock.
 *
 * Two primitives, two jobs, neither replacing the other:
 *
 *  - [generation] is an **atomic** so the synchronous [capture] has a real publication guarantee.
 *    [capture] deliberately does not take [mutex] — it has to be callable before a debounce, from a
 *    UI thread, without suspending.
 *  - [mutex] is what **serializes a validated commit against account isolation**. Atomicity of the
 *    counter says nothing about that ordering; read-modify-write on a counter is not the invariant.
 */
@OptIn(ExperimentalAtomicApi::class)
object LocalCommitGate {

    private const val TAG = "LocalCommitGate"

    /**
     * The serialization primitive shared by validated commits and account isolation.
     *
     * Still public because `NoteSyncEngine`'s `commitLocally` helper and the account-switch tests
     * take it directly; both were written before the token existed and are equivalent to a commit
     * that validates nothing. It is the one remaining raw-mutex escape hatch in production — see the
     * D11 notes in docs/AUDIT_DEEPSEEK_2026-09-20.md.
     */
    val mutex: Mutex = Mutex()

    /**
     * Whether account-owned local writes are currently permitted at all — R15.3.
     *
     * Installed once by the composition that owns the dataset authority
     * (`DatasetEpochAuthority.isIsolationIncomplete`), and read **inside** the gate so the quarantine
     * cannot be raced: while an isolation is in flight the device is between datasets, so the rows
     * still on disk belong to the dataset being left and no account-owned write may touch them —
     * local or remote. A `null` probe means no dataset authority is wired (tests, and any composition
     * that never schedules), which permits commits exactly as before.
     *
     * It is deliberately a *refusal added to* the generation check rather than a replacement for it,
     * and it is why the isolation transition is safe in both directions: a commit that won the gate
     * before isolation is applied and then wiped; one that arrives during the transition is refused.
     */
    @Volatile
    private var datasetAuthorityQuarantine: (() -> Boolean)? = null

    /**
     * Publishes the dataset authority's quarantine state to every local commit.
     *
     * Called once per process from the composition that constructs the authority. Passing `null`
     * uninstalls it, which tests use to keep lanes that are not about the transition isolated from
     * each other.
     */
    fun installDatasetAuthorityQuarantine(isQuarantined: (() -> Boolean)?) {
        datasetAuthorityQuarantine = isQuarantined
    }

    /** True while the dataset authority says local account-owned writes are not permitted. */
    private fun datasetAuthorityUnresolved(): Boolean = datasetAuthorityQuarantine?.invoke() ?: false

    /**
     * The generation of the device-local dataset.
     *
     * An atomic rather than a plain `var`, because [capture] reads it **without** taking [mutex] — an
     * ordinary variable read outside the lock that writes it has no cross-thread publication
     * guarantee, and a P0 account-isolation invariant must not rest on a data race that merely
     * happens to fail safe on one JVM. `kotlin.concurrent.atomics` is the multiplatform primitive
     * (Kotlin 2.1+), so this is also correct on Native targets that would not tolerate a plain
     * unsynchronized read at all.
     */
    private val generation = AtomicLong(INITIAL_GENERATION)

    /** The generation a fresh process starts at. Nothing has isolated yet. */
    const val INITIAL_GENERATION: Long = 1L

    /** The dataset incarnation a new piece of work belongs to. Call before suspending, not after. */
    fun capture(initiatingUid: String?): LocalCommitToken =
        LocalCommitToken(generation = generation.load(), initiatingUid = initiatingUid)

    /** The current generation. Exposed for tests and diagnostics, not for commit decisions. */
    fun currentGeneration(): Long = generation.load()

    /**
     * Runs [block] as one account-owned logical mutation, refusing it if the dataset moved on.
     *
     * Validation and the mutation share one critical section, so there is no
     * check-then-unlock-then-write window for isolation to land in. If isolation got the gate first
     * the generation no longer matches and nothing runs; if this commit got it first, isolation
     * waits and then wipes what was written.
     */
    suspend fun <T> commit(
        token: LocalCommitToken,
        block: suspend () -> T,
    ): LocalCommitResult<T> = mutex.withLock {
        if (token.generation != generation.load() || datasetAuthorityUnresolved()) {
            LocalCommitResult.StaleGeneration
        } else {
            LocalCommitResult.Applied(block())
        }
    }

    /**
     * Issues the one-shot authority to start exactly one account-owned **remote** mutation.
     *
     * [commit] cannot serve this, and neither can a bare generation check. [commit]'s whole point is
     * that validation and the mutation are one critical section, which is right for device-local
     * writes and wrong for anything that talks to the network: holding [mutex] across a round trip
     * would let a sign-in wait on an unrelated server. But a *validated local commit* also says nothing
     * about the remote call that follows it. The gate is released when that commit returns, and the
     * statements between the release and `transport.…` are ordinary code — on desktop the sync queue
     * runs deletes on `Dispatchers.IO` while sign-out isolates from its own context, so a genuine
     * second thread can acquire [mutex] and advance the generation inside that gap. Checking the
     * generation and *then* starting the request is therefore not an ordering at all; it is the same
     * race with one more step in it.
     *
     * What is atomic here is the **grant**, and it is created inside [mutex] — the same lock [isolate]
     * takes to advance the generation. That gives the two orders a definite winner:
     *
     *  - **isolation first** — in *either* of its two forms, no authorization is created and the
     *    caller issues **zero** transport calls. Either the boundary has been crossed, and the
     *    generation no longer matches; or only the durable marker exists, and the gate's quarantine
     *    refuses. The second form is why this decision cannot be a generation comparison alone: an
     *    unfinished isolation is written *before* the account boundary is crossed (see
     *    `LocalAccountIsolator`), so during it the durable dataset is already the one being left while
     *    the local generation is still the old one. A generation match there would mint authority for a
     *    dataset the device has already left — for a remote call that then runs against the row set
     *    the wipe is about to replace. The refusal reuses [commit]'s exact predicate rather than
     *    inventing a second opinion about the same state;
     *  - **authorization first** — the grant exists before the boundary, so that specific mutation is
     *    by construction *already issued* and may run; its results may still only be applied under the
     *    originating generation, which is why callers keep their post-remote [commit].
     *
     * [mutationName] is both diagnostic and structural: it names the one transport invocation this
     * grant is for, and it is what a reuse attempt reports. It is never a commit condition.
     */
    suspend fun authorizeRemoteMutationStart(
        token: LocalCommitToken,
        mutationName: String,
    ): RemoteMutationAuthorization? = mutex.withLock {
        if (token.generation != generation.load()) {
            AppLog.warn(
                TAG,
                "Refused remote mutation '$mutationName' captured in generation " +
                    "${token.generation}: the dataset has moved on.",
            )
            null
        } else if (datasetAuthorityUnresolved()) {
            // The generation has not moved because the isolation has not crossed the boundary yet —
            // but the durable dataset is already the one being left, so nothing new may be authorized
            // for it. Checked in this same critical section, and against the same in-memory predicate
            // [commit] uses: no durable read, no authority lock, nothing that could suspend here.
            AppLog.warn(
                TAG,
                "Refused remote mutation '$mutationName' in generation ${token.generation}: a " +
                    "dataset isolation is in flight, so the device is between datasets.",
            )
            null
        } else {
            RemoteMutationAuthorization(generation = token.generation, mutationName = mutationName)
        }
    }

    /**
     * One-shot authority to start a single account-owned remote mutation.
     *
     * Created only by [authorizeRemoteMutationStart], under [mutex], and consumed exactly once by
     * [consumeOnce]. There is deliberately no permit registry and nothing to revoke: the grant is a
     * value whose only power is to run the one block it is handed, so it cannot leak, cannot be shared
     * between mutations, and cannot be carried across a later account boundary. A cancellation between
     * grant and request therefore leaves no global state behind — the object is simply dropped and the
     * generation counter is untouched.
     *
     * **What this guarantees.** Isolation and a grant cannot both win for the same mutation: the grant
     * is created under the lock that advances the generation *and* against the same quarantine [commit]
     * consults, so either the transition came first — in either of its two forms, the durable marker or
     * the crossed boundary — and no grant exists and no request starts, or the mutation was already
     * issued before the transition and may run. The authorization is also the only thing [consumeOnce]
     * will run, so one grant can never end up covering two mutations or a different mutation than the
     * one it names.
     *
     * **What this does not guarantee.** That the request physically leaves the device before isolation.
     * The *grant* is atomic; the transport call is not, and making the call atomic would mean
     * initiating network I/O while holding [mutex] — which this design forbids, and which
     * [CloudNoteTransport] could not express anyway because every operation is a single `suspend` that
     * bundles initiation with the wait. An operation whose grant precedes the boundary is defined as
     * already issued and is allowed to complete, the same asymmetry as work already on the wire.
     * Closing that last interval would need an initiate/await split in the transport, not another check
     * here.
     */
    class RemoteMutationAuthorization internal constructor(
        /** The dataset generation this grant was issued under. Diagnostic and audit only. */
        val generation: Long,
        /** The single transport invocation this grant is for. */
        val mutationName: String,
    ) {
        private val consumed = AtomicBoolean(false)

        /** Whether [consumeOnce] has already run. Exposed for tests and diagnostics. */
        val isConsumed: Boolean get() = consumed.load()

        /**
         * Runs [block] as the one remote mutation this grant authorizes, outside [mutex].
         *
         * Reuse is a programming error rather than a stale generation: one grant authorizes one
         * mutation, so a second call throws instead of quietly starting another request. A failure
         * inside [block] propagates unchanged and still leaves the grant consumed — authority is never
         * handed back to a generation that no longer exists.
         */
        suspend fun <T> consumeOnce(block: suspend () -> T): T {
            check(consumed.compareAndSet(false, true)) {
                "RemoteMutationAuthorization for '$mutationName' was already consumed; " +
                    "one grant authorizes exactly one remote mutation."
            }
            return block()
        }
    }

    /**
     * Runs [block] as an account boundary, invalidating every token captured before it.
     *
     * The generation is bumped at the **start** of the held section, so any commit that arrives
     * after the boundary is refused even if [block] is still running, while a commit already inside
     * completes first and is then wiped by [block].
     */
    suspend fun <T> isolate(block: suspend () -> T): T = mutex.withLock {
        generation.addAndFetch(1L)
        block()
    }

    /**
     * Captures a token whose [LocalCommitToken.generation] and [LocalCommitToken.initiatingUid] come
     * from the *same* dataset incarnation.
     *
     * [capture] takes the uid as an argument, so a caller that reads the session and then captures
     * performs two independent reads — and an isolation landing between them yields the worst
     * possible pairing: the **old account's uid with the new dataset's generation**, which
     * generation validation would then *accept*. This closes that window by re-reading the
     * generation either side of the uid read and retrying if it moved.
     *
     * Non-suspending and never blocks on [mutex]: it is called from the UI thread when an edit
     * schedules its autosave, before any debounce. The retry cannot spin forever — [generation] only
     * advances when isolation runs, so a retry means another account boundary, not a livelock.
     *
     * A uid change *without* an isolation deliberately does not retry: intentional guest → first
     * account adoption keeps the same generation, and the token must stay valid across it.
     */
    fun captureCoherent(initiatingUidProvider: () -> String?): LocalCommitToken {
        while (true) {
            val generationBefore = generation.load()
            val uid = initiatingUidProvider()
            val generationAfter = generation.load()
            if (generationBefore == generationAfter) {
                return LocalCommitToken(generation = generationAfter, initiatingUid = uid)
            }
        }
    }

    /**
     * Resolves the dataset epoch a piece of user intent may be queued under — R15.1, closing F-4.
     *
     * The scheduler cannot simply stamp whatever epoch is current when it reaches the queue, because
     * the token it holds may already have been invalidated by an isolation that landed in between:
     * an old dataset's action would then enter the replacement dataset's queue as its own work. Both
     * halves of the decision are therefore taken here, in one critical section:
     *
     *  - the token's generation is still the current one, and
     *  - the dataset epoch the device is on now is the one the command will be stamped with.
     *
     * Runs nothing but in-memory reads: [epochProvider] must return the caller's in-memory mirror,
     * and the durable read that populates that mirror belongs to the caller, before this call. A
     * disk read here would let an account boundary wait on file I/O.
     *
     * A `null` return refuses the enqueue. It is not a failure to report — it means the intent's
     * dataset is gone, and the queue must stay empty of it.
     */
    suspend fun resolveSchedulingEpoch(
        token: LocalCommitToken,
        epochProvider: () -> DatasetEpoch?,
    ): DatasetEpoch? = mutex.withLock {
        if (token.generation != generation.load()) {
            AppLog.warn(
                TAG,
                "Refused to queue work captured in generation ${token.generation}: the dataset has " +
                    "moved on.",
            )
            null
        } else {
            epochProvider()
        }
    }

    /**
     * Turns a queued command's [ScheduledWorkOrigin] into the device-local token it may execute
     * under — R15.1's atomic engine handoff.
     *
     * This is the step a worker cannot be allowed to skip. A worker that merely checks the epoch
     * before calling an ordinary engine entry point still has a window: it validates E1, parks,
     * isolation rotates to E2, and the engine then captures a *fresh* token for the replacement
     * dataset — at which point the old delete is newly authorized against ids the replacement
     * dataset has since reused. Validating and minting in one critical section closes that window,
     * and it is why the decision lives here rather than in the worker or the scheduler.
     *
     * The epoch comparison is the authority; [currentUid] is defense in depth and is passed in
     * rather than read here, because reading the session may refresh a credential. Deliberately
     * *not* checked: whether anything was queued, and whether a token was captured earlier. Both are
     * statements about the past, and the only question that matters is whether the dataset the
     * command names is the one this process is on now.
     */
    suspend fun resolveScheduledOrigin(
        origin: ScheduledWorkOrigin,
        currentUid: String?,
        epochProvider: () -> DatasetEpoch?,
    ): LocalCommitToken? = mutex.withLock {
        val currentEpoch = epochProvider()
        when {
            currentEpoch == null || currentEpoch != origin.datasetEpoch -> {
                AppLog.warn(
                    TAG,
                    "Refused scheduled work captured in dataset '${origin.datasetEpoch.value}': the " +
                        "current dataset is '${currentEpoch?.value ?: "<uninitialized>"}'.",
                )
                null
            }

            origin.expectedUid != null && currentUid != origin.expectedUid -> {
                AppLog.warn(
                    TAG,
                    "Refused scheduled work captured for account '${origin.expectedUid}': the live " +
                        "session is '${currentUid ?: "<signed out>"}'.",
                )
                null
            }

            else -> LocalCommitToken(
                generation = generation.load(),
                initiatingUid = origin.expectedUid,
            )
        }
    }
}
