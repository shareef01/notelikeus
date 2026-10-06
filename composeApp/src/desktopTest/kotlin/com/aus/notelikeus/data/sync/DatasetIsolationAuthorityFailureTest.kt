package com.aus.notelikeus.data.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.local.entity.LabelEntity
import com.aus.notelikeus.data.local.entity.NoteLabelCrossRef
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.data.repository.NoteRepositoryImpl
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.platform.PendingSyncKind
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.domain.platform.ScheduleOutcome
import com.aus.notelikeus.domain.repository.LocalCommitResult
import com.aus.notelikeus.platform.DesktopSyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R15.3 — an isolation that cannot complete must not leave the old dataset executable.
 *
 * R15.1 and R15.2 made queued work carry the dataset it originated in and refuse it in the dataset
 * that replaced that one. Both assumed the *current* dataset is a settled fact. It is not while an
 * isolation is failing: the account boundary has already been crossed — the local generation has
 * advanced and the user's session has changed — but the durable epoch could not be replaced, so the
 * previous dataset was still what every check compared against. Its queued work validated, in this
 * process *and* in the next one, because the durable record still said E1 was current.
 *
 * The fix is a durable transition: the isolation is marked before the boundary is crossed and cleared
 * only after the wipe, so every crash window in between reads back as "between datasets" and refuses
 * — action-origin work, current-dataset maintenance and the worker's authority handoff alike.
 *
 * Every lane below drives the real `LocalAccountIsolator`, the real `NoteRepositoryImpl` over a real
 * Room database, the real desktop coordinator and the real `DatasetEpochAuthority`; only the platform
 * collaborators and the durable store are doubles.
 */
class DatasetIsolationAuthorityFailureTest {

    private lateinit var tempDir: File
    private lateinit var database: NotelikeusDatabase

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("notelikeus-isolation-authority-test").toFile()
        database = Room.databaseBuilder<NotelikeusDatabase>(
            name = File(tempDir, "test.db").absolutePath,
        )
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    @AfterTest
    fun tearDown() {
        // The quarantine is process-wide by design — every local commit consults it — so a lane that
        // installs one has to take it back down.
        LocalCommitGate.installDatasetAuthorityQuarantine(null)
        database.close()
        tempDir.deleteRecursively()
    }

    /**
     * One process: a durable store, the authority over it, the coordinator, the repository and the
     * isolator. [restart] builds the next one over the same durable backing, with fresh in-memory
     * state — which is what makes "survives a restart" a real observation rather than an assumption.
     */
    private inner class Process(backing: InMemoryDatasetEpochStore) {
        val store: InMemoryDatasetEpochStore = backing
        val transport = FakeCloudNoteTransport()
        val stateStore = FakeNoteSyncStateStore()
        val authority = DatasetEpochAuthority(backing)
        val coordinator: DesktopSyncCoordinator
        val repository: NoteRepositoryImpl
        val isolator: LocalAccountIsolator

        init {
            coordinator = DesktopSyncCoordinator(
                syncEngine = engine(),
                epochAuthority = authority,
                ownerUidProvider = { SAME_UID },
                // A scope that cannot run: these lanes assert what is durable and what refused, never
                // what a debounce eventually dispatched.
                scope = CoroutineScope(SupervisorJob().apply { cancel() }),
            )
            repository = NoteRepositoryImpl(
                database = database,
                noteDao = database.noteDao,
                labelDao = database.labelDao,
                reminderManager = NoopReminderManager(),
                widgetManager = NoopWidgetManager(),
                syncCoordinator = coordinator,
                ioDispatcher = Dispatchers.Unconfined,
            )
            isolator = LocalAccountIsolator(
                noteRepository = repository,
                syncStateStore = stateStore,
                syncCoordinator = coordinator,
            )
        }

        fun engine() = NoteSyncEngine(
            transport = transport,
            remoteIdentityProvider = testRemoteIdentityProvider { SAME_UID },
            noteDao = database.noteDao,
            labelDao = database.labelDao,
            syncStateStore = stateStore,
            uidProvider = { Result.success(SAME_UID) },
            platform = "desktop",
            datasetEpochAuthority = authority,
        )

        /** The next process over the same durable backing. */
        fun restart(): Process = this@DatasetIsolationAuthorityFailureTest.Process(store)

        fun quarantine() {
            LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
        }

        /** The transition marker lands, the completion write fails — F-4C's exact state. */
        fun failCompletionWrite(): IllegalStateException {
            val failure = IllegalStateException("disk full")
            store.beforeSave = { pending ->
                if (pending.isolationTarget == null) throw failure
            }
            return failure
        }

        /** Queues one action-origin delete and hands back what a worker would have carried. */
        suspend fun queuedDelete(epoch: DatasetEpoch): PendingSyncCommand {
            assertTrue(authority.enqueueIfCurrent(epoch, PendingSyncKind.DELETE, NOTE_ID, SAME_UID))
            return authority.currentPending().commandsOf(PendingSyncKind.DELETE).single()
        }

        /** What the engine's origin resolution decides — the earliest discriminating observable. */
        suspend fun runScheduledDelete(originEpoch: DatasetEpoch): ScheduledWorkOutcome =
            engine().deleteNoteFromScheduledWork(
                NOTE_ID,
                ScheduledWorkOrigin(SAME_UID, originEpoch),
            )
    }

