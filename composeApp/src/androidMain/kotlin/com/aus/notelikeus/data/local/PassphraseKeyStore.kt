package com.aus.notelikeus.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The two AndroidKeyStore operations [DatabaseKeyManager] needs, behind a seam.
 *
 * Extracted for the same reason [PassphraseFileCodec] was: the interesting behaviour cannot be
 * reached otherwise. [DatabaseKeyManager] replaces its key when encryption fails, because an
 * invalidated AndroidKeyStore key still *exists* and would otherwise be handed back on every
 * attempt forever — and there is no way to invalidate a real key from a test. Invalidation is a
 * lock-screen credential reset or a device-to-device transfer, not an API call.
 *
 * So the recovery path was reasoned about and shipped unverified. This interface is what lets a
 * test supply a key that genuinely fails to encrypt, and then observe whether the manager replaces
 * it, replaces it exactly once, and leaves a passphrase file the replacement can read back.
 */
internal interface PassphraseKeyStore {

    /** The key for the passphrase file, generating it if the alias does not exist yet. */
    fun getOrCreateKey(): SecretKey

    /**
     * Drops the alias so the next [getOrCreateKey] generates a fresh one.
     *
     * @return false when the alias could not be removed, which is the caller's signal to give up
     *   rather than retry — a retry would resolve to the same unusable key.
     */
    fun deleteKey(): Boolean
}

/** The real one. Everything here is AndroidKeyStore-specific and untestable off-device. */
internal class AndroidPassphraseKeyStore : PassphraseKeyStore {

    override fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    override fun deleteKey(): Boolean = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        Log.w(TAG, "Deleted unusable AndroidKeyStore key; regenerating")
        true
    } catch (error: Exception) {
        Log.e(TAG, "Could not delete unusable AndroidKeyStore key", error)
        false
    }

    private companion object {
        const val TAG = "PassphraseKeyStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "notelikeus_db_passphrase_aes"
    }
}
