package com.trustmesh.app.data.repository

import com.trustmesh.app.core.protection.ProtectionAction
import com.trustmesh.app.core.protection.ProtectionMode
import com.trustmesh.app.data.local.dao.ProtectionPolicyDao
import com.trustmesh.app.data.local.dao.TrustedCallerDao
import com.trustmesh.app.data.local.entity.ProtectionPolicyEntity
import com.trustmesh.app.data.local.entity.TrustedCallerEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProtectionPolicyRepository(
    private val policyDao: ProtectionPolicyDao,
    private val trustedCallerDao: TrustedCallerDao
) {

    fun getPolicyFlow(): Flow<ProtectionPolicyEntity> =
        policyDao.getPolicyFlow().map { it ?: ProtectionPolicyEntity.default() }

    suspend fun getPolicy(): ProtectionPolicyEntity =
        policyDao.getPolicySync() ?: ProtectionPolicyEntity.default()

    suspend fun setMode(mode: ProtectionMode) {
        val current = getPolicy()
        val updated = when (mode) {
            ProtectionMode.STANDARD -> current.copy(
                mode = mode,
                lowRiskBehavior = ProtectionAction.MONITOR_ONLY,
                elevatedRiskBehavior = ProtectionAction.SHOW_COMPACT_WARNING,
                highRiskBehavior = ProtectionAction.SHOW_RISK_CARD,
                criticalRiskBehavior = ProtectionAction.SHOW_SECURITY_INTERVENTION,
                unknownCallerBehavior = ProtectionAction.MONITOR_ONLY,
                autoBlockCritical = false,
                updatedAt = System.currentTimeMillis()
            )
            ProtectionMode.STRICT -> current.copy(
                mode = mode,
                lowRiskBehavior = ProtectionAction.SHOW_COMPACT_WARNING,
                elevatedRiskBehavior = ProtectionAction.SHOW_RISK_CARD,
                highRiskBehavior = ProtectionAction.SHOW_BOTTOM_SHEET,
                criticalRiskBehavior = ProtectionAction.SHOW_SECURITY_INTERVENTION,
                unknownCallerBehavior = ProtectionAction.SHOW_COMPACT_WARNING,
                autoBlockCritical = true,
                updatedAt = System.currentTimeMillis()
            )
            ProtectionMode.CUSTOM -> current.copy(
                mode = mode,
                updatedAt = System.currentTimeMillis()
            )
        }
        policyDao.insertPolicy(updated)
    }

    suspend fun updateCustomPolicy(
        lowRisk: ProtectionAction,
        elevatedRisk: ProtectionAction,
        highRisk: ProtectionAction,
        criticalRisk: ProtectionAction,
        unknownCaller: ProtectionAction,
        autoBlockCritical: Boolean
    ) {
        val current = getPolicy()
        policyDao.insertPolicy(
            current.copy(
                mode = ProtectionMode.CUSTOM,
                lowRiskBehavior = lowRisk,
                elevatedRiskBehavior = elevatedRisk,
                highRiskBehavior = highRisk,
                criticalRiskBehavior = criticalRisk,
                unknownCallerBehavior = unknownCaller,
                autoBlockCritical = autoBlockCritical,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun getTrustedCallersFlow(): Flow<List<TrustedCallerEntity>> =
        trustedCallerDao.getTrustedCallersFlow()

    suspend fun addTrustedCaller(phoneNumber: String, name: String) {
        trustedCallerDao.insertTrustedCaller(
            TrustedCallerEntity(
                phoneNumber = phoneNumber,
                name = name,
                addedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun removeTrustedCaller(phoneNumber: String) {
        trustedCallerDao.deleteTrustedCaller(phoneNumber)
    }

    suspend fun isTrustedCaller(phoneNumber: String): Boolean =
        trustedCallerDao.getTrustedCallerSync(phoneNumber) != null
}
