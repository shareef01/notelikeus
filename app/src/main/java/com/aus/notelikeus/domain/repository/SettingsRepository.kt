package com.aus.notelikeus.domain.repository

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val isTrueDarkMode: Flow<Boolean>
    suspend fun setTrueDarkMode(enabled: Boolean)
}
