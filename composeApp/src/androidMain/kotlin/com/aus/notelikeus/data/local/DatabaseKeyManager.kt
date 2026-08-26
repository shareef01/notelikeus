package com.aus.notelikeus.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Holds the SQLCipher passphrase.
 *
 * Primary store: AES-GCM file under [Context.getFilesDir], key in AndroidKeyStore
 * (replacement for deprecated EncryptedSharedPreferences).
 * Legacy: one-time read from ESP, then migrate and clear.
 */
class DatabaseKeyManager(
    private val context: Context
) {
    private val lock = Any()

    fun getPassphrase(): ByteArray = synchronized(lock) {
        when (val existing = readFromKeystoreFile()) {
            is PassphraseReadResult.Decrypted -> return existing.passphrase
            PassphraseReadResult.Absent -> Unit // First run: nothing to preserve.
            PassphraseReadResult.Corrupt -> preserveUnreadablePassphraseFile()
        }

        val legacy = readFromLegacyEsp()
        if (legacy != null) {
            if (writeToKeystoreFile(legacy)) {
                clearLegacyEsp()
            }
            return legacy
        }

        val generated = ByteArray(32).also { SecureRandom().nextBytes(it) }
        if (!writeToKeystoreFile(generated)) {
            // Fall back so a Keystore failure does not leave us without persistence.
            writeToLegacyEsp(generated)
            Log.w(TAG, "Persisted DB passphrase via legacy ESP; Keystore file write failed")
        }
        return generated
    }

    private fun passphraseFile(): File = File(context.filesDir, PASSPHRASE_FILE)

    private sealed interface PassphraseReadResult {
        object Absent : PassphraseReadResult
        data class Decrypted(val passphrase: ByteArray) : PassphraseReadResult
        object Corrupt : PassphraseReadResult
    }

    private fun readFromKeystoreFile(): PassphraseReadResult {
        val file = passphraseFile()
        if (!file.exists()) return PassphraseReadResult.Absent
        return try {
            val hex = PassphraseFileCodec.decrypt(getOrCreateSecretKey(), file.readBytes())
            PassphraseReadResult.Decrypted(hex.hexToByteArray())
        } catch (error: Exception) {
            Log.e(
                TAG,
                "Passphrase file present but undecryptable; AndroidKeyStore key was invalidated " +
                    "and the existing DB passphrase cannot be recovered from it",
                error
            )
            PassphraseReadResult.Corrupt
        }
    }

    /**
     * The passphrase file exists but its AndroidKeyStore key can no longer decrypt it — the key was
     * invalidated (a device-to-device transfer, a lock-screen credential reset, or the key simply
     * not surviving to the new install). Move the unreadable blob aside instead of deleting it, so
     * the original key material survives in case the keystore key becomes readable again. The app
     * continues with a fresh key; [PlaintextDatabaseMigrator] quarantines (never deletes) any
     * existing database it cannot open with that key.
     */
    private fun preserveUnreadablePassphraseFile() {
        val file = passphraseFile()
        if (!file.exists()) return
        val preserved = File(file.parent, "$PASSPHRASE_FILE.unrecoverable-${System.currentTimeMillis()}")
        if (!file.renameTo(preserved)) {
            Log.w(TAG, "Failed to preserve unreadable passphrase file")
        }
    }

    /**
     * Persists the passphrase under the AndroidKeyStore key, replacing that key if it has died.
     *
     * Returns false only when the second attempt fails too; the caller then falls back to the
     * legacy ESP and the database still opens.
     */
    private fun writeToKeystoreFile(passphrase: ByteArray): Boolean {
        val payload = encryptUnderKeystoreKey(passphrase)
            // Encryption itself failed, which is the one signal that proves the key rather than the
            // filesystem is at fault — an invalidated AndroidKeyStore key still *exists*, so
            // getOrCreateSecretKey keeps handing back the same dead alias and every attempt, this
            // launch and every launch after, fails identically. Left alone that is permanent: the
            // passphrase falls back to the legacy ESP and stays there for good, which is precisely
            // the deprecated store this class was written to replace.
            //
            // Deleting the alias is safe here and only here. By this point the key protects
            // nothing: either no passphrase file exists (first run, or the legacy migration), or
            // readFromKeystoreFile already found it undecryptable and preserveUnreadablePassphraseFile
            // moved it aside. The decrypt path deliberately does the opposite and keeps the alias,
            // because dropping it would destroy the only chance of ever reading that preserved blob.
            ?: run {
                if (!deleteInvalidatedKey()) return false
                encryptUnderKeystoreKey(passphrase) ?: return false
            }
        return publishByRename(payload)
    }

    /** The encrypted payload, or null when the Keystore key could not produce one. */
    private fun encryptUnderKeystoreKey(passphrase: ByteArray): ByteArray? = try {
        PassphraseFileCodec.encrypt(getOrCreateSecretKey(), passphrase.toHexString())
    } catch (error: Exception) {
        Log.w(TAG, "Keystore key could not encrypt the passphrase", error)
        null
    }

    /**
     * Publishes the passphrase only by rename, never by writing to the live path.
     *
     * The previous fallback wrote the payload straight to [passphraseFile] when the rename failed.
     * A crash part-way through that write leaves a truncated file, which reads back as
     * [PassphraseReadResult.Corrupt] on the next launch — and that path generates a *fresh* key,
     * which in turn means [PlaintextDatabaseMigrator] cannot open the existing database and
     * quarantines it. Trading a working keystore file for a quarantined database is a bad deal, so
     * failing here is better: the caller falls back to the legacy ESP and the database still opens.
     *
     * A failure here says nothing about the key, which is why it never triggers the replacement
     * above: deleting a healthy key because a rename lost a race would make the existing passphrase
     * file undecryptable and quarantine the database — the exact outcome this method exists to
     * avoid.
     */
    private fun publishByRename(payload: ByteArray): Boolean {
        val tmp = File(context.filesDir, "$PASSPHRASE_FILE.tmp")
        return try {
            tmp.writeBytes(payload)
            if (tmp.renameTo(passphraseFile())) {
                true
            } else {
                tmp.delete()
                Log.w(TAG, "Could not publish passphrase file by rename; leaving previous state intact")
                false
            }
        } catch (error: Exception) {
            tmp.delete()
            Log.w(TAG, "Failed to write Keystore passphrase file", error)
            false
        }
    }

    /** Drops the unusable alias so the next [getOrCreateSecretKey] generates a working one. */
    private fun deleteInvalidatedKey(): Boolean = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        Log.w(TAG, "Deleted unusable AndroidKeyStore key; regenerating")
        true
    } catch (error: Exception) {
        Log.e(TAG, "Could not delete unusable AndroidKeyStore key", error)
        false
    }

    private fun getOrCreateSecretKey(): SecretKey {
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

    private fun legacyEsp(): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            LEGACY_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (error: Exception) {
        Log.w(TAG, "Legacy ESP unavailable", error)
        null
    }

    private fun readFromLegacyEsp(): ByteArray? {
        val hex = legacyEsp()?.getString(LEGACY_PASSPHRASE_KEY, null) ?: return null
        return try {
            hex.hexToByteArray()
        } catch (error: Exception) {
            Log.w(TAG, "Legacy ESP passphrase is not valid hex; treating as absent", error)
            null
        }
    }

    private fun writeToLegacyEsp(passphrase: ByteArray) {
        try {
            legacyEsp()?.edit()?.putString(LEGACY_PASSPHRASE_KEY, passphrase.toHexString())?.apply()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to persist passphrase to legacy ESP", error)
        }
    }

    private fun clearLegacyEsp() {
        try {
            legacyEsp()?.edit()?.remove(LEGACY_PASSPHRASE_KEY)?.apply()
            context.deleteSharedPreferences(LEGACY_PREFS_NAME)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to clear legacy ESP after migration", error)
        }
    }

    companion object {
        private const val TAG = "DatabaseKeyManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "notelikeus_db_passphrase_aes"
        const val PASSPHRASE_FILE = "db_passphrase.enc"

        private const val LEGACY_PREFS_NAME = "db_security_prefs"
        private const val LEGACY_PASSPHRASE_KEY = "db_passphrase"

        internal fun ByteArray.toHexString(): String =
            joinToString(separator = "") { byte -> "%02x".format(byte) }

        internal fun String.hexToByteArray(): ByteArray =
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