    private fun process(): Process = Process(InMemoryDatasetEpochStore())

    private suspend fun seedNote(noteId: Long, title: String, labelId: Long? = null) {
        labelId?.let { database.labelDao.insertLabel(LabelEntity(id = it, name = "label-$it")) }
        database.noteDao.insertNote(
            Note(id = noteId, title = title, content = "", timestamp = 1L, color = 0).toNoteEntity(),
        )
        labelId?.let { database.noteDao.insertNoteLabelCrossRef(NoteLabelCrossRef(noteId, it)) }
    }

    // ---- ISOAUTH-1 / ISOAUTH-2: the same process, and the next one ----

    @Test
    fun `ISOAUTH-1 an incomplete isolation cannot validate the old dataset's queued work`() = runTest {
        val process = process()
        seedNote(NOTE_ID, "old-generation")
        val epoch = process.authority.awaitStableEpoch()
        val command = process.queuedDelete(epoch)
        val generationBefore = LocalCommitGate.currentGeneration()

        val failure = process.failCompletionWrite()
        val thrown = runCatching { process.isolator.isolate() }

        assertTrue(
            thrown.exceptionOrNull() === failure,
            "the isolation reported success although its completion write failed",
        )
        // The state F-4C was about: the durable epoch is still E1 while the account boundary has
        // already moved, so "current dataset" and "current generation" no longer describe one world.
        assertEquals(epoch, process.store.durable.epoch, "the durable epoch moved despite the failure")
        assertNull(process.authority.currentEpochOrNull(), "the old epoch is still reported as current")
        assertTrue(process.authority.isIsolationIncomplete())
        assertTrue(
            LocalCommitGate.currentGeneration() > generationBefore,
            "the account boundary did not advance, so this case proves nothing",
        )

        assertEquals(
            ScheduledWorkOutcome.RefusedStaleOrigin,
            process.runScheduledDelete(command.datasetEpoch),
        )
        assertTrue(process.stateStore.deletedIds().isEmpty(), "the incomplete isolation's work applied")
        assertTrue(process.transport.tombstones.isEmpty(), "the incomplete isolation's work reached the cloud")
    }

    @Test
    fun `ISOAUTH-2 the same failure cannot validate the old work after a restart`() = runTest {
        val first = process()
        seedNote(NOTE_ID, "old-generation")
        val epoch = first.authority.awaitStableEpoch()
        val command = first.queuedDelete(epoch)
        first.failCompletionWrite()
        runCatching { first.isolator.isolate() }

        // A new process over the same durable backing. Nothing about the old process survives except
        // what was written down — which is exactly why the marker is durable and not a flag.
        val second = first.restart()
        val authority = second.authority.awaitAuthority()
        assertIs<DatasetAuthority.Isolating>(authority, "a restarted process read the old epoch as current")
        assertEquals(epoch, authority.from, "the transition lost the dataset it is leaving")

        assertEquals(
            ScheduledWorkOutcome.RefusedStaleOrigin,
            second.runScheduledDelete(command.datasetEpoch),
        )
        assertTrue(second.stateStore.deletedIds().isEmpty())
        assertTrue(second.transport.tombstones.isEmpty())
    }

