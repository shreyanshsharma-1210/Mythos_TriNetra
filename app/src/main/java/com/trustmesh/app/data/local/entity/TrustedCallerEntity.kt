package com.trustmesh.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trusted_callers")
data class TrustedCallerEntity(
    @PrimaryKey
    val phoneNumber: String,
    val name: String,
    val addedAt: Long
)
