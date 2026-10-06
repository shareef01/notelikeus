package com.aus.notelikeus.data.sync

/**
 * An in-memory [PendingCloudWipeIntentStore] for tests and for platforms that have no durable store wired.
 *
 * It is *not* a production store: it forgets on process death, which is the failure F-8 is about. The
 * production graph wires the platform implementations instead (Android preferences, Desktop file), and
 * tests use this one to drive the coordinator's ordering deterministically.
 */
class InMemoryPendingCloudWipeIntentStore : PendingCloudWipeIntentStore {

    private val intents = linkedMapOf<String, PendingCloudWipeIntent>()

    /** Set to make [write] fail, for the lane that proves no destructive work starts without a record. */
    var failWrites: Boolean = false

    /** Set to make [clear] fail, for the lane that proves a completed wipe stays harmlessly retryable. */
    var failClear: Boolean = false

    /**
     * Set to make [write] behave like the pre-R21.1 single-slot store: it replaces *every* owner's record.
     *
     * Exists so the cross-account red can be measured against the same lane bodies: with this true, a second
     * account's request silently drops the first account's accepted obligation.
     */
    var replacesOtherOwners: Boolean = false

    override suspend fun read(): List<PendingCloudWipeIntent> = intents.values.toList()

    override suspend fun find(ownerUid: String): PendingCloudWipeIntent? = intents[ownerUid]

    override suspend fun write(intent: PendingCloudWipeIntent) {
        if (failWrites) error("pending wipe store unavailable")
        if (replacesOtherOwners) intents.clear()
        // A repeat request keeps the original timestamp: the obligation is the same one, still outstanding.
        intents[intent.ownerUid] = intents[intent.ownerUid] ?: intent
    }

    override suspend fun clear(ownerUid: String) {
        if (failClear) error("pending wipe store unavailable")
        intents.remove(ownerUid)
    }
}
