package com.aus.notelikeus.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device coverage for the paths that decide whether a user's notes survive.
 *
 * These cannot run off-device: [PlaintextDatabaseMigrator] opens real SQLCipher databases, and
 * SQLCipher ships per-ABI native libraries with no JVM build, so Robolectric cannot reach this
 * code. That is why the quarantine path was previously verified only by reading it.
 *
 * The behaviour under test is deliberately conservative — a database that cannot be opened is
 * *renamed aside*, never deleted, and a marker is left so the app can tell the user their notes
 * still exist. Getting this wrong destroys data silently, which is precisely the failure the
 * audit found unwitnessed.
 */
@RunWith(AndroidJUnit4::class)
class PlaintextDatabaseMigratorTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "migrator_test_db"

    companion object {
        init {
            // In the app this happens in NotelikeusApp.onCreate, before DI builds the database.
            // This module's test APK has no such Application, so without it every SQLCipher call
            // here dies with UnsatisfiedLinkError — which, being an Error rather than an
            // Exception, is not caught by the migrator's own `catch (_: Exception)` guards.
            System.loadLibrary("sqlcipher")
        }
    }

    private fun databaseFile(name: String = dbName) = context.getDatabasePath(name)

    private fun cleanUp() {
        val dir = databaseFile().parentFile ?: return
        dir.listFiles()
            ?.filter { it.name.startsWith(dbName) }
            ?.forEach { it.delete() }
        DatabaseRecoveryNotice.consume(context)
    }

    @Before fun setUp() = cleanUp()

    @After fun tearDown() = cleanUp()

    /** Writes a SQLCipher database encrypted with [passphrase], containing one row. */
    private fun createEncryptedDatabase(file: File, passphrase: ByteArray) {
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openDatabase(
            file.absolutePath,
            passphrase,
            null,
            SQLiteDatabase.CREATE_IF_NECESSARY,
            null,
            null
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS probe (id INTEGER PRIMARY KEY, value TEXT)")
        db.execSQL("INSERT INTO probe (value) VALUES ('survives')")
        db.close()
    }

    @Test
    fun anUnopenableDatabaseIsQuarantinedRatherThanDeleted() {
        val original = databaseFile()
        // Encrypted with a key we will then "lose" — the device-transfer / keystore-invalidation
        // case, where an otherwise valid database simply cannot be opened any more.
        createEncryptedDatabase(original, "the-old-key".toByteArray())
        val originalBytes = original.readBytes()
        assertTrue(original.exists())

        PlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(
            context,
            dbName,
            "a-completely-different-key".toByteArray()
        )

        // The canonical path is cleared so Room can start fresh...
        assertFalse("the unopenable database was left in place", original.exists())

        // ...but the bytes must still be on disk under a quarantined name.
        val quarantined = original.parentFile!!
            .listFiles { file -> file.name.startsWith("$dbName.quarantined-") }
            ?.toList()
            .orEmpty()
        assertEquals("expected exactly one quarantined copy", 1, quarantined.size)
        assertTrue(
            "the quarantined file is not the original database",
            quarantined.single().readBytes().contentEquals(originalBytes)
        )
    }

    @Test
    fun quarantineTakesTheJournalWithIt() {
        val original = databaseFile()
        createEncryptedDatabase(original, "the-old-key".toByteArray())
        // A hot journal beside the database, as a non-WAL database leaves behind.
        val journal = context.getDatabasePath("$dbName-journal")
        journal.writeBytes(byteArrayOf(1, 2, 3, 4))

        PlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(
            context,
            dbName,
            "a-completely-different-key".toByteArray()
        )

        // Left in place it would be a hot journal for a database that has moved away, sitting next
        // to the fresh one Room creates under the same name.
        assertFalse("the journal was left beside the new database", journal.exists())
        val quarantinedJournal = original.parentFile!!
            .listFiles { file -> file.name.startsWith("$dbName-journal.quarantined-") }
            ?.toList()
            .orEmpty()
        assertEquals("the journal was not quarantined", 1, quarantinedJournal.size)
    }

    @Test
    fun quarantiningLeavesANoticeForTheUser() {
        createEncryptedDatabase(databaseFile(), "the-old-key".toByteArray())
        assertNull("stale notice before the run", DatabaseRecoveryNotice.pending(context))

        PlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(
            context,
            dbName,
            "a-completely-different-key".toByteArray()
        )

        // Without this the app opens to an empty note list and says nothing, which is how the
        // user concludes their notes were deleted when they are recoverable on disk.
        assertNotNull("no recovery notice was recorded", DatabaseRecoveryNotice.pending(context))

        DatabaseRecoveryNotice.consume(context)
        assertNull("notice survived being consumed", DatabaseRecoveryNotice.pending(context))
    }

    @Test
    fun aDatabaseThatOpensWithTheCurrentKeyIsLeftAlone() {
        val passphrase = "the-current-key".toByteArray()
        val original = databaseFile()
        createEncryptedDatabase(original, passphrase)
        val before = original.readBytes()

        PlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(context, dbName, passphrase)

        assertTrue("an openable database was moved", original.exists())
        assertTrue("an openable database was rewritten", original.readBytes().contentEquals(before))
        assertNull(
            "a healthy database should not raise a recovery notice",
            DatabaseRecoveryNotice.pending(context)
        )
    }

    @Test
    fun aPlaintextDatabaseIsMigratedToEncryptedWithItsRowsIntact() {
        val original = databaseFile()
        original.parentFile?.mkdirs()
        // Plaintext: the pre-encryption state this migrator exists to upgrade.
        val plain = SQLiteDatabase.openDatabase(
            original.absolutePath,
            "",
            null,
            SQLiteDatabase.CREATE_IF_NECESSARY,
            null
        )
        plain.execSQL("CREATE TABLE probe (id INTEGER PRIMARY KEY, value TEXT)")
        plain.execSQL("INSERT INTO probe (value) VALUES ('survives')")
        plain.close()

        val passphrase = "the-new-key".toByteArray()
        PlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(context, dbName, passphrase)

        // It must now open with the key Room will use, and still hold the row.
        val reopened = SQLiteDatabase.openDatabase(
            original.absolutePath,
            passphrase,
            null,
            SQLiteDatabase.OPEN_READONLY,
            null,
            null
        )
        reopened.rawQuery("SELECT value FROM probe", null).use { cursor ->
            assertTrue("migrated database lost its rows", cursor.moveToFirst())
            assertEquals("survives", cursor.getString(0))
        }
        reopened.close()

        assertNull(
            "a successful migration should not raise a recovery notice",
            DatabaseRecoveryNotice.pending(context)
        )
    }
}
