package com.aus.notelikeus.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.settingsDataStore by preferencesDataStore(name = "settings")

val TRUE_DARK_MODE_KEY = booleanPreferencesKey("true_dark_mode")
val APP_LOCK_ENABLED_KEY = booleanPreferencesKey("app_lock_enabled")
val APP_THEME_KEY = stringPreferencesKey("app_theme")
val NOTE_VIEW_MODE_KEY = stringPreferencesKey("note_view_mode")
val NOTE_SORT_ORDER_KEY = stringPreferencesKey("note_sort_order")
val USE_MONOCHROME_THEME_KEY = booleanPreferencesKey("use_monochrome_theme")
val CLOUD_AUTO_SYNC_ENABLED_KEY = booleanPreferencesKey("cloud_auto_sync_enabled")
val LEGACY_ATTACHMENTS_CLEANED_KEY = booleanPreferencesKey("legacy_attachments_cleaned")
val RECENT_SEARCHES_KEY = stringPreferencesKey("recent_searches")
