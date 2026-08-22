package com.trustmesh.app.vcd.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM wrapping for voiceprints, keyed by a non-exportable Android Keystore key.
 *
 * The key never leaves the Keystore (hardware-backed where the device supports it), so a
 * voiceprint blob lifted off the filesystem — by a backup, a file manager, or an adb pull — is
 * inert. This is the mechanism behind FR-VOICE-ENR-4: the only long-lived artefact of an
 * enrolment is a vector, and even that vector is unreadable off-device.
 *
 * GCM is used rather than CBC so tampering with a stored voiceprint fails loudly on decrypt
 * instead of silently yielding a garbage embedding that would then be scored against a caller.
 */
object VoiceprintCrypto {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "vcd_voiceprint_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /** Thrown when a stored voiceprint cannot be decrypted — corrupt, tampered, or key lost. */
    class VoiceprintDecryptException(message: String, cause: Throwable?) :
        RuntimeException(message, cause)

    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected GCM IV length ${iv.size}" }
        val body = cipher.doFinal(plaintext)
        // Layout: [12-byte IV][ciphertext || 16-byte GCM tag]
        return iv + body
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "voiceprint blob too short to contain an IV" }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_BYTES),
            )
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        } catch (t: Throwable) {
            throw VoiceprintDecryptException(
                "Stored voiceprint could not be decrypted. If the device was restored from a " +
                    "backup or the app data was moved, the Keystore key no longer exists and the " +
                    "contact must be enrolled again.",
                t,
            )
        }
    }

    /**
     * Deletes the wrapping key. Every stored voiceprint becomes permanently unrecoverable, which
     * is what makes a bulk revoke actually final rather than a row deletion someone could undo.
     */
    fun destroyKey() {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // A fresh IV per encryption is mandatory for GCM; reusing one would leak plaintext
                // relationships between voiceprints.
                .setRandomizedEncryptionRequired(true)
                // No user-authentication gate: the foreground verification service has to decrypt
                // a voiceprint mid-call, and a lock-screen prompt at that moment would break the
                // feature exactly when the user needs it.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
