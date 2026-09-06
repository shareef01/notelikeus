package com.aus.notelikeus.data.local

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsDataStorePathTest {

    /**
     * `preferencesDataStore(name)` writes `files/datastore/{name}.preferences_pb`. Passing the
     * full filename as `name` created a second store the widget read while the app wrote
     * `settings.preferences_pb` through Koin's [createDataStore] path — so app lock and theme
     * never reached Glance. Koin must inject that same [settingsDataStore] singleton; a second
     * factory on the same file crashes with "multiple DataStores active for the same file".
     */
    @Test
    fun `widget settings store is the same file the app settings repository uses`() {
        val context = RuntimeEnvironment.getApplication()
        runBlocking {
            context.settingsDataStore.edit { it[APP_LOCK_ENABLED_KEY] = true }
            context.settingsDataStore.data.first()
        }

        val datastoreDir = File(context.filesDir, "datastore")
        val names = datastoreDir.list()?.toSet().orEmpty()

        assertTrue(names.contains(SETTINGS_DATASTORE_FILENAME))
        assertFalse(names.contains("$SETTINGS_DATASTORE_FILENAME.preferences_pb"))
    }
}
