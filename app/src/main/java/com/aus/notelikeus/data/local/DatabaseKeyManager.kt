package com.aus.notelikeus.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the SQLCipher passphrase.
 *
 * Primary store: AES-GCM file under [Context.getFilesDir], key in AndroidKeyStore
 * (replacement for deprecated EncryptedSharedPreferences).
 * Legacy: one-time read from ESP, then migrate and clear.
 */
@Singleton
class DatabaseKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val lock = Any()

    fun getPassphrase(): ByteArray = synchronized(lock) {
        readFromKeystoreFile()?.let { return it }

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

    private fun readFromKeystoreFile(): ByteArray? {
        val file = passphraseFile()
        if (!file.exists()) return null
        return try {
            val hex = PassphraseFileCodec.decrypt(getOrCreateSecretKey(), file.readBytes())
            hex.hexToByteArray()
        } catch (error: Exception) {
            Log.w(TAG, "Failed to read Keystore passphrase file", error)
            null
        }
    }

    private fun writeToKeystoreFile(passphrase: ByteArray): Boolean {
        return try {
            val payload = PassphraseFileCodec.encrypt(getOrCreateSecretKey(), passphrase.toHexString())
            val tmp = File(context.filesDir, "$PASSPHRASE_FILE.tmp")
            tmp.writeBytes(payload)
            if (!tmp.renameTo(passphraseFile())) {
                passphraseFile().writeBytes(payload)
                tmp.delete()
            }
            true
        } catch (error: Exception) {
            Log.w(TAG, "Failed to write Keystore passphrase file", error)
            false
        }
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
        } catch (_: Exception) {
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

/** AES-GCM file payload codec (testable with a software [SecretKey]). */
internal object PassphraseFileCodec {
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'L'.code.toByte(), 'U'.code.toByte(), '1'.code.toByte())
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128

    fun encrypt(key: SecretKey, utf8Plaintext: String): ByteArray {
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(utf8Plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(MAGIC.size + iv.size + ciphertext.size)
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
        System.arraycopy(iv, 0, out, MAGIC.size, iv.size)
        System.arraycopy(ciphertext, 0, out, MAGIC.size + iv.size, ciphertext.size)
        return out
    }

    fun decrypt(key: SecretKey, payload: ByteArray): String {
        require(payload.size >= MAGIC.size + IV_SIZE + 16) { "Passphrase file too short" }
        require(payload.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Bad passphrase file magic" }
        val iv = payload.copyOfRange(MAGIC.size, MAGIC.size + IV_SIZE)
        val ciphertext = payload.copyOfRange(MAGIC.size + IV_SIZE, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }
}

object PlaintextDatabaseMigrator {

    fun migrateToEncryptedIfNeeded(
        context: Context,
        databaseName: String,
        passphrase: ByteArray
    ) {
        val databaseFile = context.getDatabasePath(databaseName)
        if (!databaseFile.exists()) return

        val encryptedTemp = context.getDatabasePath("$databaseName-encrypted-temp")
        if (encryptedTemp.exists()) encryptedTemp.delete()

        // Room opens the DB with the raw passphrase bytes — not a hex string.
        if (canOpenEncrypted(databaseFile, passphrase)) return

        val plainDb = try {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                "",
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null
            )
        } catch (_: Exception) {
            when {
                canOpenEncrypted(databaseFile, passphrase) -> return
                else -> {
                    // Can't open it plaintext, and not with our current passphrase either. This is
                    // reachable after an android:allowBackup restore to a new device: the DB file
                    // comes along, but the Keystore-bound key in DatabaseKeyManager does not, so an
                    // otherwise-valid encrypted database looks unopenable. Never delete outright —
                    // quarantine (rename) it so Room can create a fresh DB while the original bytes
                    // stay recoverable on disk instead of being destroyed.
                    quarantineDatabaseFiles(context, databaseName)
                    return
                }
            }
        }

        try {
            val escapedPath = encryptedTemp.absolutePath.replace("'", "''")
            plainDb.execSQL(
                "ATTACH DATABASE '$escapedPath' AS encrypted KEY \"x'${passphrase.toHex()}'\""
            )
            plainDb.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            plainDb.execSQL("DETACH DATABASE encrypted")
        } finally {
            plainDb.close()
        }

        databaseFile.delete()
        if (!encryptedTemp.renameTo(databaseFile)) {
            // The encrypted export still exists at encryptedTemp — only clean up leftover
            // -shm/-wal files here rather than touching the export itself.
            quarantineDatabaseFiles(context, databaseName)
        }
    }

    private fun canOpenEncrypted(databaseFile: File, passphrase: ByteArray): Boolean {
        return try {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                passphrase,
                null,
                SQLiteDatabase.OPEN_READONLY,
                null,
                null
            ).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Renames the database (and its -shm/-wal files) aside instead of deleting them, so a database
     * we merely failed to *open* (e.g. encrypted with a key that didn't survive a device restore)
     * is never destroyed outright — it stays on disk, recoverable, under a quarantined name.
     */
    private fun quarantineDatabaseFiles(context: Context, databaseName: String) {
        val suffix = System.currentTimeMillis()
        for (name in listOf(databaseName, "$databaseName-shm", "$databaseName-wal")) {
            val file = context.getDatabasePath(name)
            if (file.exists()) {
                file.renameTo(File(file.parent, "$name.quarantined-$suffix"))
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }
}
