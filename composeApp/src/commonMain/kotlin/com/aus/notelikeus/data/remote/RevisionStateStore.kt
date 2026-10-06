package com.aus.notelikeus.data.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The revisions this client has learned from the cloud, and the dataset generation they belong to.
 *
 * Two properties have to hold together, which is why the epoch and the map live in one guarded object
 * rather than beside each other:
 *
 * * **Atomic generation decisions.** A caller captures the current epoch once, at the start of its
 *   logical operation, and every read and write is decided *inside* the same critical section that
 *   compares it. Checking the epoch and then returning the map to mutate would leave a window — the
 *   check passes on dataset N, the dataset is replaced, and the continuation writes N's revision into
 *   N+1, which is the orphan belief F-9 exists to prevent. Nothing can observe the check and mutate
 *   across a reset, because there is no such pair of steps to interleave.
 * * **Atomic reset.** [resetDataset] advances the epoch and discards the state under the same lock, so
 *   there is no observable moment of "new epoch with the old revisions" or "old epoch with an empty
 *   map" that could authorize a wrong base revision.
 *
 * The lock is `kotlinx.coroutines.sync.Mutex` (multiplatform, and this is common code), and every
 * critical section is a few in-memory operations: no RPC, no credential lookup, no disk, and never the
 * local-commit gate. Network work happens around these calls, and the revision it produces is written
 * afterwards, re-checked against the epoch inside the lock.
 */
internal class RevisionStateStore {

    private val lock = Mutex()

    /** Only ever touched under [lock]. */
    private var activeEpoch: Long = 0L

    /** Only ever touched under [lock]; uid -> noteId -> server revision. */
    private val byUid = mutableMapOf<String, MutableMap<Long, Long>>()

    /**
     * The dataset generation an operation belongs to, captured once when that operation starts.
     *
     * Called before any suspension, so an operation cannot ask "which dataset is current now?" after
     * resuming and launder old work into the replacement.
     */
    suspend fun currentEpoch(): Long = lock.withLock { activeEpoch }

    /**
     * Ends the current dataset: a new epoch *and* no state, in one transition.
     *
     * Returns the epoch now active, for callers that want to log or assert the boundary.
     */
    suspend fun resetDataset(): Long = lock.withLock {
        activeEpoch += 1
        byUid.clear()
        activeEpoch
    }

    /** The revision learned in [epoch] for [noteId], or null when [epoch] is not the active dataset. */
    suspend fun read(epoch: Long, uid: String, noteId: Long): Long? = lock.withLock {
        if (epoch != activeEpoch) return@withLock null
        byUid[uid]?.get(noteId)
    }

    /** Records [revision] — only when [epoch] is still the active dataset. */
    suspend fun write(epoch: Long, uid: String, noteId: Long, revision: Long) {
        lock.withLock {
            if (epoch != activeEpoch) return@withLock
            byUid.getOrPut(uid) { mutableMapOf() }[noteId] = revision
        }
    }

    /** Forgets [noteId] — only when [epoch] is still the active dataset. */
    suspend fun remove(epoch: Long, uid: String, noteId: Long) {
        lock.withLock {
            if (epoch != activeEpoch) return@withLock
            byUid[uid]?.remove(noteId)
        }
    }

    /**
     * Forgets everything known for [uid], under any epoch.
     *
     * An account-wide wipe is the caller: it cannot import a stale belief — it only removes them — so it
     * needs no generation decision, but it still has to be synchronized like every other access. The cost
     * of a wipe landing late is that the replacement dataset re-learns its revisions from the next
     * snapshot, which is the cheap direction to be wrong in.
     */
    suspend fun forget(uid: String) {
        lock.withLock { byUid.remove(uid) }
    }

    /**
     * Replaces everything known for [uid] with [entries], as one transition.
     *
     * A download publishes the revisions it read from the authoritative snapshot in a single step, so a
     * concurrent reader sees either the previous set or the new one — never a half-applied mixture.
     */
    suspend fun replace(epoch: Long, uid: String, entries: Map<Long, Long>) {
        lock.withLock {
            if (epoch != activeEpoch) return@withLock
            byUid.getOrPut(uid) { mutableMapOf() }.apply {
                clear()
                putAll(entries)
            }
        }
    }
}