    // ---- ISOAUTH-3 / ISOAUTH-4: nothing new may be queued or run while between datasets ----

    @Test
    fun `ISOAUTH-3 a new action cannot enqueue while the isolation is incomplete`() = runTest {
        val process = process()
        seedNote(NOTE_ID, "old-generation", labelId = LABEL_ID)
        process.quarantine()
        process.authority.beginIsolation()

        val outcome = process.repository.updateLabel(
            Label(id = LABEL_ID, name = "Renamed"),
            actionToken(SAME_UID),
        )

        // The local write is refused too — the rows on disk belong to the dataset being left — and
        // either way nothing is queued under either epoch.
        assertIs<LocalCommitResult.StaleGeneration>(outcome)
        assertTrue(process.authority.currentPending().isEmpty, "the transition queued work")
        assertEquals("label-$LABEL_ID", database.labelDao.getLabelById(LABEL_ID)?.name)
    }

    @Test
    fun `ISOAUTH-3b the quarantine decides local commits, and only while it is installed`() = runTest {
        val process = process()
        seedNote(NOTE_ID, "old-generation")
        process.authority.beginIsolation()

        // Without the quarantine the local commit is still allowed through and its upload is refused;
        // with it, the commit itself refuses, so residual rows cannot be mutated at all.
        assertIs<LocalCommitResult.Applied<Unit>>(
            LocalCommitGate.commit(actionToken(SAME_UID)) { },
        )
        assertEquals(
            ScheduleOutcome.RefusedStaleOrigin,
            process.coordinator.scheduleUploadFromAction(NOTE_ID, actionToken(SAME_UID)),
        )
        assertTrue(process.authority.currentPending().isEmpty)

        process.quarantine()
        assertIs<LocalCommitResult.StaleGeneration>(
            LocalCommitGate.commit(actionToken(SAME_UID)) { },
            "a local commit proceeded while the device was between datasets",
        )
    }

    @Test
    fun `ISOAUTH-4 current-dataset maintenance cannot run while the isolation is incomplete`() = runTest {
        val process = process()
        seedNote(NOTE_ID, "old-generation")
        process.quarantine()
        process.stateStore.setLastMergedUserId(SAME_UID)
        process.authority.beginIsolation()

        process.coordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, NOTE_ID)
        assertTrue(
            process.authority.currentPending().isEmpty,
            "maintenance work was stamped and queued although there is no current dataset",
        )

