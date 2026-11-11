package com.aus.notelikeus.util

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NL-01: with the app able to stay in the tray, a second launch must hand over to the running copy
 * rather than open the same database beside it.
 */
class SingleInstanceTest {

    private val directory: File = Files.createTempDirectory("notelikeus-instance-test").toFile()
    private val opened = mutableListOf<AutoCloseable>()

    @AfterTest
    fun cleanUp() {
        opened.asReversed().forEach { runCatching { it.close() } }
        directory.deleteRecursively()
    }

    @Test
    fun firstLaunchTakesTheLock() {
        val first = SingleInstance.acquire(directory)
        assertNotNull(first).also { opened += it }
    }

    @Test
    fun secondLaunchIsRefusedWhileTheFirstRuns() {
        assertNotNull(SingleInstance.acquire(directory)).also { opened += it }
        assertNull(SingleInstance.acquire(directory))
    }

    @Test
    fun lockIsFreeAgainOnceTheFirstCopyCloses() {
        val first = assertNotNull(SingleInstance.acquire(directory))
        first.close()

        assertNotNull(SingleInstance.acquire(directory)).also { opened += it }
    }

    @Test
    fun secondLaunchAsksTheRunningCopyToShowItself() {
        val first = assertNotNull(SingleInstance.acquire(directory)).also { opened += it }
        val activated = CountDownLatch(1)
        first.listenForActivation { activated.countDown() }.also { opened += it }

        assertNull(SingleInstance.acquire(directory))

        assertTrue(activated.await(10, TimeUnit.SECONDS), "the running copy was never asked to show itself")
    }

    @Test
    fun otherFilesInTheDirectoryDoNotWakeTheRunningCopy() {
        val first = assertNotNull(SingleInstance.acquire(directory)).also { opened += it }
        val activated = CountDownLatch(1)
        first.listenForActivation { activated.countDown() }.also { opened += it }

        File(directory, "something-else.txt").writeText("not a request")

        assertTrue(!activated.await(1, TimeUnit.SECONDS), "an unrelated file counted as an activation request")
    }
}
