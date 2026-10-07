package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.sync.PendingCloudWipeIntent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The Desktop half of F-8's durability: the request outlives the process that made it.
 *
 * A unit test cannot kill a process, but it can do the next best thing — write with one store instance,
 * read with a brand-new one over the same file, which is exactly what a restart does.
 */
class DesktopPendingCloudWipeIntentStoreTest {

    @Test
    fun `DURABLE-1 the intent survives a new store instance`() = runTest(timeout = TIMEOUT) {
        val directory = Files.createTempDirectory("pending-wipe").toFile()
        val file = File(directory, "pending_cloud_wipe.txt")

        DesktopPendingCloudWipeIntentStore(file).write(PendingCloudWipeIntent("owner-a", 1234L))

        val reread = DesktopPendingCloudWipeIntentStore(file).read().singleOrNull()
        assertEquals("owner-a", reread?.ownerUid, "the request did not survive a fresh store instance")
        assertEquals(1234L, reread?.requestedAtMillis)

        DesktopPendingCloudWipeIntentStore(file).clear("owner-a")
        assertNull(DesktopPendingCloudWipeIntentStore(file).read().singleOrNull(), "the cleared request came back")
        assertTrue(!file.exists(), "clearing left the file behind")
        directory.deleteRecursively()
    }

    @Test
    fun `DURABLE-2 a missing file reads as nothing pending`() = runTest(timeout = TIMEOUT) {
        val directory = Files.createTempDirectory("pending-wipe-empty").toFile()
        assertTrue(
            DesktopPendingCloudWipeIntentStore(File(directory, "pending_cloud_wipe.txt")).read().isEmpty(),
            "an absent record was read as a pending request",
        )
        directory.deleteRecursively()
    }


    /** DURABLE-3: two owners are stored side by side, and one owner's clear leaves the other's alone. */
    @Test
    fun `DURABLE-3 owners are stored and cleared independently`() = runTest(timeout = TIMEOUT) {
        val directory = Files.createTempDirectory("pending-wipe-two").toFile()
        val file = File(directory, "pending_cloud_wipe.txt")
        val store = DesktopPendingCloudWipeIntentStore(file)
        store.write(PendingCloudWipeIntent("owner-a", 1L))
        store.write(PendingCloudWipeIntent("owner-b", 2L))

        store.clear("owner-a")

        assertEquals(
            listOf("owner-b" to 2L),
            DesktopPendingCloudWipeIntentStore(file).read().map { it.ownerUid to it.requestedAtMillis },
            "clearing one owner changed another owner's record",
        )
        store.write(PendingCloudWipeIntent("owner-a", 3L))
        assertEquals(
            setOf("owner-a", "owner-b"),
            DesktopPendingCloudWipeIntentStore(file).read().map { it.ownerUid }.toSet(),
            "a repeat request disturbed the other owner",
        )
        directory.deleteRecursively()
    }

    /** DURABLE-4/§15: the first version's two-line format is still read. */
    @Test
    fun `DURABLE-4 the single-owner format is still readable`() = runTest(timeout = TIMEOUT) {
        val directory = Files.createTempDirectory("pending-wipe-legacy").toFile()
        val file = File(directory, "pending_cloud_wipe.txt")
        file.writeText("owner-legacy\n777\n")

        val intent = DesktopPendingCloudWipeIntentStore(file).read().singleOrNull()

        assertEquals("owner-legacy", intent?.ownerUid, "an already-accepted request became unreadable")
        assertEquals(777L, intent?.requestedAtMillis)
        directory.deleteRecursively()
    }

    private companion object {
        val TIMEOUT = 60.seconds
    }
}
