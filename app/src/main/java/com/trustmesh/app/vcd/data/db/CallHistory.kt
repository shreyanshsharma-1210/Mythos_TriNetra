package com.trustmesh.app.vcd.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One finished call, for the Recents list.
 *
 * Deliberately the app's own history rather than the system call log. Reading the system log would
 * mean asking for READ_CALL_LOG — a permission that covers every cellular call the user has ever
 * made, to show a handful of TRINETRA ones. These calls did not go through the dialler, so they
 * would not be in that log anyway.
 *
 * No audio and no scores are stored here, only what the list needs: who, when, how long, and how it
 * ended. The verdict a call reached is not recorded, because a risk judgement about a person that
 * outlives the call is a different and much heavier thing than a call list.
 */
@Entity(tableName = "call_history")
data class CallHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Display name of the other device, as it appeared over mDNS. */
    val peerName: String,
    /** The address book name the user tapped, when they dialled from Contacts. */
    val contactLabel: String?,
    val outgoing: Boolean,
    val startedAtEpochMs: Long,
    val durationSeconds: Long,
    /** CallEnding name — HUNG_UP, DECLINED, MISSED, UNANSWERED, FAILED. */
    val ending: String,
)

@Dao
interface CallHistoryDao {

    @Query("SELECT * FROM call_history ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<CallHistoryEntity>>

    @Insert
    suspend fun insert(entry: CallHistoryEntity): Long

    @Query("DELETE FROM call_history")
    suspend fun clear(): Int
}
