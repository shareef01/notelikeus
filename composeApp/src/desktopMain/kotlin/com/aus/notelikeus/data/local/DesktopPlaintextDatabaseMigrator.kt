package com.aus.notelikeus.data.local

import com.aus.notelikeus.util.AppLog
import java.io.File
import java.sql.Connection

/**
 * One-way plaintext → SQLCipher migration for the Desktop Room database.
 *
 * Mirrors the Android migrator's safety rules (quarantine, never delete; rename-swap), but uses
 * Willena's `PRAGMA rekey` to encrypt an existing plaintext file in place into a temp path copy
 * is unnecessary when rekey rewrites the open file — we still copy-then-swap so a failed rekey
 * cannot corrupt the only copy: open a byte-copy as the working file, rekey it, then swap.
 */
object DesktopPlaintextDatabaseMigrator {
    private const val TAG = "DesktopDbMigrate"

    fun migrateToEncryptedIfNeeded(databaseFile: File, passphrase: ByteArray) {
        val parent = databaseFile.parentFile ?: return
        val encryptedTemp = File(parent, "${databaseFile.name}-encrypted-temp")
        val backup = File(parent, "${databaseFile.name}.pre-encrypt")

        if (!databaseFile.exists()) {
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

        if (encryptedTemp.exists()) encryptedTemp.delete()

        if (canOpenEncrypted(databaseFile, passphrase)) return

        if (!canOpenPlaintext(databaseFile)) {
            quarantineDatabaseFiles(databaseFile)
            return
        }

        try {
            databaseFile.copyTo(encryptedTemp, overwrite = true)
            // Drop WAL/SHM companions for the temp name so rekey sees a consistent main file.
            for (suffix in listOf("-journal", "-shm", "-wal")) {
                File(parent, encryptedTemp.name + suffix).delete()
            }
            JdbcSQLiteDriver.openJdbcConnection(encryptedTemp.absolutePath, passphrase = null)
                .use { connection ->
                    applySqlCipherV4(connection)
                    val key = JdbcSQLiteDriver.passphraseAsHexKey(passphrase).replace("'", "''")
                    connection.createStatement().use { stmt ->
                        // Encrypt the open plaintext database under the SQLCipher v4 key.
                        stmt.execute("PRAGMA rekey = '$key'")
                    }
                }
        } catch (error: Exception) {
            encryptedTemp.delete()
            AppLog.warn(TAG, "Failed to migrate desktop database to encrypted", error)
            quarantineDatabaseFiles(databaseFile)
            return
        }

        if (!canOpenEncrypted(encryptedTemp, passphrase)) {
            encryptedTemp.delete()
            AppLog.warn(TAG, "Rekeyed temp database did not open with passphrase")
            quarantineDatabaseFiles(databaseFile)
            return
        }

        if (!swapEncryptedIntoPlace(databaseFile, encryptedTemp)) {
            quarantineDatabaseFiles(databaseFile)
            return
        }
        for (suffix in listOf("-journal", "-shm", "-wal")) {
            File(parent, databaseFile.name + suffix).delete()
        }
    }

    internal fun swapEncryptedIntoPlace(databaseFile: File, encryptedTemp: File): Boolean {
        val backup = File(databaseFile.parent, "${databaseFile.name}.pre-encrypt")
        if (backup.exists()) backup.delete()

        val sourceMoved = databaseFile.renameTo(backup)
        val encryptedMoved = sourceMoved && encryptedTemp.renameTo(databaseFile)
        if (!sourceMoved || !encryptedMoved) {
            if (!databaseFile.exists() && backup.exists()) {
                backup.renameTo(databaseFile)
            }
            return false
        }
        backup.delete()
        return true
    }

    /** Selects SQLCipher v4 before the first key/rekey on a plaintext connection. */
    private fun applySqlCipherV4(connection: Connection) {
        connection.createStatement().use { stmt ->
            stmt.execute("PRAGMA cipher = 'sqlcipher'")
            stmt.execute("PRAGMA legacy = 4")
            stmt.execute("PRAGMA legacy_page_size = 4096")
            stmt.execute("PRAGMA kdf_iter = 256000")
        }
    }

    private fun canOpenEncrypted(databaseFile: File, passphrase: ByteArray): Boolean =
        tryOpen(databaseFile) { JdbcSQLiteDriver.openJdbcConnection(it, passphrase) }

    private fun canOpenPlaintext(databaseFile: File): Boolean =
        tryOpen(databaseFile) { JdbcSQLiteDriver.openJdbcConnection(it, passphrase = null) }

    private fun tryOpen(databaseFile: File, open: (String) -> Connection): Boolean =
        try {
            open(databaseFile.absolutePath).use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT count(*) FROM sqlite_master").use { rs ->
                        rs.next()
                    }
                }
            }
            true
        } catch (error: Exception) {
            AppLog.warn(TAG, "Database probe failed for ${databaseFile.name}", error)
            false
        }

    private fun quarantineDatabaseFiles(databaseFile: File) {
        val parent = databaseFile.parentFile ?: return
        val suffix = System.currentTimeMillis()
        var movedAny = false
        for (name in listOf(
            databaseFile.name,
            "${databaseFile.name}-journal",
            "${databaseFile.name}-shm",
            "${databaseFile.name}-wal",
        )) {
            val file = File(parent, name)
            if (file.exists()) {
                if (file.renameTo(File(parent, "$name.quarantined-$suffix"))) movedAny = true
            }
        }
        if (movedAny) {
            AppLog.warn(TAG, "Quarantined unreadable desktop database (suffix=$suffix)")
        }
    }
}
