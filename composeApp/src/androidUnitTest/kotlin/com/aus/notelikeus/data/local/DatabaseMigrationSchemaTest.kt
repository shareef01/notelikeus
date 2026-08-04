package com.aus.notelikeus.data.local

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
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
}
