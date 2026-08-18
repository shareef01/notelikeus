package com.aus.notelikeus.data.local

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

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
        } catch (error: Exception) {
            Log.i(TAG, "Database does not open as plaintext", error)
            false
        }
        if (!isPlaintext) {
            // Can't open it plaintext, and not with our current passphrase either. The app sets
            // android:allowBackup="false", so this is not a backup-restore artefact; it is reachable
            // whenever the database file outlives the Keystore key that encrypted it — a
            // device-to-device transfer, or a key invalidated on this device. An otherwise-valid
            // encrypted database then looks unopenable. Never delete outright — quarantine (rename)
            // it so Room can create a fresh DB while the original bytes stay recoverable on disk.
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
        // -journal belongs in this list for the same reason the success path deletes it. Left
        // behind, it is a hot journal for a database that is no longer at that path, sitting beside
        // the fresh one Room is about to create under the same name -- which SQLite may try to roll
        // back into a file it never belonged to.
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
