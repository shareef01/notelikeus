package com.aus.notelikeus.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * A KMP-friendly base class for migrations that handles the Android-specific
 * SupportSQLiteDatabase override by delegating to the common SQLiteConnection one.
 */
expect abstract class RoomMigration(startVersion: Int, endVersion: Int) : Migration {
    abstract override fun migrate(connection: SQLiteConnection)
}
