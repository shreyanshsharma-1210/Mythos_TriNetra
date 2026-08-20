package com.trustmesh.app.data.local.converter

import androidx.room.TypeConverter
import com.trustmesh.app.core.events.EventType
import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.identity.Confidence
import com.trustmesh.app.core.identity.IdentitySource
import com.trustmesh.app.core.identity.IdentityType
import com.trustmesh.app.core.identity.ReputationLevel
import com.trustmesh.app.core.incident.IncidentStatus
import com.trustmesh.app.core.incident.IncidentType
import com.trustmesh.app.core.intelligence.risk.RiskFactorType

class TrustMeshConverters {

    @TypeConverter
    fun fromConfidence(value: Confidence?): String? = value?.name

    @TypeConverter
    fun toConfidence(value: String?): Confidence? = value?.let { Confidence.valueOf(it) }

    @TypeConverter
    fun fromRiskLevel(value: RiskLevel?): String? = value?.name

    @TypeConverter
    fun toRiskLevel(value: String?): RiskLevel? = value?.let { RiskLevel.valueOf(it) }

    @TypeConverter
    fun fromEventType(value: EventType?): String? = value?.name

    @TypeConverter
    fun toEventType(value: String?): EventType? = value?.let { EventType.valueOf(it) }

    @TypeConverter
    fun fromIdentityType(value: IdentityType?): String? = value?.name

    @TypeConverter
    fun toIdentityType(value: String?): IdentityType? = value?.let { IdentityType.valueOf(it) }

    @TypeConverter
    fun fromIdentitySource(value: IdentitySource?): String? = value?.name

    @TypeConverter
    fun toIdentitySource(value: String?): IdentitySource? = value?.let { IdentitySource.valueOf(it) }
    
    @TypeConverter
    fun fromRiskFactorType(value: RiskFactorType?): String? = value?.name

    @TypeConverter
    fun toRiskFactorType(value: String?): RiskFactorType? = value?.let { RiskFactorType.valueOf(it) }

    @TypeConverter
    fun fromCallerCategory(value: com.trustmesh.app.core.identity.CallerCategory?): String? = value?.name

    @TypeConverter
    fun toCallerCategory(value: String?): com.trustmesh.app.core.identity.CallerCategory? = value?.let { com.trustmesh.app.core.identity.CallerCategory.valueOf(it) }

    @TypeConverter
    fun fromReputationLevel(value: com.trustmesh.app.core.identity.ReputationLevel?): String? = value?.name

    @TypeConverter
    fun toReputationLevel(value: String?): com.trustmesh.app.core.identity.ReputationLevel? = value?.let { com.trustmesh.app.core.identity.ReputationLevel.valueOf(it) }
    @TypeConverter
    fun fromIncidentType(value: IncidentType?): String? = value?.name

    @TypeConverter
    fun toIncidentType(value: String?): IncidentType? = value?.let { IncidentType.valueOf(it) }

    @TypeConverter
    fun fromIncidentStatus(value: IncidentStatus?): String? = value?.name

    @TypeConverter
    fun toIncidentStatus(value: String?): IncidentStatus? = value?.let { IncidentStatus.valueOf(it) }

    @TypeConverter
    fun fromProtectionMode(value: com.trustmesh.app.core.protection.ProtectionMode?): String? = value?.name

    @TypeConverter
    fun toProtectionMode(value: String?): com.trustmesh.app.core.protection.ProtectionMode? = value?.let { com.trustmesh.app.core.protection.ProtectionMode.valueOf(it) }

    @TypeConverter
    fun fromProtectionAction(value: com.trustmesh.app.core.protection.ProtectionAction?): String? = value?.name

    @TypeConverter
    fun toProtectionAction(value: String?): com.trustmesh.app.core.protection.ProtectionAction? = value?.let { com.trustmesh.app.core.protection.ProtectionAction.valueOf(it) }
}
