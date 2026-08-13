package com.aus.notelikeus.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection

actual abstract class RoomMigration actual constructor(
    startVersion: Int,
    endVersion: Int
) : Migration(startVersion, endVersion) {
    actual abstract override fun migrate(connection: SQLiteConnection)
}
