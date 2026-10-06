package com.aus.notelikeus.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place that owns "the user asked for their cloud data to be deleted".
 *
 * The ordering is the whole point:
 *
 *  1. the accepted request is written durably ([PendingCloudWipeIntentStore.write]) **before** anything
 *     destructive is attempted — if that write fails, the wipe does not start and the caller reports the
 *     failure, because a destructive operation without a record is precisely F-8;
 *  2. the wipe runs with the identity captured for the intended owner;
 *  3. the record is cleared **only** on authoritative success. A failure, a timeout, or a throw after the
 *     server may already have wiped leaves the record in place, which is the safe direction: the wipe is
 *     idempotent (owner-scoped DELETEs that report `applied` even when there was nothing to delete), so a
 *     later retry is harmless whether or not the first attempt committed.
 *
 * No credential is stored, and a pending intent is only ever executed for its own [ownerUid]: recovery is
 * refused for any other signed-in account, which is what keeps one account's destructive request from
 * being performed with another's bearer.
 */
class CloudWipeCoordinator(
    private val intents: PendingCloudWipeIntentStore,
    /**
     * Serializes record creation, retry and completion inside this process.
     *
     * Each record is filed under its own owner, so two accounts cannot overwrite each other's request by
     * construction; this lock covers the remaining overlaps within one process — a completion racing a new
     * request, or two requests for the same owner. The process model is one app instance per platform, so no
     * cross-process lock is attempted; the stores keep every write whole (preferences `commit`, atomic file
     * replace), so a second desktop instance at worst loses an update, never truncates a record.
     */
    private val serialization: Mutex = Mutex(),
) {

    /**
     * Accepts and attempts the destructive request for [ownerUid].
     *
     * The wipe itself is supplied as a function so this class holds no transport, no credential source
     * and no live-session access: the caller provides the work *and* the identity it must run under.
     */
    suspend fun requestWipe(
        ownerUid: String,
        nowMillis: Long,
        performWipe: suspend () -> Unit,
    ): CloudWipeOutcome = serialization.withLock {
        val intent = PendingCloudWipeIntent(ownerUid = ownerUid, requestedAtMillis = nowMillis)
        // Persist first. A store that cannot remember the request must stop the request, not defer it —
        // and this record is filed under its own owner, so it cannot displace another account's.
        try {
            intents.write(intent)
        } catch (failure: Throwable) {
            return@withLock CloudWipeOutcome.NotStarted(failure)
        }
        attempt(ownerUid, performWipe)
    }

    /**
     * Retries a pending request when — and only when — [signedInUid] is the account it targets.
     *
     * Returns null when there is nothing pending, or when the pending request belongs to somebody else,
     * in which case it is left exactly as it was: another account signing in must not run it, and must not
     * erase the fact that it is still owed.
     */
    suspend fun resumeIfPending(signedInUid: String, performWipe: suspend () -> Unit): CloudWipeOutcome? =
        serialization.withLock {
            // Filed by owner, so a signed-in account finds only its own request — never another's.
            if (intents.find(signedInUid) == null) return@withLock null
            attempt(signedInUid, performWipe)
        }

    /**
     * Runs one attempt for [ownerUid] and records the outcome.
     *
     * Completion clears *that owner's* record only: an account whose wipe finished can never erase what
     * another account is still owed, however the two overlap.
     */
    private suspend fun attempt(ownerUid: String, performWipe: suspend () -> Unit): CloudWipeOutcome {
        try {
            performWipe()
        } catch (failure: Throwable) {
            // The request may or may not have reached the server. Either way it stays owed.
            return CloudWipeOutcome.Failed(failure)
        }
        return try {
            intents.clear(ownerUid)
            CloudWipeOutcome.Completed
        } catch (failure: Throwable) {
            // The wipe succeeded but the record could not be dropped. Reporting "pending" is the safe
            // lie: the retry it triggers is idempotent, whereas claiming completion we cannot record
            // would strand the record as permanently pending.
            CloudWipeOutcome.CompletedButStillPending(failure)
        }
    }
}

/** What happened to a destructive wipe request, in the terms the caller has to report. */
sealed interface CloudWipeOutcome {

    /** The remote wipe completed and the durable record was cleared. */
    data object Completed : CloudWipeOutcome

    /** The request could not even be recorded, so no destructive work was attempted. */
    data class NotStarted(val failure: Throwable) : CloudWipeOutcome

    /** The wipe did not complete authoritatively; the request stays pending and will be retried. */
    data class Failed(val failure: Throwable) : CloudWipeOutcome

    /** The wipe completed, but the record could not be cleared; a harmless retry is still owed. */
    data class CompletedButStillPending(val failure: Throwable) : CloudWipeOutcome

    /** True when the durable record still says the account is owed a destructive wipe. */
    val stillPending: Boolean
        get() = this is Failed || this is CompletedButStillPending || this is NotStarted
}
