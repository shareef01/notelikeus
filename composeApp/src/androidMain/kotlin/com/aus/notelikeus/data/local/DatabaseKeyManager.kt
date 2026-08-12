package com.aus.notelikeus.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
     * The passphrase file exists but its AndroidKeyStore key is gone (e.g. after a device restore
     * that invalidated keystore keys). Move the unreadable blob aside instead of deleting it, so
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
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Deliberately no caller-supplied IV. AndroidKeyStore keys are generated with
        // setRandomizedEncryptionRequired defaulting to true, and such a key rejects a
        // caller-provided IV on encrypt with InvalidAlgorithmParameterException — which used to
        // throw on every call here, so writeToKeystoreFile always failed and the passphrase fell
        // back to the legacy ESP this class exists to replace. Letting the provider generate the IV
        // keeps randomized encryption required (a stronger policy than turning it off) and behaves
        // the same for the software keys used in tests.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == IV_SIZE) { "Unexpected GCM IV size: ${iv.size}" }
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

    private const val TAG = "PlaintextDatabaseMigrator"

    fun migrateToEncryptedIfNeeded(
        context: Context,
        databaseName: String,
        passphrase: ByteArray
    ) {
        val databaseFile = context.getDatabasePath(databaseName)
        val encryptedTemp = context.getDatabasePath("$databaseName-encrypted-temp")
        val backup = context.getDatabasePath("$databaseName.pre-encrypt")

        if (!databaseFile.exists()) {
            // A previous run may have been interrupted mid-swap (original moved aside but the
            // encrypted copy not yet moved into place). Recover instead of starting fresh: prefer
            // the encrypted copy when it opens with our key, otherwise restore the original source.
            if (encryptedTemp.exists() && canOpenEncrypted(encryptedTemp, passphrase)) {
                if (encryptedTemp.renameTo(databaseFile)) {
                    backup.delete()
                    return
                }
            }
            if (backup.exists() && backup.renameTo(databaseFile)) {
                return
            }
            return
        }

        // A stale export from an earlier interrupted run is safe to remove now: the source database
        // is intact at this point, so no data is destroyed.
        if (encryptedTemp.exists()) encryptedTemp.delete()

        // Room opens the DB with the raw passphrase bytes via SupportOpenHelperFactory(byte[]),
        // which SQLCipher keys through sqlite3_key() and derives with PBKDF2. If the file already
        // opens with that derivation there is nothing to migrate.
        if (canOpenEncrypted(databaseFile, passphrase)) return

        val isPlaintext = try {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                "",
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null
            ).close()
            true
        } catch (_: Exception) {
            false
        }
        if (!isPlaintext) {
            // Can't open it plaintext, and not with our current passphrase either. This is
            // reachable after an android:allowBackup restore to a new device: the DB file comes
            // along, but the Keystore-bound key does not, so an otherwise-valid encrypted database
            // looks unopenable. Never delete outright — quarantine (rename) it so Room can create a
            // fresh DB while the original bytes stay recoverable on disk.
            quarantineDatabaseFiles(context, databaseName)
            return
        }

        try {
            // Create the encrypted copy with the SAME key derivation Room will use: raw passphrase
            // bytes through the byte[] open API (identical code path to SupportOpenHelperFactory).
            // The previous approach — ATTACH ... KEY "x'<hex>'" — was a key-format mismatch: SQLCipher
            // parses that double-quoted text literal as a *raw* key (the x'...' prefix), which
            // bypasses PBKDF2 and produces a file Room cannot open.
            val encryptedDb = SQLiteDatabase.openDatabase(
                encryptedTemp.absolutePath,
                passphrase,
                null,
                SQLiteDatabase.CREATE_IF_NECESSARY,
                null,
                null
            )
            try {
                // Attach the plaintext source (empty key -> plaintext attachment, per SQLCipher)
                // and copy its schema and data into the encrypted 'main' database.
                val escapedSource = databaseFile.absolutePath.replace("'", "''")
                encryptedDb.execSQL("ATTACH DATABASE '$escapedSource' AS plain KEY ''")
                encryptedDb.rawExecSQL("SELECT sqlcipher_export('main', 'plain')")
                encryptedDb.execSQL("DETACH DATABASE plain")
            } finally {
                encryptedDb.close()
            }
        } catch (error: Exception) {
            // The source is untouched; only the partial export is discarded.
            encryptedTemp.delete()
            Log.e(TAG, "Failed to migrate database to encrypted", error)
            quarantineDatabaseFiles(context, databaseName)
            return
        }

        if (!swapEncryptedIntoPlace(databaseFile, encryptedTemp)) {
            quarantineDatabaseFiles(context, databaseName)
            return
        }
        if (!canOpenEncrypted(databaseFile, passphrase)) {
            // The swapped-in file must open with our key; if it does not, do not trust it.
            quarantineDatabaseFiles(context, databaseName)
            return
        }
        // Auxiliary files belonged to the old plaintext database; the fresh encrypted copy starts
        // without them.
        for (name in listOf("$databaseName-journal", "$databaseName-shm", "$databaseName-wal")) {
            context.getDatabasePath(name).delete()
        }
    }

    /**
     * Swaps the encrypted copy into the canonical database path without ever deleting the original
     * before the replacement is in place: the original is first moved to a sidecar backup, the
     * encrypted copy is moved into the canonical path, and only then is the backup removed. A crash
     * or failed rename at any step leaves at least one complete copy on disk.
     */
    internal fun swapEncryptedIntoPlace(databaseFile: File, encryptedTemp: File): Boolean {
        val backup = File(databaseFile.parent, "${databaseFile.name}.pre-encrypt")
        if (backup.exists()) backup.delete()

        val sourceMoved = databaseFile.renameTo(backup)
        val encryptedMoved = sourceMoved && encryptedTemp.renameTo(databaseFile)
        if (!sourceMoved || !encryptedMoved) {
            // Restore the original when the canonical path is empty.
            if (!databaseFile.exists() && backup.exists()) {
                backup.renameTo(databaseFile)
            }
            return false
        }
        backup.delete()
        return true
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
}
