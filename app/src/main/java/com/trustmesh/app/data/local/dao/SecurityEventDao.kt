package com.trustmesh.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.trustmesh.app.data.local.entity.SecurityEventEntity

@Dao
interface SecurityEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: SecurityEventEntity)

    @Query("SELECT * FROM security_events WHERE timestamp >= :cutoff ORDER BY timestamp ASC")
    suspend fun getRecentEvents(cutoff: Long): List<SecurityEventEntity>
    
    @Query("DELETE FROM security_events")
    suspend fun clearAll()
}
