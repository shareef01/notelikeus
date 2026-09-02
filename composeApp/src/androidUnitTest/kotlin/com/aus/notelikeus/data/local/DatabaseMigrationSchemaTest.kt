package com.aus.notelikeus.data.local

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression test for the bug MIGRATION_4_5 fixed: MIGRATION_1_2's raw SQL declared foreign keys
 * on note_label_cross_ref that the Room entity never carried, so upgraders and fresh v4 installs
 * ended up with different on-disk schemas — one Room's own validation would reject. This exercises
 * the actual migration SQL against a hand-built "clean v4" table (no FKs, matching what a fresh
 * pre-fix v4 install had) rather than relying on DatabaseMigrationsTest's version-number-only checks,
 * which would not have caught the original mismatch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DatabaseMigrationSchemaTest {

    @Test
    fun migration4to5_addsForeignKeysAndDropsOrphanedCrossRefs() {
        val context = RuntimeEnvironment.getApplication()
        val dbName = "migration-schema-test-${System.nanoTime()}.db"

        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE notes (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            title TEXT NOT NULL,
                            content TEXT NOT NULL,
                            timestamp INTEGER NOT NULL,
                            color INTEGER NOT NULL,
                            isPinned INTEGER NOT NULL,
                            isArchived INTEGER NOT NULL,
                            isTrashed INTEGER NOT NULL,
                            position INTEGER NOT NULL,
                            isLocked INTEGER NOT NULL,
                            reminderTimestamp INTEGER
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        "CREATE TABLE labels (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)"
                    )
                    // Simulates a "clean" pre-fix v4 install: no foreign keys, unlike what
                    // MIGRATION_1_2's raw SQL actually created for upgraders.
                    db.execSQL(
                        """
                        CREATE TABLE note_label_cross_ref (
                            noteId INTEGER NOT NULL,
                            labelId INTEGER NOT NULL,
                            PRIMARY KEY(noteId, labelId)
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val db = FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
        try {
            db.execSQL(
                "INSERT INTO notes (id, title, content, timestamp, color, isPinned, isArchived, isTrashed, position, isLocked) " +
                    "VALUES (1, 't', 'c', 0, 0, 0, 0, 0, 0, 0)"
            )
            db.execSQL("INSERT INTO labels (id, name) VALUES (1, 'work')")
            db.execSQL("INSERT INTO note_label_cross_ref (noteId, labelId) VALUES (1, 1)")
            // Orphan: references a note that doesn't exist — the kind of row the missing
            // cascade let accumulate before this migration, which should be dropped here.
            db.execSQL("INSERT INTO note_label_cross_ref (noteId, labelId) VALUES (999, 1)")

            DatabaseMigrations.MIGRATION_4_5.migrate(db)

            val foreignKeyCount = db.query("PRAGMA foreign_key_list(note_label_cross_ref)").use { cursor ->
                cursor.count
            }
            assertEquals(2, foreignKeyCount)

            db.query("SELECT noteId, labelId FROM note_label_cross_ref").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals(1L, cursor.getLong(0))
                assertEquals(1L, cursor.getLong(1))
            }
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }
    /**
     * MIGRATION_9_10 against a populated v9 table.
     *
     * The value the column will hold cannot be produced in SQL -- folding diacritics needs the
     * Kotlin table in SearchText.kt -- so this only has to prove three things: the column arrives,
     * every existing row survives it, and each of them reads back null rather than empty string.
     * That last one carries the safety property: null means "not yet indexed" and makes the
     * matcher fold on the spot, where an empty string would mean "matches nothing" and would make
     * every un-backfilled note silently unfindable.
     *
     * Re-running it must also be a no-op, because a migration that half-ran and is retried is the
     * normal case, not the exotic one.
     */
    @Test
    fun migration9to10_addsSearchTextAndPreservesRows() {
        val context = RuntimeEnvironment.getApplication()
        val dbName = "migration-9-10-test-${System.nanoTime()}.db"

        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE notes (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            title TEXT NOT NULL,
                            content TEXT NOT NULL,
                            timestamp INTEGER NOT NULL,
                            color INTEGER NOT NULL,
                            isPinned INTEGER NOT NULL,
                            isArchived INTEGER NOT NULL,
                            isTrashed INTEGER NOT NULL,
                            position INTEGER NOT NULL,
                            isLocked INTEGER NOT NULL,
                            reminderTimestamp INTEGER,
                            serverUpdatedAt INTEGER
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        "INSERT INTO notes VALUES (1,'Café','milk',100,0,0,0,0,0,0,NULL,NULL)"
                    )
                    db.execSQL(
                        "INSERT INTO notes VALUES (2,'Second','body',200,0,1,0,0,1,0,500,900)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        try {
            val db = helper.writableDatabase
            DatabaseMigrations.MIGRATION_9_10.migrate(db)
            DatabaseMigrations.MIGRATION_9_10.migrate(db)

            db.query("SELECT COUNT(*) FROM notes").use { cursor ->
                cursor.moveToFirst()
                assertEquals(2, cursor.getInt(0))
            }
            db.query("SELECT id, title, searchText FROM notes ORDER BY id").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals("Café", cursor.getString(1))
                assertEquals(true, cursor.isNull(2))
                cursor.moveToNext()
                assertEquals(2, cursor.getInt(0))
                assertEquals(true, cursor.isNull(2))
            }
            // Idempotency: the guard in the migration is what makes a retry safe. Adding the
            // column twice is an error SQLite raises rather than ignores, so a second migrate()
            // must no-op rather than ALTER again.
            db.query("SELECT COUNT(*) FROM pragma_table_info('notes') WHERE name = 'searchText'")
                .use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(1, cursor.getInt(0))
                }
        } finally {
            helper.close()
            context.deleteDatabase(dbName)
        }
    }

    /**
     * A library that started at version 1, with rows in every table the chain creates, must still
     * be there at version 11. DatabaseMigrationsTest only asserts start/end version numbers;
     * MIGRATION_9_10's comment is that ALTER ADD COLUMN must not rebuild `notes` or the CASCADE
     * on checklists and labels would fire. This is the witness for that, and for PACK-01's
     * "green CI can miss a populated upgrade" gap.
     */
    @Test
    fun populatedV1survivesUpgradeToCurrent() {
        val context = RuntimeEnvironment.getApplication()
        val dbName = "migration-v1-current-${System.nanoTime()}.db"

        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE notes (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            title TEXT NOT NULL,
                            content TEXT NOT NULL,
                            timestamp INTEGER NOT NULL,
                            color INTEGER NOT NULL,
                            isPinned INTEGER NOT NULL,
                            isArchived INTEGER NOT NULL,
                            isTrashed INTEGER NOT NULL,
                            position INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        "INSERT INTO notes (id, title, content, timestamp, color, isPinned, isArchived, isTrashed, position) " +
                            "VALUES (1, 'Café', 'milk', 100, 0, 0, 0, 0, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO notes (id, title, content, timestamp, color, isPinned, isArchived, isTrashed, position) " +
                            "VALUES (2, 'Pinned', 'body', 200, 0, 1, 0, 0, 1)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        try {
            val db = helper.writableDatabase
            for (migration in DatabaseMigrations.ALL) {
                migration.migrate(db)
                when (migration.endVersion) {
                    2 -> {
                        db.execSQL("INSERT INTO labels (id, name) VALUES (1, 'work')")
                        db.execSQL("INSERT INTO note_label_cross_ref (noteId, labelId) VALUES (1, 1)")
                    }
                    4 -> {
                        db.execSQL(
                            "INSERT INTO checklist_items (id, noteId, text, isChecked, position) " +
                                "VALUES (1, 1, 'buy milk', 0, 0)"
                        )
                        db.execSQL(
                            "INSERT INTO attachments (id, noteId, uri, type) " +
                                "VALUES (1, 1, 'file:///unused', 'image')"
                        )
                    }
                }
            }

            db.query("SELECT COUNT(*) FROM notes").use { cursor ->
                cursor.moveToFirst()
                assertEquals(2, cursor.getInt(0))
            }
            db.query("SELECT id, title, isPinned, isLocked, searchText FROM notes ORDER BY id").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals("Café", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(0, cursor.getInt(3))
                assertTrue(cursor.isNull(4))
                cursor.moveToNext()
                assertEquals(2, cursor.getInt(0))
                assertEquals("Pinned", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
                assertTrue(cursor.isNull(4))
            }

            assertEquals(
                listOf(
                    "id", "title", "content", "timestamp", "color",
                    "isPinned", "isArchived", "isTrashed", "position",
                    "isLocked", "reminderTimestamp", "serverUpdatedAt", "searchText",
                    "attachmentsJson"
                ),
                columnNames(db, "notes")
            )
            assertFalse("cloudId must not survive MIGRATION_6_7", columnNames(db, "notes").contains("cloudId"))
            assertFalse("attachments must not survive MIGRATION_8_9", tableExists(db, "attachments"))

            db.query("SELECT noteId, labelId FROM note_label_cross_ref").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals(1L, cursor.getLong(0))
                assertEquals(1L, cursor.getLong(1))
            }
            db.query("SELECT noteId, text FROM checklist_items").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals(1L, cursor.getLong(0))
                assertEquals("buy milk", cursor.getString(1))
            }
        } finally {
            helper.close()
            context.deleteDatabase(dbName)
        }
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): List<String> {
        val names = mutableListOf<String>()
        db.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                names.add(cursor.getString(nameIndex))
            }
        }
        return names
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean {
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'")
            .use { cursor ->
                cursor.moveToFirst()
                return cursor.getInt(0) > 0
            }
    }

}
