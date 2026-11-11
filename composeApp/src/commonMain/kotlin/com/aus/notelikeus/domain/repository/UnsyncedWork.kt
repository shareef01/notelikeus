package com.aus.notelikeus.domain.repository

/**
 * Work on this device that the cloud has not confirmed (AUDIT-001).
 *
 * Signing out clears this device's notes so the next person cannot see them. Until 2.6.0 it did so
 * without asking whether any were newer than the cloud's copy; since NL-03 such work is sealed for the
 * account instead of deleted (`SignedOutAccountVault`) and uploads when it signs in here again. These
 * counts are what the sign-out confirmation tells the user, and what decides whether anything is kept.
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
