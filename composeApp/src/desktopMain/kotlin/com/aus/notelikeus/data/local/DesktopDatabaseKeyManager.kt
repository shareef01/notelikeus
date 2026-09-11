package com.aus.notelikeus.data.local

import com.aus.notelikeus.data.attachments.SecureBlobStore
import com.aus.notelikeus.platform.Dpapi
import com.aus.notelikeus.util.AppLog
import java.io.File
import java.security.SecureRandom

/**
 * Holds the future Desktop SQLCipher / sqlite-jdbc-crypt passphrase.
 *
 * Slice 1 of Desktop notes-DB encryption: mint and persist a 32-byte key under DPAPI with
 * dedicated entropy ([Dpapi.databaseKeyEntropy]), separate from the session token and attachment
 * AES key. The Room driver still uses plaintext [androidx.sqlite.driver.bundled.BundledSQLiteDriver]
 * until a later slice lands the encrypted driver and migration.
 *
 * Failure policy matches attachments: a present key file that DPAPI cannot unwrap is preserved
 * (renamed aside), never silently replaced — minting a new key would orphan an encrypted DB.
 * Inject [blobStore] so Linux CI can exercise persistence without Crypt32.
 */
class DesktopDatabaseKeyManager(
    private val keyDir: File = File(System.getProperty("user.home"), ".notelikeus"),
    private val blobStore: SecureBlobStore = DpapiDatabaseKeyBlobStore,
) {
    @Volatile
    private var cached: ByteArray? = null
    private val lock = Any()

    fun getPassphrase(): ByteArray = synchronized(lock) {
        cached?.let { return it.copyOf() }
        val loaded = loadOrCreate()
        cached = loaded
        return loaded.copyOf()
    }

    /** True when a key file exists on disk (encrypted DB expected after migration lands). */
    fun hasPersistedKey(): Boolean = keyFile().exists()

    private fun loadOrCreate(): ByteArray {
        keyDir.mkdirs()
        val file = keyFile()
        if (file.exists()) {
            val plaintext = try {
                blobStore.unprotect(file.readBytes())
            } catch (error: Exception) {
                AppLog.warn(TAG, "Database key file present but DPAPI unwrap failed", error)
                preserveUnreadableKeyFile()
                throw error
            }
            require(plaintext.size == KEY_BYTES) {
                "Database key unwrap produced ${plaintext.size} bytes; expected $KEY_BYTES"
            }
            return plaintext
        }

        val generated = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val sealed = blobStore.protect(generated)
        if (!publishByRename(sealed)) {
            throw IllegalStateException("Could not publish desktop database key file")
        }
        return generated
    }

    private fun preserveUnreadableKeyFile() {
        val file = keyFile()
        if (!file.exists()) return
        val preserved = File(keyDir, "$KEY_FILE_NAME.unrecoverable-${System.currentTimeMillis()}")
        if (!file.renameTo(preserved)) {
            AppLog.warn(TAG, "Failed to preserve unreadable database key file")
        }
    }

    private fun publishByRename(payload: ByteArray): Boolean {
        val tmp = File(keyDir, "$KEY_FILE_NAME.tmp")
        return try {
            tmp.writeBytes(payload)
            if (tmp.renameTo(keyFile())) {
                true
            } else {
                tmp.delete()
                AppLog.warn(TAG, "Could not publish database key by rename")
                false
            }
        } catch (error: Exception) {
            tmp.delete()
            AppLog.warn(TAG, "Failed to write database key file", error)
            false
        }
    }

    private fun keyFile(): File = File(keyDir, KEY_FILE_NAME)

    companion object {
        private const val TAG = "DesktopDbKey"
        const val KEY_FILE_NAME = "notes-db.key"
        private const val KEY_BYTES = 32
    }
}

internal object DpapiDatabaseKeyBlobStore : SecureBlobStore {
    override fun protect(plaintext: ByteArray): ByteArray =
        Dpapi.protect(plaintext, Dpapi.databaseKeyEntropy, "Notelikeus notes database key")

    override fun unprotect(sealed: ByteArray): ByteArray =
        Dpapi.unprotect(sealed, Dpapi.databaseKeyEntropy)
}
