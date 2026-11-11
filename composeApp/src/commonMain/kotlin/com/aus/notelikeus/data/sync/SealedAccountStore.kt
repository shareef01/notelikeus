package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.local.dao.SealedAccountDao
import com.aus.notelikeus.data.local.entity.SealedAccountEntity

/** A signed-out account's sealed library, as [SealedAccountStore] keeps it. */
data class SealedAccount(
    val ownerUid: String,
    val payload: String,
    val sealedAt: Long,
    /** True once the dataset it was sealed from is gone from the device; see [SealedAccountEntity.armed]. */
    val armed: Boolean,
)

/**
 * Durable, owner-keyed storage for [SealedAccount]s. Untouched by account isolation.
 *
 * The production store is a table in the encrypted notes database ([RoomSealedAccountStore]), so a
 * restore can delete its row in the same transaction that writes the notes back.
 */
interface SealedAccountStore {
    /** Replaces whatever is sealed for [SealedAccount.ownerUid]. */
    suspend fun put(account: SealedAccount)

    suspend fun all(): List<SealedAccount>

    /** Marks every sealed library as safe to restore: the dataset each came from has been wiped. */
    suspend fun armAll()

    suspend fun remove(ownerUid: String)
}

class RoomSealedAccountStore(private val dao: SealedAccountDao) : SealedAccountStore {
    override suspend fun put(account: SealedAccount) = dao.upsert(
        SealedAccountEntity(
            ownerUid = account.ownerUid,
            payload = account.payload,
            sealedAt = account.sealedAt,
            armed = account.armed,
        ),
    )

    override suspend fun all(): List<SealedAccount> = dao.getAll().map {
        SealedAccount(ownerUid = it.ownerUid, payload = it.payload, sealedAt = it.sealedAt, armed = it.armed)
    }

    override suspend fun armAll() = dao.armAll()

    override suspend fun remove(ownerUid: String) = dao.delete(ownerUid)
}

/** For tests and for wiring that has no database. */
class InMemorySealedAccountStore : SealedAccountStore {
    private val accounts = linkedMapOf<String, SealedAccount>()

    override suspend fun put(account: SealedAccount) {
        accounts[account.ownerUid] = account
    }

    override suspend fun all(): List<SealedAccount> = accounts.values.toList()

    override suspend fun armAll() {
        for (key in accounts.keys.toList()) accounts[key] = accounts.getValue(key).copy(armed = true)
    }

    override suspend fun remove(ownerUid: String) {
        accounts.remove(ownerUid)
    }
}
