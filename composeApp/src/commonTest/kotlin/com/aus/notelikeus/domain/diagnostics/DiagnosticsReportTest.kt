package com.aus.notelikeus.domain.diagnostics

import com.aus.notelikeus.contract.ContractFixtures
import com.aus.notelikeus.data.attachments.AttachmentStagingStore
import com.aus.notelikeus.data.attachments.StagedAttachment
import com.aus.notelikeus.data.sync.FakeNoteSyncStateStore
import com.aus.notelikeus.data.sync.SuspectEmptyCloudException
import com.aus.notelikeus.data.sync.WrongAccountSyncException
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The diagnostics report's one hard requirement: it says enough to troubleshoot sync and nothing
 * about what the user wrote.
 *
 * Every assertion below is about that boundary. The counts are easy to get right and easy to
 * verify; the leak guard is the part that has to keep working as fields are added, so it is
 * exercised against each shape of secret rather than only against a happy path.
 */
class DiagnosticsReportTest {

    private class FakeStagingStore(
        private val staged: List<StagedAttachment> = emptyList(),
    ) : AttachmentStagingStore {
        override suspend fun stage(
            attachmentId: String,
            ownerId: String,
            noteId: Long?,
            bytes: ByteArray,
            mimeType: String,
        ): StagedAttachment? = null

        override suspend fun readBytes(attachmentId: String, ownerId: String): ByteArray? = null
        override suspend fun metadata(attachmentId: String, ownerId: String): StagedAttachment? =
            null

        override suspend fun bindNote(attachmentId: String, ownerId: String, noteId: Long) = Unit
        override suspend fun release(attachmentId: String, ownerId: String) = Unit
        override suspend fun list(ownerId: String): List<StagedAttachment> = staged
    }

    private fun collector(
        notes: List<Note> = emptyList(),
        staged: List<StagedAttachment> = emptyList(),
        ownerId: String? = null,
        signedIn: Boolean = false,
        stateStore: FakeNoteSyncStateStore = FakeNoteSyncStateStore(),
        loadNotes: (suspend () -> List<Note>)? = null,
    ) = DiagnosticsCollector(
        loadNotes = loadNotes ?: { notes },
        syncStateStore = stateStore,
        staging = FakeStagingStore(staged),
        ownerIdProvider = { ownerId },
        isSignedIn = { signedIn },
        databaseSchemaVersion = 11,
        storageKind = "Room",
        encryptedAtRest = true,
        now = { 1_767_225_600_000L },
    )

    // ---- ownerTag ----

    @Test
    fun ownerTagIsStableAndNeverContainsTheAccountId() {
        val uid = "9c1f7a3e-2b44-4d1a-9f6e-0c8b2d4e6a10"
        val tag = ownerTag(uid)
        assertEquals(tag, ownerTag(uid), "the same account must produce the same tag")
        assertTrue(tag.matches(Regex("acct-[0-9a-f]{8}")), "unexpected tag shape: $tag")
        assertFalse(uid in tag)
        assertFalse("9c1f7a3e" in tag)
        assertTrue(ownerTag("11111111-2222-3333-4444-555555555555") != tag)
    }

    /**
     * The web client hashes the same way, so one account produces one tag on every platform.
     *
     * Read from the shared fixture rather than restated here: the TypeScript suite asserts the
     * same file, so a change to either implementation fails on both sides instead of silently
     * splitting reports from the same user into two identities.
     */
    @Test
    fun ownerTagMatchesTheWebClientsHash() {
        val vectors = Json.parseToJsonElement(
            ContractFixtures.read("diagnostics/owner-tag-vectors.json"),
        ).jsonObject.getValue("vectors").jsonArray

        for (element in vectors) {
            val vector = element.jsonObject
            val ownerId = vector.getValue("ownerId").jsonPrimitive.content
            assertEquals(
                vector.getValue("tag").jsonPrimitive.content,
                ownerTag(ownerId),
                "tag for \"$ownerId\"",
            )
        }
        assertEquals("none", ownerTag(null))
    }

    // ---- error categories ----

