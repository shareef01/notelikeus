package com.aus.notelikeus.domain.diagnostics

import com.aus.notelikeus.data.attachments.AttachmentStagingStore
import com.aus.notelikeus.data.attachments.GUEST_STAGING_OWNER
import com.aus.notelikeus.data.attachments.isPendingAttachment
import com.aus.notelikeus.data.sync.NoteSyncStateStore
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.util.AppConfig
import com.aus.notelikeus.util.DateUtils

/**
 * Assembles a [DiagnosticsReport] from the state this device actually holds.
 *
 * Every read is defensive. This runs when something is already wrong, so a store that throws must
 * degrade the report rather than replace it with an error — a diagnostics screen that itself
 * fails is the least useful possible outcome.
 */
class DiagnosticsCollector(
    private val loadNotes: suspend () -> List<Note>,
    private val syncStateStore: NoteSyncStateStore,
    private val staging: AttachmentStagingStore,
    private val ownerIdProvider: () -> String?,
    private val isSignedIn: () -> Boolean,
    private val databaseSchemaVersion: Int,
    private val storageKind: String,
    private val encryptedAtRest: Boolean,
    private val now: () -> Long = { DateUtils.currentTimeMillis() },
) {
    /** The category of the last sync failure, recorded as it happens. */
    var lastErrorCategory: SyncErrorCategory = SyncErrorCategory.NONE
        private set

    fun recordSyncFailure(error: Throwable?) {
        lastErrorCategory = categorizeSyncError(error)
    }

    fun recordSyncSuccess() {
        lastErrorCategory = SyncErrorCategory.NONE
    }

    suspend fun collect(): DiagnosticsReport {
        val notes = runCatching { loadNotes() }.getOrDefault(emptyList())
        val ownerId = runCatching { ownerIdProvider() }.getOrNull()
        val knownCloudIds = runCatching { syncStateStore.knownCloudIds() }.getOrDefault(emptySet())
        // Staged bytes are namespaced by the same key the upload path uses, so a signed-out
        // device reports what it actually holds rather than an empty namespace.
        val stagingOwner = ownerId ?: GUEST_STAGING_OWNER
        val staged = runCatching { staging.list(stagingOwner) }.getOrDefault(emptyList())

        val pendingUploads = notes.sumOf { note ->
            note.attachments.count { isPendingAttachment(it.storagePath) }
        }

        return DiagnosticsReport(
            generatedAt = now(),
            appVersion = AppConfig.versionName,
            platform = if (AppConfig.isDesktop) "windows" else "android",
            storage = DiagnosticsReport.Storage(
                kind = storageKind,
                schemaVersion = databaseSchemaVersion,
                encryptedAtRest = encryptedAtRest,
            ),
            account = DiagnosticsReport.Account(
                state = when {
                    isSignedIn() -> DiagnosticsReport.AccountState.SIGNED_IN
                    ownerId != null -> DiagnosticsReport.AccountState.GUEST
                    else -> DiagnosticsReport.AccountState.SIGNED_OUT
                },
                // A guest has no account to tag; saying so is more useful than hashing a
                // namespace constant into something that looks like an account.
                ownerTag = if (isSignedIn()) ownerTag(ownerId) else "guest",
            ),
            notes = DiagnosticsReport.Notes(
                total = notes.size,
                active = notes.count { !it.isArchived && !it.isTrashed },
                archived = notes.count { it.isArchived },
                trashed = notes.count { it.isTrashed },
                pinned = notes.count { it.isPinned },
                withReminder = notes.count { it.reminderTimestamp != null },
                withAttachments = notes.count { it.attachments.isNotEmpty() },
            ),
            sync = DiagnosticsReport.Sync(
                knownCloudIdCount = knownCloudIds.size,
                // A note the server has never confirmed, or one it has not seen since. This is
                // the number behind "why does it still say pending".
                pendingMutationCount = notes.count { note ->
                    note.serverUpdatedAt == null || note.id !in knownCloudIds
                },
                tombstoneCount = runCatching { syncStateStore.deletedIds().size }.getOrDefault(0),
                pendingRestoreCount = runCatching { syncStateStore.restoredIds().size }
                    .getOrDefault(0),
                lastReconciledAt = runCatching { syncStateStore.lastReconciledAt() }
                    .getOrDefault(0L),
                lastErrorCategory = lastErrorCategory,
            ),
            attachments = DiagnosticsReport.Attachments(
                stagedCount = staged.size,
                stagedBytes = staged.sumOf { it.sizeBytes },
                pendingUploadCount = pendingUploads,
                // Staged bytes no live note references any more: the number that explains
                // storage a user cannot account for.
                unresolvedCleanupCount = (staged.size - pendingUploads).coerceAtLeast(0),
            ),
        )
    }
}
