package com.aus.notelikeus.di

import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.local.getDatabaseBuilder
import com.aus.notelikeus.data.local.createDataStore
import com.aus.notelikeus.data.local.SETTINGS_DATASTORE_FILENAME
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.platform.DesktopReminderManager
import com.aus.notelikeus.platform.DesktopWidgetManager
import org.koin.dsl.module
import java.io.File

actual val platformModule = module {
    single {
        createDataStore {
            val userHome = System.getProperty("user.home")
            File(userHome, ".notelikeus/$SETTINGS_DATASTORE_FILENAME").absolutePath
        }
    }

    single<NotelikeusDatabase> {
        getDatabaseBuilder().build()
    }
    
    single { get<NotelikeusDatabase>().noteDao }
    single { get<NotelikeusDatabase>().labelDao }
    
    single<ReminderManager> { DesktopReminderManager() }
    single<PlatformWidgetManager> { DesktopWidgetManager() }
}
