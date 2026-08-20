package com.trustmesh.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.trustmesh.app.data.local.entity.TrustedCallerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrustedCallerDao {
    @Query("SELECT * FROM trusted_callers ORDER BY addedAt DESC")
    fun getTrustedCallersFlow(): Flow<List<TrustedCallerEntity>>

    @Query("SELECT * FROM trusted_callers WHERE phoneNumber = :phoneNumber LIMIT 1")
    suspend fun getTrustedCallerSync(phoneNumber: String): TrustedCallerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrustedCaller(caller: TrustedCallerEntity)

    @Query("DELETE FROM trusted_callers WHERE phoneNumber = :phoneNumber")
    suspend fun deleteTrustedCaller(phoneNumber: String)
}
