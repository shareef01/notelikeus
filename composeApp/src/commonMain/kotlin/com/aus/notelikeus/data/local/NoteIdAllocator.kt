package com.aus.notelikeus.data.local

import kotlin.random.Random

/**
 * Picks the id of a note this device creates. The id is also the note's id in the cloud, so it must
 * never be one the account has used before.
 *
 * Room's autoincrement could not promise that. After a sign-out wipe, the next sync downloads the
 * account's notes under their cloud ids and the sequence restarts at the largest of them, so the
 * first notes created afterwards took the ids of notes deleted earlier on this device. The cloud
 * still holds those notes' tombstones, and the next sync read them as deletions of the new notes:
 * the notes never uploaded and then disappeared from the device.
 *
 * So this uses the web app's rule (`nextLocalNoteIdAfter`): epoch millis times 1000 plus a random
 * suffix, which stays below 2^53 for the web's numbers. An id still taken on this device is skipped,
 * since the insert replaces on conflict.
 */
internal object NoteIdAllocator {
    private const val SUFFIX_RANGE = 1000

    suspend fun next(
        nowMillis: Long,
        random: Random = Random.Default,
        isTaken: suspend (Long) -> Boolean,
    ): Long {
        var id = nowMillis * SUFFIX_RANGE + random.nextInt(SUFFIX_RANGE)
        while (isTaken(id)) id++
        return id
    }
}
