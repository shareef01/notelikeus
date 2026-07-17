package com.aus.notelikeus.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS labels (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS note_label_cross_ref (
                    noteId INTEGER NOT NULL,
                    labelId INTEGER NOT NULL,
                    PRIMARY KEY(noteId, labelId),
                    FOREIGN KEY(noteId) REFERENCES notes(id) ON DELETE CASCADE,
                    FOREIGN KEY(labelId) REFERENCES labels(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_note_label_cross_ref_labelId ON note_label_cross_ref(labelId)"
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE notes ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE notes ADD COLUMN reminderTimestamp INTEGER"
            )
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS checklist_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    noteId INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    isChecked INTEGER NOT NULL,
                    position INTEGER NOT NULL,
                    FOREIGN KEY(noteId) REFERENCES notes(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_checklist_items_noteId ON checklist_items(noteId)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS attachments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    noteId INTEGER NOT NULL,
                    uri TEXT NOT NULL,
                    type TEXT NOT NULL,
                    FOREIGN KEY(noteId) REFERENCES notes(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_attachments_noteId ON attachments(noteId)"
            )
        }
    }

    /**
     * MIGRATION_1_2's raw SQL declared FOREIGN KEYs on note_label_cross_ref, but the Room
     * entity never carried @ForeignKey annotations — so installs that ran that migration
     * ended up with FK constraints Room's own schema validation didn't expect, crashing on
     * every launch after an app update. This migration unconditionally recreates the table
     * to the schema now declared on NoteLabelCrossRef (with FKs), converging both that path
     * and any "clean" v4 install (which never had FKs) to the same schema. The copy also
     * drops any already-orphaned rows left behind by the missing cascade.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS note_label_cross_ref_new")
            db.execSQL(
                """
                CREATE TABLE note_label_cross_ref_new (
                    noteId INTEGER NOT NULL,
                    labelId INTEGER NOT NULL,
                    PRIMARY KEY(noteId, labelId),
                    FOREIGN KEY(noteId) REFERENCES notes(id) ON DELETE CASCADE,
                    FOREIGN KEY(labelId) REFERENCES labels(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO note_label_cross_ref_new (noteId, labelId)
                SELECT noteId, labelId FROM note_label_cross_ref
                WHERE noteId IN (SELECT id FROM notes) AND labelId IN (SELECT id FROM labels)
                """.trimIndent()
            )
            db.execSQL("DROP TABLE note_label_cross_ref")
            db.execSQL("ALTER TABLE note_label_cross_ref_new RENAME TO note_label_cross_ref")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_note_label_cross_ref_labelId ON note_label_cross_ref(labelId)"
            )
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
