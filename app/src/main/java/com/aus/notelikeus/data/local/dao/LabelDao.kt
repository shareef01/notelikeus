package com.aus.notelikeus.data.local.dao

import androidx.room.*
import com.aus.notelikeus.data.local.entity.LabelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LabelDao {

    @Query("SELECT * FROM labels ORDER BY name ASC")
    fun getAllLabels(): Flow<List<LabelEntity>>

    @Query("SELECT * FROM labels ORDER BY name ASC")
    suspend fun getAllLabelsOnce(): List<LabelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLabel(label: LabelEntity): Long

    @Update
    suspend fun updateLabel(label: LabelEntity)

    @Delete
    suspend fun deleteLabel(label: LabelEntity)

    @Query("SELECT * FROM labels WHERE id = :id")
    suspend fun getLabelById(id: Long): LabelEntity?
}
