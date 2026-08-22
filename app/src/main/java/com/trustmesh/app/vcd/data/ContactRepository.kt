package com.trustmesh.app.vcd.data

import android.util.Log
import com.trustmesh.app.vcd.data.crypto.VoiceprintCrypto
import com.trustmesh.app.vcd.data.db.ContactDao
import com.trustmesh.app.vcd.data.db.ContactEntity
import com.trustmesh.app.vcd.ml.Vec
import com.trustmesh.app.vcd.pipeline.Voiceprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A contact as the UI sees it. The embedding stays encrypted until something needs to compare. */
data class EnrolledContact(
    val id: Long,
    val name: String,
    val relationship: String?,
    val createdAtEpochMs: Long,
    val enrolledSeconds: Float,
    val embeddingDim: Int,
    val modelId: String,
    /** False when this print came from a different model build and can no longer be compared. */
    val usableWithCurrentModel: Boolean,
    /** Median synthetic_probability on this contact's own known-genuine enrolment audio. */
    val baselineSynthetic: Float?,
    /** Channel conditions this contact has a voiceprint for, e.g. mic, voip-wb, voip-nb. */
    val variantLabels: List<String>,
) {
    /** True once this contact has prints for call-like channels, not only the microphone. */
    val channelMatched: Boolean get() = variantLabels.size > 1
}

class ContactRepository(
    private val dao: ContactDao,
    /** Identifies the encoder build that produced stored prints. */
    private val currentModelId: () -> String,
) {

    fun observeContacts(): Flow<List<EnrolledContact>> =
        dao.observeAll().map { list -> list.map { it.toUi(currentModelId()) } }

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    suspend fun get(id: Long): EnrolledContact? = withContext(Dispatchers.IO) {
        dao.getById(id)?.toUi(currentModelId())
    }

    /**
     * Stores every variant of one enrolment under a single contact.
     *
     * The embeddings are concatenated into one blob and encrypted together, so a contact stays
     * exactly one encrypted object with one key operation, rather than N rows that could drift
     * out of step with each other.
     */
    suspend fun enroll(
        name: String,
        relationship: String?,
        voiceprints: List<Voiceprint>,
        enrolledSeconds: Float,
        consentAcknowledgedAtEpochMs: Long,
    ): Long = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "a contact needs a name" }
        require(voiceprints.isNotEmpty()) { "refusing to store a contact with no voiceprint" }
        val dim = voiceprints.first().embedding.size
        require(dim > 0) { "refusing to store an empty voiceprint" }
        require(voiceprints.all { it.embedding.size == dim }) {
            "all variants must share a dimensionality"
        }
        require(consentAcknowledgedAtEpochMs > 0) {
            "refusing to store a voiceprint with no recorded consent timestamp"
        }

        val packed = FloatArray(dim * voiceprints.size)
        voiceprints.forEachIndexed { i, print ->
            System.arraycopy(print.embedding, 0, packed, i * dim, dim)
        }

        dao.insert(
            ContactEntity(
                name = name.trim(),
                relationship = relationship?.trim()?.takeIf { it.isNotEmpty() },
                createdAtEpochMs = System.currentTimeMillis(),
                enrolledSeconds = enrolledSeconds,
                voiceprintCipher = VoiceprintCrypto.encrypt(Vec.toBytes(packed)),
                embeddingDim = dim,
                modelId = currentModelId(),
                consentAcknowledgedAtEpochMs = consentAcknowledgedAtEpochMs,
                // The microphone variant's reading, kept so the contact list has one number to
                // show without unpacking every variant.
                baselineSynthetic = voiceprints.first().baselineSynthetic,
                variantLabels = voiceprints.joinToString(",") { it.label },
                variantBaselines = voiceprints.joinToString(",") {
                    it.baselineSynthetic?.toString() ?: ""
                },
            )
        )
    }

    /**
     * Decrypts every stored variant for comparison.
     *
     * Returns an empty list when the row is gone, was made by a different encoder, or will not
     * decrypt. Callers treat empty as "cannot verify" rather than substituting anything: a
     * comparison that was asked for and cannot be made is reported, never silently skipped.
     */
    suspend fun loadVoiceprints(id: Long): List<Voiceprint> = withContext(Dispatchers.IO) {
        val row = dao.getById(id) ?: return@withContext emptyList()
        if (row.modelId != currentModelId()) {
            Log.w(
                TAG,
                "voiceprint for contact $id was made by model '${row.modelId}' but the app now " +
                    "bundles '${currentModelId()}'; refusing to compare across encoders",
            )
            return@withContext emptyList()
        }

        val packed = try {
            Vec.fromBytes(VoiceprintCrypto.decrypt(row.voiceprintCipher))
        } catch (t: Throwable) {
            Log.e(TAG, "voiceprint for contact $id failed to decrypt", t)
            return@withContext emptyList()
        }

        val dim = row.embeddingDim
        if (dim <= 0 || packed.size % dim != 0) {
            Log.e(TAG, "voiceprint blob for contact $id is not a whole number of ${dim}-d prints")
            return@withContext emptyList()
        }

        // Rows enrolled before multi-condition prints existed hold exactly one, and are read as
        // the microphone condition with the single baseline they were stored with.
        val labels = row.variantLabels?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf(LEGACY_LABEL)
        val baselines = row.variantBaselines?.split(",")?.map { it.trim().toFloatOrNull() }
            ?: listOf(row.baselineSynthetic)

        List(packed.size / dim) { i ->
            Voiceprint(
                label = labels.getOrElse(i) { "variant-$i" },
                embedding = packed.copyOfRange(i * dim, (i + 1) * dim),
                baselineSynthetic = baselines.getOrNull(i) ?: row.baselineSynthetic,
            )
        }
    }

    /** FR-VOICE-ENR-5 — revoke consent and delete the voiceprint. */
    suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) { dao.deleteById(id) > 0 }

    /** Deletes every voiceprint and destroys the wrapping key, so nothing is recoverable. */
    suspend fun deleteAllAndDestroyKey() = withContext(Dispatchers.IO) {
        dao.deleteAll()
        VoiceprintCrypto.destroyKey()
    }

    private fun ContactEntity.toUi(modelId: String) = EnrolledContact(
        id = id,
        name = name,
        relationship = relationship,
        createdAtEpochMs = createdAtEpochMs,
        enrolledSeconds = enrolledSeconds,
        embeddingDim = embeddingDim,
        modelId = this.modelId,
        usableWithCurrentModel = this.modelId == modelId,
        baselineSynthetic = baselineSynthetic,
        variantLabels = variantLabels?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf(LEGACY_LABEL),
    )

    private companion object {
        const val TAG = "ContactRepository"

        /** How a pre-multi-condition print is labelled: it was recorded straight off the mic. */
        const val LEGACY_LABEL = "mic"
    }
}
