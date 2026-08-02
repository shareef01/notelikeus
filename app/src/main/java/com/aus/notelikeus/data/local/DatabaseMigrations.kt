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

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN serverUpdatedAt INTEGER")
        }
    }

    /**
     * Devices that installed the app before the `cloudId` UUID scheme was dropped in favor of
     * the integer `id` primary key still carry that column in their local `notes` table —
     * removing a field from the Kotlin entity never touches an already-installed SQLite
     * table. Room validates the schema on every launch and rejects any unexpected extra
     * column, so those devices have been crashing on startup after every update since,
     * recoverable only by clearing app data (losing every local note).
     *
     * A plain DROP COLUMN (supported since SQLite 3.35, well within SQLCipher 4.6's bundled
     * version) fixes it without recreating `notes` — which would fire the ON DELETE CASCADE
     * on `checklist_items` and `note_label_cross_ref` and wipe every note's checklist/labels,
     * the exact risk MIGRATION_4_5 above already had to route around for a different table.
     * Conditional because a fresh install, or any device that never had `cloudId`, doesn't
     * have the column — DROP COLUMN on one that doesn't exist throws.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            if (hasColumn(db, "notes", "cloudId")) {
                db.execSQL("ALTER TABLE notes DROP COLUMN cloudId")
            }
        }
    }

    /**
     * A device found in the wild (see MIGRATION_6_7) had `cloudId` still on `notes` *and* was
     * missing the foreign keys MIGRATION_4_5 was already supposed to have added to
     * `note_label_cross_ref` — proof that some real installs took a history this linear
     * migration chain doesn't fully account for (per the "Fix broken main merge... restore
     * the weiter app/web trees, drop incompatible main-only leftovers" commit, `main` and
     * `weiter` carried divergent schemas at points before they were reconciled). Rather than
     * trying to reconstruct exactly which past path a given device took, this defensively
     * re-checks the actual on-disk state and re-applies MIGRATION_4_5's recreate — safe and
     * idempotent, since recreating an already-correct table just copies it into an identical
     * one. Skipped when the foreign keys are already there so the common case stays a no-op.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            if (hasForeignKeys(db, "note_label_cross_ref")) return
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

    /**
     * The attachments feature was removed; this migration drops the now-unused table
     * and its index. The entity, DAO methods, and mapper functions are removed in the
     * same version bump.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS attachments")
        }
    }

    private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
        db.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
        }
        return false
    }

    private fun hasForeignKeys(db: SupportSQLiteDatabase, table: String): Boolean {
        db.query("PRAGMA foreign_key_list($table)").use { cursor ->
            return cursor.moveToFirst()
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