        // The engine's own maintenance route stops before any remote work: its one identity capture is
        // a fenced local decision, and that is what refused it.
        assertEquals(0, process.engine().reconcileUploads().getOrNull())
        assertTrue(process.transport.notes.isEmpty(), "current-dataset maintenance reached the cloud")
        assertTrue(process.transport.syncMetaCalls.isEmpty())
    }

    // ---- ISOAUTH-5 / ISOAUTH-6 / ISOAUTH-7: recovery, and who may trigger it ----

    @Test
    fun `ISOAUTH-5 recovery completes the transition and reaches the epoch it had already chosen`() =
        runTest {
            val process = process()
            seedNote(NOTE_ID, "old-generation")
            val epoch = process.authority.awaitStableEpoch()
            val command = process.queuedDelete(epoch)
            process.failCompletionWrite()
            runCatching { process.isolator.isolate() }

            val target = (process.authority.awaitAuthority() as DatasetAuthority.Isolating).next

            // The store recovers; the next attempt finishes what the first one started.
            process.store.beforeSave = null
            process.isolator.recoverIncompleteIsolationIfAny()

            assertEquals(
                DatasetAuthority.Stable(target),
                process.authority.awaitAuthority(),
                "recovery minted an unrelated epoch instead of completing the transition",
            )
            assertNull(database.noteDao.getNoteById(NOTE_ID), "recovery did not wipe the old dataset")
            assertTrue(process.authority.currentPending().isEmpty)

            // Old work is dead, and the device is usable again: a fresh action schedules normally.
            assertEquals(
                ScheduledWorkOutcome.RefusedStaleOrigin,
                process.runScheduledDelete(command.datasetEpoch),
            )
            seedNote(SECOND_NOTE_ID, "new-generation")
            assertEquals(
                ScheduleOutcome.Enqueued,
                process.coordinator.scheduleUploadFromAction(SECOND_NOTE_ID, actionToken(SAME_UID)),
            )
        }

    @Test
    fun `ISOAUTH-6 the same uid signing back in cannot rescue the old dataset`() = runTest {
        val process = process()
        seedNote(NOTE_ID, "old-generation")
        val epoch = process.authority.awaitStableEpoch()
        process.stateStore.setLastMergedUserId(SAME_UID)
        val command = process.queuedDelete(epoch)
        process.failCompletionWrite()
        runCatching { process.isolator.isolate() }

        // The primary D11 case: the *same* account returns, so a uid comparison sees no boundary at
        // all. The unfinished transition is what says otherwise, and it is completed rather than
        // adopted.
        process.store.beforeSave = null
        process.isolator.isolateIfAccountChanged(SAME_UID)

        assertIs<DatasetAuthority.Stable>(process.authority.awaitAuthority())
        assertNull(database.noteDao.getNoteById(NOTE_ID), "the same uid's rows were kept")
        assertEquals(
            ScheduledWorkOutcome.RefusedStaleOrigin,
            process.runScheduledDelete(command.datasetEpoch),
        )
    }

    @Test
    fun `ISOAUTH-7 a different account cannot touch the residual dataset`() = runTest {
        val process = process()
        seedNote(NOTE_ID, "old-generation", labelId = LABEL_ID)
        val epoch = process.authority.awaitStableEpoch()
        process.stateStore.setLastMergedUserId(SAME_UID)
        val command = process.queuedDelete(epoch)
        process.quarantine()

        // The state where the residual rows are *still there*: the boundary was crossed and the wipe
        // did not run. This is the window §16 is about, and A's rows must be untouchable in it.
        process.authority.beginIsolation()
        LocalCommitGate.isolate { }
        assertEquals("label-$LABEL_ID", database.labelDao.getLabelById(LABEL_ID)?.name)
        assertIs<LocalCommitResult.StaleGeneration>(
            process.repository.updateLabel(
                Label(id = LABEL_ID, name = "B-rename"),
                actionToken(OTHER_UID),
            ),
        )
        assertEquals(
            "label-$LABEL_ID",
            database.labelDao.getLabelById(LABEL_ID)?.name,
            "A's residual row was written while the device was between datasets",
        )

        // B signing in completes the unfinished transition instead of adopting what is on disk.
        process.isolator.isolateIfAccountChanged(OTHER_UID)

        assertIs<DatasetAuthority.Stable>(process.authority.awaitAuthority())
        assertNull(database.noteDao.getNoteById(NOTE_ID), "A's rows survived B's sign-in")
        assertNull(database.labelDao.getLabelById(LABEL_ID))
        assertEquals(
            ScheduledWorkOutcome.RefusedStaleOrigin,
            process.runScheduledDelete(command.datasetEpoch),
        )
    }

    // ---- ISOAUTH-8 / ISOAUTH-9 / ISOAUTH-10: the crash matrix ----

    @Test
    fun `ISOAUTH-8 a crash after the transition is marked refuses everything, without a boundary`() =
        runTest {
            val first = process()
            seedNote(NOTE_ID, "old-generation")
            val epoch = first.authority.awaitStableEpoch()
            val command = first.queuedDelete(epoch)
            val generationBefore = LocalCommitGate.currentGeneration()

            // Marked, then the process dies before the account boundary is crossed.
            first.authority.beginIsolation()
            assertEquals(
                LocalCommitGate.currentGeneration(),
                generationBefore,
                "merely marking the transition crossed the account boundary",
            )

            val second = first.restart()
            assertIs<DatasetAuthority.Isolating>(second.authority.awaitAuthority())
            assertEquals(
                ScheduledWorkOutcome.RefusedStaleOrigin,
                second.runScheduledDelete(command.datasetEpoch),
            )

            second.isolator.recoverIncompleteIsolationIfAny()
            assertIs<DatasetAuthority.Stable>(second.authority.awaitAuthority())
            assertNull(database.noteDao.getNoteById(NOTE_ID))
        }

    @Test
    fun `ISOAUTH-9 a crash after the boundary but before the wipe still refuses everything`() = runTest {
        val first = process()
        seedNote(NOTE_ID, "old-generation")
        val epoch = first.authority.awaitStableEpoch()
        val command = first.queuedDelete(epoch)

        // The boundary is crossed and the process dies before the wipe runs: no completion write, no
        // cleared rows, and the durable record still names the dataset being left.
        first.authority.beginIsolation()
        LocalCommitGate.isolate { }

        val second = first.restart()
        assertIs<DatasetAuthority.Isolating>(second.authority.awaitAuthority())
        assertEquals(
            ScheduledWorkOutcome.RefusedStaleOrigin,
            second.runScheduledDelete(command.datasetEpoch),
        )

        second.isolator.recoverIncompleteIsolationIfAny()
        assertIs<DatasetAuthority.Stable>(second.authority.awaitAuthority())
        assertNull(database.noteDao.getNoteById(NOTE_ID), "recovery did not finish the wipe")
    }

    @Test
    fun `ISOAUTH-10 a crash after the wipe but before the completion write still refuses everything`() =
        runTest {
            val first = process()
            seedNote(NOTE_ID, "old-generation")
            val epoch = first.authority.awaitStableEpoch()
            val command = first.queuedDelete(epoch)

            // The wipe ran; the completion write never did. The device must not be reported as settled
            // on the new dataset while the old one's authority is still what the store records.
            first.authority.beginIsolation()
            val target = (first.authority.awaitAuthority() as DatasetAuthority.Isolating).next
            LocalCommitGate.isolate {
                first.stateStore.clear()
                first.repository.clearAllUserData()
            }

            val second = first.restart()
            assertIs<DatasetAuthority.Isolating>(second.authority.awaitAuthority())
            assertEquals(
                ScheduledWorkOutcome.RefusedStaleOrigin,
                second.runScheduledDelete(command.datasetEpoch),
            )

            second.isolator.recoverIncompleteIsolationIfAny()
            assertEquals(DatasetAuthority.Stable(target), second.authority.awaitAuthority())
            assertNull(database.noteDao.getNoteById(NOTE_ID))
        }

    // ---- ISOAUTH-11 / ISOAUTH-12: the ordinary path, and the lock order ----

    @Test
    fun `ISOAUTH-11 an ordinary isolation still replaces the dataset in two durable writes`() = runTest {
        val process = process()
        seedNote(NOTE_ID, "old-generation")
        val epoch = process.authority.awaitStableEpoch()
        val command = process.queuedDelete(epoch)
        val writesBefore = process.store.writes.size

        process.isolator.isolate()

        val transition = process.store.writes.drop(writesBefore)
        assertTrue(
            transition.size in 2..3,
            "the transition wrote ${transition.size} times; it is a marker, a queue drop and a completion",
        )
        // Each write is atomic on its own, and none ever pairs a new epoch with the old queue: the
        // marker names the dataset being left, and only the completion establishes the new one.
        assertEquals(epoch, transition.first().epoch, "the marker did not name the dataset being left")
        assertTrue(transition.first().isIsolating, "the marker did not mark a transition")
        assertEquals(1, transition.count { it.isIsolating }, "the marker was written more than once")
        val settledEpoch = transition[1].epoch
        assertTrue(settledEpoch != null)
        assertNotEquals(epoch, settledEpoch, "the completion did not reach a new epoch")
        assertEquals(settledEpoch, transition[0].isolationTarget, "the transition reached another epoch")
        assertTrue(!transition[1].isIsolating, "the completion left the transition marked")
        assertTrue(transition[1].commands.isEmpty(), "the completion kept the old dataset's queue")

        assertEquals(DatasetAuthority.Stable(settledEpoch), process.authority.awaitAuthority())
        assertNull(database.noteDao.getNoteById(NOTE_ID), "the old dataset was not wiped")
        assertEquals(
            ScheduledWorkOutcome.RefusedStaleOrigin,
            process.runScheduledDelete(command.datasetEpoch),
        )
    }

    @Test
    fun `ISOAUTH-12 isolation and scheduling cannot deadlock against each other`() = runTest {
        val process = process()
        process.quarantine()
        seedNote(NOTE_ID, "mine")
        process.authority.awaitStableEpoch()

        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(DEADLOCK_TIMEOUT_MS) {
                val schedulers = (1..6).map { index ->
                    async(Dispatchers.Default) {
                        repeat(SCHEDULE_ATTEMPTS) {
                            process.coordinator.scheduleUploadFromAction(
                                noteId = index.toLong(),
                                origin = actionToken(SAME_UID),
                            )
                        }
                    }
                }
                val isolations = (1..4).map {
                    async(Dispatchers.Default) { process.isolator.isolate() }
                }
                (schedulers + isolations).awaitAll()
            }
        }

        // Both families ran to completion. The authority's mutex is held together with the gate only
        // by the completion write; the marker takes it on its own, and the scheduling path releases the
        // gate before it reaches the queue — so the two orders cannot be inverted.
        assertIs<DatasetAuthority.Stable>(process.authority.awaitAuthority())
    }

    @Test
    fun `ISOAUTH-12b the authority never acquires the commit gate`() {
        val offenders = ProductionIsolationSources.authorityGateReferences()
        assertEquals(
            emptyList(),
            offenders,
            "the epoch authority's code reached the commit gate, which would invert the lock order:\n" +
                offenders.joinToString("\n"),
        )
    }

    private class NoopReminderManager : ReminderManager {
        override fun scheduleReminder(noteId: Long, timestamp: Long) {}
        override fun cancelReminder(noteId: Long) {}
    }

    private class NoopWidgetManager : PlatformWidgetManager {
        override suspend fun refreshWidgets() {}
    }

    private companion object {
        const val SAME_UID = "same-user"
        const val OTHER_UID = "other-user"
        const val NOTE_ID = 42L
        const val SECOND_NOTE_ID = 43L
        const val LABEL_ID = 7L
        const val DEADLOCK_TIMEOUT_MS = 30_000L
        const val SCHEDULE_ATTEMPTS = 15
    }
}

