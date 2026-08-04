package com.aus.notelikeus.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.aus.notelikeus.data.local.dao.NoteDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LegacyAttachmentCleanup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val noteDao: NoteDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun scheduleIfNeeded() {
        scope.launch {
            runIfNeeded()
        }
    }

    internal suspend fun runIfNeeded() {
        val alreadyCleaned = context.settingsDataStore.data.first()[LEGACY_ATTACHMENTS_CLEANED_KEY] ?: false
        if (alreadyCleaned) return

        deleteOrphanAttachmentFiles(context.filesDir)
        noteDao.deleteAllAttachments()

        context.settingsDataStore.edit { preferences ->
            preferences[LEGACY_ATTACHMENTS_CLEANED_KEY] = true
        }
    }
}

internal fun deleteOrphanAttachmentFiles(filesDir: File) {
    val dir = File(filesDir, "attachments")
    if (!dir.exists()) return
    dir.listFiles()?.forEach { file -> file.delete() }
    dir.delete()
}
