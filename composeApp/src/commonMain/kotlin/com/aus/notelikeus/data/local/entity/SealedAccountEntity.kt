package com.aus.notelikeus.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One signed-out account's local library, kept on this device because it still held work the cloud
 * had not confirmed when the account signed out (NL-03).
 *
 * The row lives in the same SQLCipher database as the notes, so it is encrypted at rest under the same
 * key, and nothing reads it except [com.aus.notelikeus.data.sync.SignedOutAccountVault], which hands it
 * back only to the account that sealed it. Account isolation never deletes this table: that is what
 * lets the work survive the sign-out, and why the library itself is wiped as before.
 *
 * @property ownerUid the account the library belongs to, as the sync state recorded it
 * @property payload the sealed library, encoded by `SignedOutAccountVault`
 * @property sealedAt when it was sealed, epoch millis
 * @property armed true once the dataset it was sealed from has been wiped from the device. Until
 *   then the live rows are still that dataset and are newer than this copy, so it must not be restored.
 */
@Entity(tableName = "sealed_accounts")
data class SealedAccountEntity(
    @PrimaryKey
    val ownerUid: String,
    val payload: String,
    val sealedAt: Long,
    val armed: Boolean,
)
