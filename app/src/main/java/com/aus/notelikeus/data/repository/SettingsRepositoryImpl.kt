package com.aus.notelikeus.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aus.notelikeus.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private val TRUE_DARK_MODE_KEY = booleanPreferencesKey("true_dark_mode")

    override val isTrueDarkMode: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[TRUE_DARK_MODE_KEY] ?: false
        }

    override suspend fun setTrueDarkMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[TRUE_DARK_MODE_KEY] = enabled
        }
    }
}
