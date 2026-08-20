package com.trustmesh.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.trustmesh.app.core.protection.ProtectionMode
import com.trustmesh.app.core.protection.ProtectionAction

@Entity(tableName = "protection_policy")
data class ProtectionPolicyEntity(
    @PrimaryKey
    val policyId: String = "SINGLETON_POLICY",
    val mode: ProtectionMode,
    
    // Settings for CUSTOM mode
    val lowRiskBehavior: ProtectionAction,
    val elevatedRiskBehavior: ProtectionAction,
    val highRiskBehavior: ProtectionAction,
    val criticalRiskBehavior: ProtectionAction,
    val unknownCallerBehavior: ProtectionAction,
    
    // Explicit aggressive settings
    val autoBlockCritical: Boolean,
    
    val updatedAt: Long
) {
    companion object {
        fun default(): ProtectionPolicyEntity {
            return ProtectionPolicyEntity(
                mode = ProtectionMode.STANDARD,
                lowRiskBehavior = ProtectionAction.MONITOR_ONLY,
                elevatedRiskBehavior = ProtectionAction.SHOW_COMPACT_WARNING,
                highRiskBehavior = ProtectionAction.SHOW_RISK_CARD,
                criticalRiskBehavior = ProtectionAction.SHOW_SECURITY_INTERVENTION,
                unknownCallerBehavior = ProtectionAction.MONITOR_ONLY,
                autoBlockCritical = false,
                updatedAt = System.currentTimeMillis()
            )
        }
    }
}