    @Test
    fun categorizesThisProjectsOwnFailuresByType() {
        assertEquals(SyncErrorCategory.NONE, categorizeSyncError(null))
        assertEquals(
            SyncErrorCategory.EMPTY_CLOUD_REFUSED,
            categorizeSyncError(SuspectEmptyCloudException(3)),
        )
        assertEquals(
            SyncErrorCategory.WRONG_ACCOUNT,
            categorizeSyncError(WrongAccountSyncException("a", "b")),
        )
    }

    @Test
    fun categorizesByCodeWithoutCarryingTheMessage() {
        val conflict = categorizeSyncError(
            IllegalStateException("Revision conflict for note 7: remote title \"Divorce paperwork\""),
        )
        assertEquals(SyncErrorCategory.CONFLICT, conflict)
        assertFalse("Divorce" in conflict.name, "a category must not carry note content")

        assertEquals(SyncErrorCategory.AUTH, categorizeSyncError(Exception("401 invalid JWT")))
        assertEquals(
            SyncErrorCategory.PERMISSION,
            categorizeSyncError(Exception("new row violates row-level security policy")),
        )
        assertEquals(
            SyncErrorCategory.OFFLINE,
            categorizeSyncError(Exception("Unable to resolve host")),
        )
        assertEquals(SyncErrorCategory.UPSTREAM, categorizeSyncError(Exception("502 Bad Gateway")))
        assertEquals(SyncErrorCategory.UNKNOWN, categorizeSyncError(Exception("something new")))
    }

    // ---- the leak guard ----

    @Test
    fun rejectsEveryShapeOfSecret() {
        val leaks = mapOf(
            "a JWT" to "token: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature",
            "an email address" to "user: someone@example.com",
            "a raw account id" to "owner: 9c1f7a3e-2b44-4d1a-9f6e-0c8b2d4e6a10",
            "an object key" to "key: owners/abc/notes/7/att",
            "a staged path" to "path: pending-attachments/guest/att-1",
            "a storage prefix" to "storagePath: r2:notes/7",
            "an auth header" to "header: Bearer abc123",
            "a named password" to "password: hunter2",
            "a bare secret" to "secret: anything",
        )
        for ((label, text) in leaks) {
            assertFailsWith<DiagnosticsLeakException>("must refuse $label") {
                assertNoSensitiveValues(text)
            }
        }
    }

    @Test
    fun acceptsAReportOfCountsAndCursors() {
        assertNoSensitiveValues("notes.total: 12\nsync.knownCloudIdCount: 12\naccount.ownerTag: acct-deadbeef")
    }

    // ---- collection ----

    private fun note(
        id: Long,
        title: String = "",
        archived: Boolean = false,
        trashed: Boolean = false,
        pinned: Boolean = false,
        reminder: Long? = null,
        attachments: List<Attachment> = emptyList(),
        serverUpdatedAt: Long? = null,
        labels: List<Label> = emptyList(),
        checklist: List<ChecklistItem> = emptyList(),
    ) = Note(
        id = id,
        title = title,
        content = "",
        timestamp = 1L,
        color = 0,
        isPinned = pinned,
        isArchived = archived,
        isTrashed = trashed,
        reminderTimestamp = reminder,
        serverUpdatedAt = serverUpdatedAt,
        attachments = attachments,
        labels = labels,
        checklist = checklist,
    )

    @Test
    fun countsNoteStateWithoutCarryingNoteContent() = runTest {
        val report = collector(
            notes = listOf(
                note(1, title = "Divorce paperwork", labels = listOf(Label(1L, "Medical"))),
                note(2, archived = true),
                note(
                    3,
                    trashed = true,
                    reminder = 4_102_444_800_000L,
                    attachments = listOf(
                        Attachment(id = "att-one", noteId = 3, storagePath = "pending:att-one"),
                    ),
                    checklist = listOf(ChecklistItem(text = "Call the lawyer", position = 0)),
                ),
                note(4, pinned = true),
            ),
            ownerId = "9c1f7a3e-2b44-4d1a-9f6e-0c8b2d4e6a10",
            signedIn = true,
        ).collect()

        assertEquals(
            DiagnosticsReport.Notes(
                total = 4,
                active = 2,
                archived = 1,
                trashed = 1,
                pinned = 1,
                withReminder = 1,
                withAttachments = 1,
            ),
            report.notes,
        )
        assertEquals(DiagnosticsReport.AccountState.SIGNED_IN, report.account.state)
        assertEquals(1, report.attachments.pendingUploadCount)

        val text = formatDiagnosticsReport(report)
        for (secret in listOf("Divorce", "paperwork", "Medical", "Call the lawyer", "att-one")) {
            assertFalse(secret in text, "report must not contain \"$secret\"")
        }
    }

