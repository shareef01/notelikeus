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

    /**
     * Test seam: runs just before the copy is rekeyed, so a test can make encryption fail at the
     * point a real I/O or SQLCipher error would. Never set in production.
     */
    internal var beforeRekeyForTests: (() -> Unit)? = null

    /**
     * Encrypts a plaintext database in place, and reports how the database must be opened now.
     *
     * Only a file that opens neither with the passphrase nor as plaintext is quarantined. A readable
     * plaintext database that cannot be encrypted this time is left exactly where it is
     * ([DatabaseEncryptionOutcome.PLAINTEXT_RETAINED]); the next launch tries again (F-10).
     */
    fun migrateToEncryptedIfNeeded(databaseFile: File, passphrase: ByteArray): DatabaseEncryptionOutcome {
        val parent = databaseFile.parentFile ?: return DatabaseEncryptionOutcome.ENCRYPTED
        val encryptedTemp = File(parent, "${databaseFile.name}-encrypted-temp")
        val backup = File(parent, "${databaseFile.name}.pre-encrypt")

        if (!databaseFile.exists()) {
            recoverInterruptedSwap(databaseFile, encryptedTemp, backup, passphrase)?.let { return it }
        }

        if (encryptedTemp.exists()) encryptedTemp.delete()

        if (canOpenEncrypted(databaseFile, passphrase)) return DatabaseEncryptionOutcome.ENCRYPTED

        if (!canOpenPlaintext(databaseFile)) {
            quarantineDatabaseFiles(databaseFile)
            return DatabaseEncryptionOutcome.QUARANTINED
        }

        // Every failure from here on leaves a readable plaintext database holding every note, so it
        // stays in place rather than being quarantined, and the next launch tries again (F-10).
        if (!rekeyCopy(databaseFile, encryptedTemp, passphrase)) {
            return DatabaseEncryptionOutcome.PLAINTEXT_RETAINED
        }

        if (!canOpenEncrypted(encryptedTemp, passphrase)) {
            encryptedTemp.delete()
            AppLog.warn(TAG, "Rekeyed copy did not open; keeping the plaintext database for now")
            return DatabaseEncryptionOutcome.PLAINTEXT_RETAINED
        }

        if (!swapEncryptedIntoPlace(databaseFile, encryptedTemp)) {
            // The swap restores the original when it cannot finish, so the plaintext file is back.
            encryptedTemp.delete()
            AppLog.warn(TAG, "Could not swap the encrypted copy in; keeping the plaintext database for now")
            return DatabaseEncryptionOutcome.PLAINTEXT_RETAINED
        }
        for (suffix in listOf("-journal", "-shm", "-wal")) {
            File(parent, databaseFile.name + suffix).delete()
        }
        return DatabaseEncryptionOutcome.ENCRYPTED
    }

    /**
     * A previous run was interrupted mid-swap. Prefers the encrypted copy when it opens; otherwise
     * puts the original back and returns null, so the caller migrates it like any plaintext database
     * — returning early here handed a plaintext file to an encrypted open.
     */
    private fun recoverInterruptedSwap(
        databaseFile: File,
        encryptedTemp: File,
        backup: File,
        passphrase: ByteArray,
    ): DatabaseEncryptionOutcome? {
        if (encryptedTemp.exists() && canOpenEncrypted(encryptedTemp, passphrase) &&
            encryptedTemp.renameTo(databaseFile)
        ) {
            backup.delete()
            return DatabaseEncryptionOutcome.ENCRYPTED
        }
        // Nothing to recover: no database yet, so Room creates a fresh, encrypted one.
        if (!(backup.exists() && backup.renameTo(databaseFile))) return DatabaseEncryptionOutcome.ENCRYPTED
        return null
    }

    /**
     * Copies the plaintext [databaseFile] to [encryptedTemp] and rekeys the copy. False, with the
     * partial copy removed and the source untouched, when that fails.
     */
    private fun rekeyCopy(databaseFile: File, encryptedTemp: File, passphrase: ByteArray): Boolean =
        try {
            // SQLCipher/MC cannot rekey while journal_mode is WAL.
            leaveWalJournalMode(databaseFile)
            databaseFile.copyTo(encryptedTemp, overwrite = true)
            for (suffix in listOf("-journal", "-shm", "-wal")) {
                File(encryptedTemp.parentFile, encryptedTemp.name + suffix).delete()
            }
            JdbcSQLiteDriver.openJdbcConnection(encryptedTemp.absolutePath, passphrase = null)
                .use { connection ->
                    applySqlCipherV4(connection)
                    beforeRekeyForTests?.invoke()
                    val key = JdbcSQLiteDriver.passphraseAsHexKey(passphrase).replace("'", "''")
                    connection.createStatement().use { stmt ->
                        stmt.execute("PRAGMA rekey = '$key'")
                    }
                }
            true
        } catch (error: Exception) {
            encryptedTemp.delete()
            AppLog.warn(TAG, "Could not encrypt the desktop database; keeping it as plaintext for now", error)
            false
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
