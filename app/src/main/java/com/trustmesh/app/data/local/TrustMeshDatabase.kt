package com.trustmesh.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.trustmesh.app.data.local.converter.TrustMeshConverters
import com.trustmesh.app.data.local.dao.InteractionDao
import com.trustmesh.app.data.local.dao.SecurityEventDao
import com.trustmesh.app.data.local.dao.SecurityIncidentDao
import com.trustmesh.app.data.local.dao.ProtectionPolicyDao
import com.trustmesh.app.data.local.dao.TrustedCallerDao
import com.trustmesh.app.data.local.entity.EvidenceEntryEntity
import com.trustmesh.app.data.local.entity.InteractionEntity
import com.trustmesh.app.data.local.entity.ProtectionPolicyEntity
import com.trustmesh.app.data.local.entity.RiskAssessmentEntity
import com.trustmesh.app.data.local.entity.RiskFactorEntity
import com.trustmesh.app.data.local.entity.SecurityEventEntity
import com.trustmesh.app.data.local.entity.SecurityIncidentEntity
import com.trustmesh.app.data.local.entity.TimelineEntryEntity
import com.trustmesh.app.data.local.entity.TrustedCallerEntity

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        InteractionEntity::class,
        RiskAssessmentEntity::class,
        RiskFactorEntity::class,
        TimelineEntryEntity::class,
        EvidenceEntryEntity::class,
        SecurityEventEntity::class,
        SecurityIncidentEntity::class,
        ProtectionPolicyEntity::class,
        TrustedCallerEntity::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(TrustMeshConverters::class)
abstract class TrustMeshDatabase : RoomDatabase() {

    abstract fun interactionDao(): InteractionDao
    abstract fun securityEventDao(): SecurityEventDao
    abstract fun securityIncidentDao(): SecurityIncidentDao
    abstract fun protectionPolicyDao(): ProtectionPolicyDao
    abstract fun trustedCallerDao(): TrustedCallerDao

    companion object {
        @Volatile
        private var INSTANCE: TrustMeshDatabase? = null
        
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE interactions ADD COLUMN repCategory TEXT")
                database.execSQL("ALTER TABLE interactions ADD COLUMN repLevel TEXT")
                database.execSQL("ALTER TABLE interactions ADD COLUMN repSpamReports INTEGER")
                database.execSQL("ALTER TABLE interactions ADD COLUMN repFraudReports INTEGER")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `security_incidents` (
                        `incidentId` TEXT NOT NULL,
                        `incidentType` TEXT NOT NULL,
                        `severity` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `resolvedAt` INTEGER,
                        `riskScore` INTEGER NOT NULL,
                        `explanation` TEXT NOT NULL,
                        `recommendedActions` TEXT NOT NULL,
                        `relatedInteractionIds` TEXT NOT NULL,
                        `callerPhoneNumber` TEXT,
                        `callerDisplayName` TEXT,
                        `callerIdentityType` TEXT,
                        `callerIdentityConfidence` TEXT,
                        `callerIdentitySource` TEXT,
                        `repCategory` TEXT,
                        `repLevel` TEXT,
                        `repSpamReports` INTEGER,
                        `repFraudReports` INTEGER,
                        PRIMARY KEY(`incidentId`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `protection_policy` (
                        `policyId` TEXT NOT NULL,
                        `mode` TEXT NOT NULL,
                        `lowRiskBehavior` TEXT NOT NULL,
                        `elevatedRiskBehavior` TEXT NOT NULL,
                        `highRiskBehavior` TEXT NOT NULL,
                        `criticalRiskBehavior` TEXT NOT NULL,
                        `unknownCallerBehavior` TEXT NOT NULL,
                        `autoBlockCritical` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`policyId`)
                    )
                """.trimIndent())
                
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `trusted_callers` (
                        `phoneNumber` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`phoneNumber`)
                    )
                """.trimIndent())
                
                // Insert default policy
                db.execSQL("""
                    INSERT INTO `protection_policy` 
                    (`policyId`, `mode`, `lowRiskBehavior`, `elevatedRiskBehavior`, `highRiskBehavior`, `criticalRiskBehavior`, `unknownCallerBehavior`, `autoBlockCritical`, `updatedAt`)
                    VALUES
                    ('SINGLETON_POLICY', 'STANDARD', 'MONITOR_ONLY', 'SHOW_COMPACT_WARNING', 'SHOW_RISK_CARD', 'SHOW_SECURITY_INTERVENTION', 'MONITOR_ONLY', 0, ${System.currentTimeMillis()})
                """.trimIndent())
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE interactions ADD COLUMN protectionDecision TEXT")
                db.execSQL("ALTER TABLE interactions ADD COLUMN incidentType TEXT")
                db.execSQL("ALTER TABLE interactions ADD COLUMN isBlocked INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): TrustMeshDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TrustMeshDatabase::class.java,
                    "trustmesh.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