    @Test
    fun countsPendingMutationsAndTombstones() = runTest {
        val stateStore = FakeNoteSyncStateStore().apply {
            setKnownCloudIds(setOf(1L))
            markDeleted(9L, 1L)
            markRestored(11L)
            markReconciled(1_767_000_000_000L)
        }

        val report = collector(
            notes = listOf(
                note(1, serverUpdatedAt = 100L), // confirmed
                note(2, serverUpdatedAt = 100L), // confirmed but not in knownCloudIds
                note(3), // never confirmed
            ),
            stateStore = stateStore,
            ownerId = "9c1f7a3e-2b44-4d1a-9f6e-0c8b2d4e6a10",
            signedIn = true,
        ).collect()

        assertEquals(1, report.sync.knownCloudIdCount)
        assertEquals(2, report.sync.pendingMutationCount)
        assertEquals(1, report.sync.tombstoneCount)
        assertEquals(1, report.sync.pendingRestoreCount)
        assertEquals(1_767_000_000_000L, report.sync.lastReconciledAt)
    }

    @Test
    fun reportsStagedBytesAndUnresolvedCleanup() = runTest {
        val report = collector(
            notes = listOf(
                note(
                    1,
                    attachments = listOf(
                        Attachment(id = "att-a", noteId = 1, storagePath = "pending:att-a"),
                    ),
                ),
            ),
            staged = listOf(
                StagedAttachment("att-a", "guest", 1L, "image/png", 1_024, 0L),
                // Staged but no live note references it: the number a user sees as storage
                // they cannot account for.
                StagedAttachment("att-orphan", "guest", null, "image/png", 2_048, 0L),
            ),
        ).collect()

        assertEquals(2, report.attachments.stagedCount)
        assertEquals(3_072L, report.attachments.stagedBytes)
        assertEquals(1, report.attachments.pendingUploadCount)
        assertEquals(1, report.attachments.unresolvedCleanupCount)
    }

    @Test
    fun recordsTheLastFailureCategoryAndClearsItOnSuccess() = runTest {
        val collector = collector()
        assertEquals(SyncErrorCategory.NONE, collector.collect().sync.lastErrorCategory)

        collector.recordSyncFailure(SuspectEmptyCloudException(2))
        assertEquals(
            SyncErrorCategory.EMPTY_CLOUD_REFUSED,
            collector.collect().sync.lastErrorCategory,
        )

        collector.recordSyncSuccess()
        assertEquals(SyncErrorCategory.NONE, collector.collect().sync.lastErrorCategory)
    }

    @Test
    fun degradesRatherThanFailingWhenAStoreThrows() = runTest {
        // Diagnostics run when something is already broken; a report that cannot be produced is
        // the least useful possible outcome.
        val report = collector(loadNotes = { throw IllegalStateException("database is gone") })
            .collect()

        assertEquals(0, report.notes.total)
        assertEquals("Room", report.storage.kind)
        assertTrue(report.storage.encryptedAtRest)
    }

    @Test
    fun signedOutReportsGuestRatherThanAHashedNamespace() = runTest {
        val report = collector(signedIn = false, ownerId = null).collect()
        assertEquals(DiagnosticsReport.AccountState.SIGNED_OUT, report.account.state)
        assertEquals("guest", report.account.ownerTag)
        assertNoSensitiveValues(formatDiagnosticsReport(report))
    }
}
