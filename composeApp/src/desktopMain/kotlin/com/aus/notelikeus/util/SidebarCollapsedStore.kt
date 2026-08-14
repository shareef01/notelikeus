package com.aus.notelikeus.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Persists whether the user collapsed the side drawer, so a rail stays a rail across launches.
 *
 * The web app persists the same preference (its `uiStore` partialize), while desktop previously
 * remembered only the window size — the rail silently reset to expanded at every launch.
 */
class SidebarCollapsedStore(private val dataStore: DataStore<Preferences>) {

    // Same corruption-tolerance trade as WindowMetricsStore: a truncated preferences file must
    // fall back to the default (expanded) rather than take the app down at launch.
    val collapsed: Flow<Boolean> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { preferences -> preferences[KEY] ?: false }

    suspend fun save(collapsed: Boolean) {
        dataStore.edit { preferences -> preferences[KEY] = collapsed }
    }

    private companion object {
        val KEY = booleanPreferencesKey("sidebar_collapsed")
    }
}
