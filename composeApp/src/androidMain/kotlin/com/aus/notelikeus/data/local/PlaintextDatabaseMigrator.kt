package com.aus.notelikeus.data.local

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

object PlaintextDatabaseMigrator {

    private const val TAG = "PlaintextDatabaseMigrator"

    /**
     * Test seam: runs just before the encrypted export, so a test can make it fail the way a full
     * disk or an I/O error would. Never set in production.
     */
    internal var beforeExportForTests: (() -> Unit)? = null

    /**
     * Encrypts a plaintext database in place, and reports how the database must be opened now.
     *
     * Only a file that opens neither with the passphrase nor as plaintext is quarantined. A readable
     * plaintext database that cannot be encrypted this time is left exactly where it is
     * ([DatabaseEncryptionOutcome.PLAINTEXT_RETAINED]); the next launch tries again (F-10).
     */
    fun migrateToEncryptedIfNeeded(
        context: Context,
        databaseName: String,
        passphrase: ByteArray
    ): DatabaseEncryptionOutcome {
        val databaseFile = context.getDatabasePath(databaseName)
        val encryptedTemp = context.getDatabasePath("$databaseName-encrypted-temp")
        val backup = context.getDatabasePath("$databaseName.pre-encrypt")

        if (!databaseFile.exists()) {
            recoverInterruptedSwap(databaseFile, encryptedTemp, backup, passphrase)?.let { return it }
        }

        // A stale export from an earlier interrupted run is safe to remove now: the source database
        // is intact at this point, so no data is destroyed.
        if (encryptedTemp.exists()) encryptedTemp.delete()

        // Room opens the DB with the raw passphrase bytes via SupportOpenHelperFactory(byte[]),
        // which SQLCipher keys through sqlite3_key() and derives with PBKDF2. If the file already
        // opens with that derivation there is nothing to migrate.
        if (canOpenEncrypted(databaseFile, passphrase)) return DatabaseEncryptionOutcome.ENCRYPTED

        if (!opensAsPlaintext(databaseFile)) {
            // Can't open it plaintext, and not with our current passphrase either. The app sets
            // android:allowBackup="false", so this is not a backup-restore artefact; it is reachable
            // whenever the database file outlives the Keystore key that encrypted it — a
            // device-to-device transfer, or a key invalidated on this device. An otherwise-valid
            // encrypted database then looks unopenable. Never delete outright — quarantine (rename)
            // it so Room can create a fresh DB while the original bytes stay recoverable on disk.
            quarantineDatabaseFiles(context, databaseName)
            return DatabaseEncryptionOutcome.QUARANTINED
        }

        // Every failure from here on leaves a readable plaintext database holding every note, so it
        // stays in place rather than being quarantined: the cause (a full disk, an I/O error) may
        // well be gone by the next launch (F-10).
        if (!exportEncryptedCopy(databaseFile, encryptedTemp, passphrase)) {
            return DatabaseEncryptionOutcome.PLAINTEXT_RETAINED
        }

        // Proven before the swap, while the plaintext original is still the canonical file: the swap
        // deletes the original, so a copy that turned out not to open afterwards left nothing to fall
        // back on.
        if (!canOpenEncrypted(encryptedTemp, passphrase)) {
            encryptedTemp.delete()
            Log.e(TAG, "Encrypted copy did not open; keeping the plaintext database for now")
            return DatabaseEncryptionOutcome.PLAINTEXT_RETAINED
        }

        if (!swapEncryptedIntoPlace(databaseFile, encryptedTemp)) {
            // The swap puts the original back when it cannot finish.
            encryptedTemp.delete()
            Log.e(TAG, "Could not swap the encrypted copy in; keeping the plaintext database for now")
            return DatabaseEncryptionOutcome.PLAINTEXT_RETAINED
        }
        if (!canOpenEncrypted(databaseFile, passphrase)) {
            // The swapped-in file must open with our key; if it does not, do not trust it.
            quarantineDatabaseFiles(context, databaseName)
            return DatabaseEncryptionOutcome.QUARANTINED
        }
        // Auxiliary files belonged to the old plaintext database; the fresh encrypted copy starts
        // without them.
        for (name in listOf("$databaseName-journal", "$databaseName-shm", "$databaseName-wal")) {
            context.getDatabasePath(name).delete()
        }
        return DatabaseEncryptionOutcome.ENCRYPTED
    }

    /**
     * A previous run may have been interrupted mid-swap (original moved aside but the encrypted copy
     * not yet moved into place). Prefers the encrypted copy when it opens with our key; otherwise
     * puts the original back and returns null, so the caller migrates it like any plaintext
     * database — returning early here handed a plaintext file to an encrypted open.
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

    private fun opensAsPlaintext(databaseFile: File): Boolean =
        try {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                "",
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null
            ).close()
            true
        } catch (error: Exception) {
            Log.i(TAG, "Database does not open as plaintext", error)
            false
        }

    /**
     * Writes an encrypted copy of the plaintext [databaseFile] to [encryptedTemp]. False, with the
     * partial copy removed and the source untouched, when the export fails.
     */
    private fun exportEncryptedCopy(
        databaseFile: File,
        encryptedTemp: File,
        passphrase: ByteArray,
    ): Boolean =
        try {
            beforeExportForTests?.invoke()
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
            true
        } catch (error: Exception) {
            encryptedTemp.delete()
            Log.e(TAG, "Could not encrypt the database; keeping it as plaintext for now", error)
            false
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
        } catch (error: Exception) {
            Log.i(TAG, "Database does not open with the current passphrase: ${databaseFile.name}", error)
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
        var movedAny = false
        // -journal is here for symmetry with the success path, which deletes it -- so a -journal
        // is something this code already expects can exist, and leaving one beside the fresh
        // database Room creates under the same name would be a hot journal for a file that moved.
        //
        // Defensive rather than demonstrated: by the time quarantine runs, the two open attempts
        // above have already been made against the database, and SQLite discards the journal during
        // them. An attempt to cover this with a test could not get a journal to survive that far,
        // so this is symmetry and cheap insurance, not a reproduced failure.
        for (
            name in listOf(
                databaseName,
                "$databaseName-journal",
                "$databaseName-shm",
                "$databaseName-wal"
            )
        ) {
            val file = context.getDatabasePath(name)
            if (file.exists()) {
                if (file.renameTo(File(file.parent, "$name.quarantined-$suffix"))) movedAny = true
            }
        }
        // Only if something actually moved: the app is about to start with an empty database, and
        // the user is owed an explanation for that.
        if (movedAny) DatabaseRecoveryNotice.record(context, suffix)
    }
}