/**
 * The source half of the lock-order proof: `LocalCommitGate` reaches into
 * `DatasetEpochAuthority.isIsolationIncomplete`, so the authority must never reach back.
 */
private object ProductionIsolationSources {

    /**
     * Any *code* reference in the authority to the gate, either direction of the lock order.
     *
     * Comments are stripped first: the authority's KDoc deliberately explains the order, and naming
     * `LocalCommitGate` there is documentation rather than an acquisition.
     */
    fun authorityGateReferences(): List<String> {
        val source = stripComments(File(repoRoot(), AUTHORITY_SOURCE).readText())
        return GATE_REFERENCES.filter { source.contains(it) }
            .map { "authority source references '$it' outside a comment" }
    }

    private const val AUTHORITY_SOURCE =
        "composeApp/src/commonMain/kotlin/com/aus/notelikeus/data/sync/DatasetEpochAuthority.kt"

    private val GATE_REFERENCES = listOf("LocalCommitGate", "installDatasetAuthorityQuarantine")

    private fun stripComments(source: String): String =
        source
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")

    private fun repoRoot(): File {
        var candidate = File("").absoluteFile
        while (candidate.parentFile != null) {
            if (File(candidate, "composeApp/src/commonMain").isDirectory) return candidate
            candidate = candidate.parentFile
        }
        error("could not locate the repository root from ${File("").absolutePath}")
    }
}
