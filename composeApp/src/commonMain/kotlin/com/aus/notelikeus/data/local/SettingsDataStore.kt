package com.aus.notelikeus.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

val APP_LOCK_ENABLED_KEY = booleanPreferencesKey("app_lock_enabled")
val APP_THEME_KEY = stringPreferencesKey("app_theme")
val NOTE_VIEW_MODE_KEY = stringPreferencesKey("note_view_mode")
val NOTE_SORT_ORDER_KEY = stringPreferencesKey("note_sort_order")
/** Used by the home-screen widget theme selection (kept even though the in-app theme is fixed). */
val USE_MONOCHROME_THEME_KEY = booleanPreferencesKey("use_monochrome_theme")
val CLOUD_AUTO_SYNC_ENABLED_KEY = booleanPreferencesKey("cloud_auto_sync_enabled")
val RECENT_SEARCHES_KEY = stringPreferencesKey("recent_searches")

const val SETTINGS_DATASTORE_FILENAME = "settings.preferences_pb"
