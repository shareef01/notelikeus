package com.aus.notelikeus.ui.editor.components

import com.aus.notelikeus.data.sync.ConflictResolution
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.ui.main.CloudSyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The editor footer for a signed-in note. The Pixel test found "Saved locally • Synced" on a note
 * edited offline: the footer read the last full sync's result, not whether this note's edit had
 * been uploaded. Local save states (failed, saving, not saved) outrank all of this; the composable
 * checks them first and EditorBottomBarTest covers them.
 */
class EditorSaveStatusTest {

    private fun status(
        cloudSyncStatus: CloudSyncStatus = CloudSyncStatus.Synced,
        noteUploadPending: Boolean = false,
        isOnline: Boolean = true,
        isGuest: Boolean = false,
        attachmentSyncPending: Boolean = false,
    ) = savedNoteSyncStatus(
        cloudSyncStatus = cloudSyncStatus,
        isGuest = isGuest,
        attachmentSyncPending = attachmentSyncPending,
        noteUploadPending = noteUploadPending,
        isOnline = isOnline,
    )

    @Test
    fun `an unuploaded edit offline reads Offline, not Synced`() {
        assertEquals(EditorSaveStatus.Offline, status(noteUploadPending = true, isOnline = false))
    }

    @Test
    fun `an unuploaded edit online reads Sync pending after a successful sync`() {
        assertEquals(EditorSaveStatus.SyncPending, status(noteUploadPending = true))
        // Unknown means no full sync has run yet; the note's own state is still known.
        assertEquals(
            EditorSaveStatus.SyncPending,
            status(cloudSyncStatus = CloudSyncStatus.Unknown, noteUploadPending = true),
        )
    }

    @Test
    fun `an unuploaded edit after a failed sync reads Sync failed`() {
        assertEquals(
            EditorSaveStatus.SyncFailed,
            status(cloudSyncStatus = CloudSyncStatus.Error, noteUploadPending = true),
        )
    }

    @Test
    fun `an uploaded note keeps the global status`() {
        assertEquals(EditorSaveStatus.Synced, status())
        assertEquals(EditorSaveStatus.Synced, status(isOnline = false))
        assertEquals(EditorSaveStatus.Offline, status(cloudSyncStatus = CloudSyncStatus.Offline))
        assertEquals(EditorSaveStatus.SavedLocally, status(cloudSyncStatus = CloudSyncStatus.Unknown))
        assertEquals(EditorSaveStatus.SyncPending, status(attachmentSyncPending = true))
    }

    @Test
    fun `a guest never sees sync wording`() {
        assertEquals(EditorSaveStatus.SavedLocally, status(isGuest = true, noteUploadPending = true, isOnline = false))
    }

    // ---- ConflictResolution.awaitsUpload, the signal behind noteUploadPending ----

    private val note = Note(id = 1L, title = "t", content = "c", timestamp = 200L, color = 0)

    @Test
    fun `a note never uploaded awaits upload`() {
        assertTrue(ConflictResolution.awaitsUpload(note.copy(serverUpdatedAt = null), syncedTimestamp = null))
    }

    @Test
    fun `an edit since the synced baseline awaits upload, the baseline itself does not`() {
        val uploaded = note.copy(serverUpdatedAt = 150L)
        assertFalse(ConflictResolution.awaitsUpload(uploaded, syncedTimestamp = 200L))
        assertTrue(ConflictResolution.awaitsUpload(uploaded.copy(timestamp = 300L), syncedTimestamp = 200L))
    }

    @Test
    fun `without a baseline the server stamp decides`() {
        assertFalse(ConflictResolution.awaitsUpload(note.copy(serverUpdatedAt = 250L), syncedTimestamp = null))
        assertTrue(ConflictResolution.awaitsUpload(note.copy(serverUpdatedAt = 150L), syncedTimestamp = null))
    }
}
