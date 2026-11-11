package com.aus.notelikeus.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aus.notelikeus.data.local.entity.SealedAccountEntity

@Dao
interface SealedAccountDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SealedAccountEntity)

    @Query("SELECT * FROM sealed_accounts")
    suspend fun getAll(): List<SealedAccountEntity>

    @Query("UPDATE sealed_accounts SET armed = 1 WHERE armed = 0")
    suspend fun armAll()

    @Query("DELETE FROM sealed_accounts WHERE ownerUid = :ownerUid")
    suspend fun delete(ownerUid: String)
}
