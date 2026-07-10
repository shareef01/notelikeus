package com.aus.notelikeus.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
  private val prefs by lazy {
    val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    EncryptedSharedPreferences.create(
        PREFS_NAME,
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
  }

  fun getPassphrase(): ByteArray {
    val existing = prefs.getString(PASSPHRASE_KEY, null)
    if (existing != null) {
      return existing.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    val generated = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    val encoded = generated.joinToString(separator = "") { byte -> "%02x".format(byte) }
    prefs.edit().putString(PASSPHRASE_KEY, encoded).apply()
    return generated
  }

  companion object {
    private const val PREFS_NAME = "db_security_prefs"
    private const val PASSPHRASE_KEY = "db_passphrase"
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
                quarantineCorruptDatabase(context, databaseName)
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
        deleteDatabaseFiles(context, databaseName)
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

  private fun quarantineCorruptDatabase(context: Context, databaseName: String) {
    val databaseFile = context.getDatabasePath(databaseName)
    if (!databaseFile.exists()) return
    val quarantined = File(
        databaseFile.parent,
        "$databaseName.corrupt.${System.currentTimeMillis()}"
    )
    databaseFile.renameTo(quarantined)
    File(databaseFile.parent, "$databaseName-shm").delete()
    File(databaseFile.parent, "$databaseName-wal").delete()
  }

  private fun deleteDatabaseFiles(context: Context, databaseName: String) {
    val databaseFile = context.getDatabasePath(databaseName)
    databaseFile.delete()
    File(databaseFile.parent, "$databaseName-shm").delete()
    File(databaseFile.parent, "$databaseName-wal").delete()
  }

  private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte)
  }
}
