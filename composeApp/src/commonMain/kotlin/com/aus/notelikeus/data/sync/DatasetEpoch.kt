package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.platform.PendingSyncKind
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The durable identity of the device-local dataset incarnation a piece of *scheduled* work belongs
 * to — R15.1 of the D11 remediation, closing F-4.
 *
 * [LocalCommitGate.generation] cannot serve this role, and neither can a uid. Both are
 * process-local or account-level facts, while the defect is about work that outlives the process or
 * the session that produced it:
 *
 * ```
 * dataset N                   a delete for note 42 is queued
 * isolation                   N -> N+1 (same uid signs back in)
 * replacement dataset         a *different* note now occupies id 42
 * the queued command executes engine fresh-captures a token for N+1 — the old intent is re-authorized
 * ```
 *
 * `generation` is a `Long` that every process starts at 1, so a restarted process cannot tell an
 * old command from a new one and must not be asked to. [DatasetEpoch] is instead an opaque, random,
 * **durably persisted** value: it survives process death, it is compared by value, and it changes
 * only when account isolation actually replaces the dataset. A fresh process whose
 * `LocalCommitGate.generation` collides with a previous process's therefore cannot false-accept
 * anything, because the decision never consults the counter.
 *
 * It is deliberately *not* derived from the uid: the whole point is the case where the uid is
 * identical on both sides of an account boundary.
 */
@JvmInline
value class DatasetEpoch(val value: String)

/**
 * Mints a new dataset incarnation.
 *
 * Random rather than derived, because a guessable value would let work from a replaced dataset
 * validate against its replacement: the epoch is the only thing standing between an old queued
 * command and a fresh authorization. Version 4 UUIDs are generated from the platform's
 * cryptographically strong source where it has one.
 */
@OptIn(ExperimentalUuidApi::class)
fun randomDatasetEpoch(): DatasetEpoch = DatasetEpoch(Uuid.random().toString())

/**
 * One delayed account-owned cloud write, carrying the authority of the operation that asked for it.
 *
 * Every field is captured **at schedule time** and travels with the command: the note id alone is
 * not enough, because ids are per-device autoincrements that the replacement dataset reuses, and
 * neither is a uid, because the same account signing back in produces a new dataset under the same
 * uid. [datasetEpoch] is the origin authority; [ownerUid] is defense in depth.
 */
data class PendingSyncCommand(
    val kind: PendingSyncKind,
    val noteId: Long,
    val datasetEpoch: DatasetEpoch,
    val ownerUid: String?,
)

/**
 * The durable state of the delayed-sync queue: the dataset incarnation it belongs to, the account
 * the commands were queued for, and the commands themselves.
 *
 * [epoch] is the dataset the device is currently on, and exists even when there is nothing queued —
 * a restarted process has to know which dataset an empty queue belongs to before it can decide
 * anything about the work it finds. A `null` [epoch] means "never initialized, or written by a
 * version that predates epochs": pending commands in that state carry no origin authority at all,
 * and are dropped rather than assumed current (see `DatasetEpochAuthority.initializeLocked`).
 *
 * Each [PendingSyncCommand] also carries its own epoch. That is redundant with [epoch] by
 * construction, and deliberately kept: the queue's clear-on-rotation is *cleanup*, so a store that
 * could not complete the rotation and the clear in one write still cannot launder an old command
 * into the new dataset — the per-command epoch refuses it wherever it is found.
 */
data class DatasetPending(
    val epoch: DatasetEpoch? = null,
    val ownerUid: String? = null,
    val commands: List<PendingSyncCommand> = emptyList(),
    /**
     * The epoch an isolation in flight will reach, or `null` when the device is settled on [epoch].
     *
     * This is what makes an *interrupted* isolation fail closed rather than leave the previous
     * dataset looking current. It is written **before** the account boundary is crossed and cleared
     * only once that boundary's wipe has finished, so every crash window between those two points
     * reads back as "between datasets" — in this process and in the next one.
     */
    val isolationTarget: DatasetEpoch? = null,
) {
    val isEmpty: Boolean get() = commands.isEmpty()

    /** Whether an isolation began and has not completed safely. */
    val isIsolating: Boolean get() = isolationTarget != null

    fun commandsOf(kind: PendingSyncKind): List<PendingSyncCommand> =
        commands.filter { it.kind == kind }

    companion object {
        /** Nothing read from disk yet, and no epoch minted. */
        val Uninitialized = DatasetPending()
    }
}

