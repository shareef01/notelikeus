package com.aus.notelikeus.util

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

class WindowMetricsStore(private val dataStore: DataStore<Preferences>) {

    // main.kt blocks on the first value before the window exists, so a truncated preferences
    // file (a power cut mid-write) would otherwise turn into an unrecoverable launch crash.
    // Losing the remembered window size is the right trade against not starting at all.
    val metrics: Flow<WindowMetrics> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { preferences ->
            WindowMetrics(
                width = preferences[WIDTH_KEY]?.dp ?: 1000.dp,
                height = preferences[HEIGHT_KEY]?.dp ?: 800.dp,
                x = preferences[X_KEY]?.dp,
                y = preferences[Y_KEY]?.dp,
                isMaximized = preferences[MAXIMIZED_KEY] ?: false
            )
        }

    suspend fun saveMetrics(metrics: WindowMetrics) {
        dataStore.edit { preferences ->
            preferences[WIDTH_KEY] = metrics.width.value
            preferences[HEIGHT_KEY] = metrics.height.value
            metrics.x?.let { preferences[X_KEY] = it.value }
            metrics.y?.let { preferences[Y_KEY] = it.value }
            preferences[MAXIMIZED_KEY] = metrics.isMaximized
        }
    }

    companion object {
        private val WIDTH_KEY = floatPreferencesKey("window_width")
        private val HEIGHT_KEY = floatPreferencesKey("window_height")
        private val X_KEY = floatPreferencesKey("window_x")
        private val Y_KEY = floatPreferencesKey("window_y")
        private val MAXIMIZED_KEY = booleanPreferencesKey("window_maximized")
    }
}

data class WindowMetrics(
    val width: Dp,
    val height: Dp,
    val x: Dp?,
    val y: Dp?,
    val isMaximized: Boolean = false
)
