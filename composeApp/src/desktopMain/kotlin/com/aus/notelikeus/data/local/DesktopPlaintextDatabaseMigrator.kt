package com.aus.notelikeus.data.local

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aus.notelikeus.util.AppLog
import java.io.File
import java.sql.Connection

/**
 * One-way plaintext → SQLCipher migration for the Desktop Room database.
 *
 * Willena / SQLite3 Multiple Ciphers encrypts an existing plaintext file with
 * `PRAGMA cipher` + `PRAGMA rekey` (there is no `sqlcipher_export` in this build).
 *
 * Plaintext probe and WAL checkpoint use [BundledSQLiteDriver] so a database created by the
 * previous Bundled desktop path is recognized even when JDBC's encrypted probe path is tried
 * first. The copy is then rekeyed through [JdbcSQLiteDriver].
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
            // SQLCipher/MC cannot rekey while journal_mode is WAL.
            leaveWalJournalMode(databaseFile)
            databaseFile.copyTo(encryptedTemp, overwrite = true)
            for (suffix in listOf("-journal", "-shm", "-wal")) {
                File(parent, encryptedTemp.name + suffix).delete()
            }
            JdbcSQLiteDriver.openJdbcConnection(encryptedTemp.absolutePath, passphrase = null)
                .use { connection ->
                    applySqlCipherV4(connection)
                    val key = JdbcSQLiteDriver.passphraseAsHexKey(passphrase).replace("'", "''")
                    connection.createStatement().use { stmt ->
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

    private fun leaveWalJournalMode(databaseFile: File) {
        BundledSQLiteDriver().open(databaseFile.absolutePath).use { connection ->
            connection.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { it.step() }
            connection.prepare("PRAGMA journal_mode=DELETE").use { it.step() }
        }
        for (suffix in listOf("-journal", "-shm", "-wal")) {
            File(databaseFile.parentFile, databaseFile.name + suffix).delete()
        }
    }

    private fun applySqlCipherV4(connection: Connection) {
        connection.createStatement().use { stmt ->
            stmt.execute("PRAGMA cipher = 'sqlcipher'")
            stmt.execute("PRAGMA legacy = 4")
            stmt.execute("PRAGMA legacy_page_size = 4096")
            stmt.execute("PRAGMA kdf_iter = 256000")
        }
    }

    private fun canOpenEncrypted(databaseFile: File, passphrase: ByteArray): Boolean =
        tryOpenJdbc(databaseFile) { JdbcSQLiteDriver.openJdbcConnection(it, passphrase) }

    private fun canOpenPlaintext(databaseFile: File): Boolean =
        try {
            BundledSQLiteDriver().open(databaseFile.absolutePath).use { connection ->
                connection.prepare("SELECT count(*) FROM sqlite_master").use { stmt ->
                    stmt.step()
                }
            }
            true
        } catch (error: Exception) {
            AppLog.warn(TAG, "Database probe failed for ${databaseFile.name}", error)
            false
        }

    private fun tryOpenJdbc(databaseFile: File, open: (String) -> Connection): Boolean =
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
