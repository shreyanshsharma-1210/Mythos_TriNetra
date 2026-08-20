package com.trustmesh.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.trustmesh.app.data.local.entity.ProtectionPolicyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProtectionPolicyDao {
    @Query("SELECT * FROM protection_policy WHERE policyId = 'SINGLETON_POLICY'")
    fun getPolicyFlow(): Flow<ProtectionPolicyEntity?>

    @Query("SELECT * FROM protection_policy WHERE policyId = 'SINGLETON_POLICY'")
    suspend fun getPolicySync(): ProtectionPolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPolicy(policy: ProtectionPolicyEntity)
}
