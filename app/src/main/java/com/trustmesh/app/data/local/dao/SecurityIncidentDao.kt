package com.trustmesh.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.trustmesh.app.core.incident.IncidentStatus
import com.trustmesh.app.data.local.entity.SecurityIncidentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SecurityIncidentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncident(incident: SecurityIncidentEntity)

    @Update
    suspend fun updateIncident(incident: SecurityIncidentEntity)

    @Query("SELECT * FROM security_incidents ORDER BY updatedAt DESC")
    fun getAllIncidentsFlow(): Flow<List<SecurityIncidentEntity>>

    @Query("SELECT * FROM security_incidents WHERE status = :status ORDER BY updatedAt DESC")
    suspend fun getIncidentsByStatus(status: IncidentStatus): List<SecurityIncidentEntity>
    
    @Query("SELECT * FROM security_incidents WHERE incidentId = :id LIMIT 1")
    suspend fun getIncidentById(id: String): SecurityIncidentEntity?
}