/**
 * What the device's dataset authority is right now — R15.3.
 *
 * R15.1 and R15.2 made scheduled work carry the dataset it originated in and refused it in the
 * dataset that replaced that one. Both assumed the *current* dataset is a settled fact. It is not
 * while an isolation is failing: the account boundary has already been crossed (the local generation
 * has advanced and the user's session has changed) but the durable epoch could not be replaced, so
 * the previous dataset was still the one every check compared against — and its queued work still
 * validated, in this process and in the next one.
 *
 * Two states, one decision. [Stable] is the ordinary case; [Isolating] refuses **all** scheduled
 * authority, because there is no dataset the device is safely on:
 *
 *  - work carrying the epoch it came from is not current;
 *  - work carrying the epoch it is going to is not current either — that dataset does not exist yet;
 *  - current-dataset maintenance has nothing to maintain, since "current" is exactly what is unknown.
 *
 * It is durable state, not a flag: a restarted process answers [Isolating] from the same store, and
 * holds out until the transition is completed by recovery.
 */
sealed interface DatasetAuthority {
    /** The device is on [epoch]. Scheduled work carrying it may be queued and executed. */
    data class Stable(val epoch: DatasetEpoch) : DatasetAuthority

    /**
     * An isolation is in flight and has not completed.
     *
     * [from] is the epoch being left behind and [next] the one it will reach; both are diagnostic —
     * neither may authorize anything. [from] is `null` only for a record whose epoch is missing
     * entirely, which is still completed by recovery rather than treated as "no transition".
     */
    data class Isolating(val from: DatasetEpoch?, val next: DatasetEpoch) : DatasetAuthority
}

/**
 * The origin authority the *engine* resolves a scheduled command into a validated local token.
 *
 * Deliberately not a `LocalCommitToken`: a token is the destination, and constructing one from
 * whatever the running process happens to be doing is exactly the F-4 defect. This value can only
 * come from what the queued command recorded, and it is refused unless the dataset it names is
 * still the current one.
 */
data class ScheduledWorkOrigin(
    /**
     * The account the command was queued for, or `null` when it was queued with no session at all.
     *
     * Defense in depth, not the authority: a uid cannot see a `A -> sign out -> A` boundary, so the
     * epoch is what decides. A `null` here therefore means "no account to check against", not
     * "any account will do" — the epoch still has to be the current one, and the credential the
     * operation runs under is still captured from the originating token (R13A).
     */
    val expectedUid: String?,
    val datasetEpoch: DatasetEpoch,
)

/**
 * What a queued command did when it was finally run.
 *
 * Three outcomes, kept apart on purpose, because collapsing any two of them loses a distinction the
 * callers need: a *stale* command must be dropped rather than retried (its dataset no longer
 * exists), a *failed* one must be retried, and a *cancelled* one must be re-queued without being
 * counted as a cloud failure — counting those was what pushed the desktop queue into a 30-second
 * backoff every time the user kept typing mid-sync.
 */
enum class ScheduledWorkOutcome {
    /** The operation ran. The command is done. */
    Applied,

    /**
     * The command's dataset, or the account it was queued for, is no longer the current one.
     *
     * Nothing was touched and nothing may be retried: the dataset that asked for this work has been
     * replaced, so retrying is retrying the exact thing that must not happen.
     */
    RefusedStaleOrigin,

    /** The operation failed for a reason worth retrying — a network or credential failure. */
    Retryable,

    /** The operation was cancelled before it finished. Re-queued, but not a failure to back off on. */
    Cancelled,
}

// ---- durable encoding ---------------------------------------------------------------------------
//
// One textual form for a queued command, used by both platform stores so the two cannot drift.

private const val FIELD_SEPARATOR = '|'

/** `<epoch>|<ownerUid>|<kind>|<noteId>` — four fields, or the entry carries no usable origin. */
private const val FIELD_COUNT = 4

internal fun PendingSyncCommand.encodeForStorage(): String = listOf(
    datasetEpoch.value,
    ownerUid.orEmpty(),
    kind.name,
    noteId.toString(),
).joinToString(FIELD_SEPARATOR.toString())

/**
 * Decodes one stored entry, or `null` when it carries no usable origin authority.
 *
 * A plain note id is what versions before R15.1 wrote, and there is no way to tell which dataset it
 * belonged to. It fails closed: the caller drops it and reports it, rather than stamping it with
 * the current epoch, which would be the laundering this exists to prevent.
 */
internal fun decodePendingSyncCommand(raw: String): PendingSyncCommand? {
    val parts = raw.split(FIELD_SEPARATOR)
    if (parts.size != FIELD_COUNT) return null
    if (parts[0].isBlank()) return null
    val kind = PendingSyncKind.entries.firstOrNull { it.name == parts[2] } ?: return null
    val noteId = parts[3].toLongOrNull() ?: return null
    return PendingSyncCommand(
        kind = kind,
        noteId = noteId,
        datasetEpoch = DatasetEpoch(parts[0]),
        ownerUid = parts[1].ifBlank { null },
    )
}
