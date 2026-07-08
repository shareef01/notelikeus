package com.aus.notelikeus.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
  private val prefs by lazy {
    val masterKey = androidx.security.crypto.MasterKey.Builder(context)
        .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
        .build()

    androidx.security.crypto.EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
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

    SQLiteDatabase.loadLibs(context)

    if (isEncrypted(databaseFile, passphrase)) return

    val encryptedTemp = context.getDatabasePath("$databaseName-encrypted-temp")
    if (encryptedTemp.exists()) encryptedTemp.delete()

    val plainDb = SQLiteDatabase.openDatabase(
        databaseFile.absolutePath,
        "",
        null,
        SQLiteDatabase.OPEN_READWRITE
    )
    val escapedPath = encryptedTemp.absolutePath.replace("'", "''")
    plainDb.execSQL(
        "ATTACH DATABASE '$escapedPath' AS encrypted KEY \"x'${passphrase.toHex()}'\""
    )
    plainDb.rawExecSQL("SELECT sqlcipher_export('encrypted')")
    plainDb.execSQL("DETACH DATABASE encrypted")
    plainDb.close()

    databaseFile.delete()
    encryptedTemp.renameTo(databaseFile)
  }

  private fun isEncrypted(databaseFile: File, passphrase: ByteArray): Boolean {
    return try {
      SQLiteDatabase.openDatabase(
          databaseFile.absolutePath,
          passphrase,
          null,
          SQLiteDatabase.OPEN_READONLY
      ).close()
      true
    } catch (_: Exception) {
      false
    }
  }

  private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte)
  }
}
