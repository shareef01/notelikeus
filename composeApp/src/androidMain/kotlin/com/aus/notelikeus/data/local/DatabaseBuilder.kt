@file:JvmName("DatabaseBuilderAndroid")
package com.aus.notelikeus.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

import org.koin.core.context.GlobalContext

actual fun getDatabaseBuilder(): RoomDatabase.Builder<NotelikeusDatabase> {
    val context = GlobalContext.get().get<Context>()
    val dbFile = context.getDatabasePath(NotelikeusDatabase.DATABASE_NAME)
    return Room.databaseBuilder<NotelikeusDatabase>(
        context = context,
        name = dbFile.absolutePath
    )
}
