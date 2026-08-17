package com.aus.notelikeus.data.local

import androidx.room.RoomDatabase
import androidx.room.useWriterConnection

expect fun getDatabaseBuilder(): RoomDatabase.Builder<NotelikeusDatabase>

/**
 * Forces the database open by checking out the writer connection, which runs the open helper
 * (SQLCipher on Android) and any pending Room migrations. Exists so platform shells can warm
 * the database up on a background thread without needing Room on their own classpath.
 */
suspend fun NotelikeusDatabase.warmUp() {
    useWriterConnection<Unit> { }
}
