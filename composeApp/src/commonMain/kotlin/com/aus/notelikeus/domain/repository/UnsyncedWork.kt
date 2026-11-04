package com.aus.notelikeus.domain.repository

/**
 * Work on this device that the cloud has not confirmed: what signing out would destroy (AUDIT-001).
 *
 * Signing out clears this device's notes so the next person cannot see them, and until 2.6.0 it did
 * so without asking whether any of them were newer than the cloud's copy. These counts are what the
 * sign-out confirmation tells the user before it lets that happen.
 *
 * @property edits notes whose latest version the cloud has not confirmed, created here included
 * @property deletions notes deleted here that the cloud still holds
 * @property images images attached here that have not uploaded
 */
data class UnsyncedWork(
    val edits: Int = 0,
    val deletions: Int = 0,
    val images: Int = 0,
) {
    val isEmpty: Boolean get() = edits == 0 && deletions == 0 && images == 0

    companion object {
        val NONE = UnsyncedWork()
    }
}
