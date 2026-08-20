package com.aus.notelikeus.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** True when [table] currently has a column named [column]. */
private fun SQLiteConnection.hasColumn(table: String, column: String): Boolean =
    prepare("SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?").let { statement ->
        try {
            statement.bindText(1, table)
            statement.bindText(2, column)
            statement.step() && statement.getLong(0) > 0L
        } finally {
            statement.close()
        }
    }

/**
 * Recreates `note_label_cross_ref` with its foreign keys and copies over only the rows whose note
 * and label still exist, dropping orphans SQLite accepted while FKs were unenforced.
 *
 * Applied by both MIGRATION_4_5 and MIGRATION_7_8: the second pass exists because the first ran
 * before the FKs were declared on every path, so it re-applies the same rebuild defensively.
 */
private fun SQLiteConnection.rebuildNoteLabelCrossRef() {
    execSQL("DROP TABLE IF EXISTS note_label_cross_ref_new")
    execSQL(
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
    execSQL(
        """
        INSERT INTO note_label_cross_ref_new (noteId, labelId)
        SELECT noteId, labelId FROM note_label_cross_ref
        WHERE noteId IN (SELECT id FROM notes) AND labelId IN (SELECT id FROM labels)
        """.trimIndent()
    )
    execSQL("DROP TABLE note_label_cross_ref")
    execSQL("ALTER TABLE note_label_cross_ref_new RENAME TO note_label_cross_ref")
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_note_label_cross_ref_labelId ON note_label_cross_ref(labelId)"
    )
}

object DatabaseMigrations {

    val MIGRATION_1_2 = object : RoomMigration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS labels (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL
                )
                """.trimIndent()
            )
            connection.execSQL(
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
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_note_label_cross_ref_labelId ON note_label_cross_ref(labelId)"
            )
        }
    }

    val MIGRATION_2_3 = object : RoomMigration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE notes ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0"
            )
            connection.execSQL(
                "ALTER TABLE notes ADD COLUMN reminderTimestamp INTEGER"
            )
        }
    }

    val MIGRATION_3_4 = object : RoomMigration(3, 4) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
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
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_checklist_items_noteId ON checklist_items(noteId)"
            )
            connection.execSQL(
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
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_attachments_noteId ON attachments(noteId)"
            )
        }
    }

    val MIGRATION_4_5 = object : RoomMigration(4, 5) {
        override fun migrate(connection: SQLiteConnection) {
            connection.rebuildNoteLabelCrossRef()
        }
    }

    val MIGRATION_5_6 = object : RoomMigration(5, 6) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE notes ADD COLUMN serverUpdatedAt INTEGER")
        }
    }

    val MIGRATION_6_7 = object : RoomMigration(6, 7) {
        override fun migrate(connection: SQLiteConnection) {
            // Only drop the column when it's actually there, so the migration is idempotent
            // without a blanket try/catch. Swallowing the failure instead would let a genuinely
            // failed DROP report success, leaving a schema that no longer matches Room's expected
            // identity hash — which surfaces later as an unrecoverable crash on open.
            if (connection.hasColumn(table = "notes", column = "cloudId")) {
                connection.execSQL("ALTER TABLE notes DROP COLUMN cloudId")
            }
        }
    }

    val MIGRATION_7_8 = object : RoomMigration(7, 8) {
        override fun migrate(connection: SQLiteConnection) {
            // Defensively re-apply FKs if possible. For KMP, we might need a better check.
            connection.rebuildNoteLabelCrossRef()
        }
    }

    val MIGRATION_8_9 = object : RoomMigration(8, 9) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DROP TABLE IF EXISTS attachments")
        }
    }

    val ALL = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9
    )
}
