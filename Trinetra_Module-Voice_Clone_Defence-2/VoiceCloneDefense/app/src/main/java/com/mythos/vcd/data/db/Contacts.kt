package com.mythos.vcd.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * An enrolled contact.
 *
 * There is deliberately no column for audio, a file path, or a duration of retained recording.
 * The only trace an enrolment leaves is [voiceprintCipher], and that is Keystore-wrapped.
 *
 * [modelId] is stored alongside it because a cosine similarity between embeddings from two
 * different encoders is a meaningless number that would still render as a confident percentage.
 * If the bundled model changes, prints from the old one are marked stale rather than compared.
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val relationship: String?,
    val createdAtEpochMs: Long,
    /** Seconds of speech the embedding was derived from — useful context, not the audio itself. */
    val enrolledSeconds: Float,
    val voiceprintCipher: ByteArray,
    val embeddingDim: Int,
    val modelId: String,
    val consentAcknowledgedAtEpochMs: Long,
    /**
     * Median synthetic_probability measured on this contact's own enrolment audio.
     *
     * Enrolment audio is known-genuine — the person recorded it in front of us, having just given
     * consent — so this is a ground-truth reading of how the anti-spoofing model behaves on this
     * particular voice, microphone and recording chain.
     *
     * It exists because that reading is not always sane. On real recordings from some sources the
     * ASVspoof-trained model returns ~0.999 for genuine speech, which would make the app accuse a
     * real person of being a clone every single time. Storing the baseline lets the app notice
     * that the detector is unusable for this voice and say so, instead of reporting a confident
     * and completely wrong CRITICAL. Null means it was not measured (older enrolments).
     */
    val baselineSynthetic: Float? = null,
    /**
     * Channel labels for the prints packed into [voiceprintCipher], comma-separated and in order,
     * e.g. "mic,voip-wb,voip-nb".
     *
     * One recording yields several prints, one per channel condition, because a microphone print
     * does not transfer to a voice arriving over a call — measured at 0.7655 against narrowband
     * call audio from the same speaker, versus 0.9766 for a channel-matched print.
     *
     * Null on rows enrolled before this existed: those hold a single print and are read as "mic".
     */
    val variantLabels: String? = null,
    /** Per-variant anti-spoofing baselines, comma-separated in the same order. "" means unmeasured. */
    val variantBaselines: String? = null,
) {
    override fun equals(other: Any?) = this === other ||
        (other is ContactEntity && id == other.id && name == other.name &&
            voiceprintCipher.contentEquals(other.voiceprintCipher))

    override fun hashCode() = (id.hashCode() * 31 + name.hashCode()) * 31 +
        voiceprintCipher.contentHashCode()
}

@Dao
interface ContactDao {

    @Query("SELECT * FROM contacts ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts ORDER BY createdAtEpochMs DESC")
    suspend fun getAll(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun getById(id: Long): ContactEntity?

    @Insert
    suspend fun insert(contact: ContactEntity): Long

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM contacts")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM contacts")
    suspend fun count(): Int
}

@Database(
    entities = [ContactEntity::class, CallHistoryEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class VcdDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun callHistoryDao(): CallHistoryDao

    companion object {
        /**
         * Adds the anti-spoofing baseline column.
         *
         * A real migration rather than a destructive one: dropping the table would delete
         * voiceprints the user never asked to delete, and deletion here is supposed to be a
         * deliberate act. Existing rows get NULL, which the app reads as "not measured" and
         * handles by suppressing the clone verdict until the contact re-enrols.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN baselineSynthetic REAL")
            }
        }

        /**
         * Adds the multi-condition voiceprint columns.
         *
         * Additive, and existing rows keep working: a null variantLabels means the blob holds one
         * print, read as the microphone condition with the old single baseline. Those contacts get
         * the old behaviour until they re-enrol, which is worse than a channel-matched print but
         * far better than dropping a voiceprint the user never asked to lose.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN variantLabels TEXT")
                db.execSQL("ALTER TABLE contacts ADD COLUMN variantBaselines TEXT")
            }
        }

        /** Adds the Recents table. Additive, so no voiceprint is touched. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS call_history (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        peerName TEXT NOT NULL,
                        contactLabel TEXT,
                        outgoing INTEGER NOT NULL,
                        startedAtEpochMs INTEGER NOT NULL,
                        durationSeconds INTEGER NOT NULL,
                        ending TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
