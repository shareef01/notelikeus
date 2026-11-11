package com.aus.notelikeus.util

import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.StandardOpenOption
import java.nio.file.StandardWatchEventKinds
import kotlin.concurrent.thread

/**
 * Keeps one copy of the desktop app running per user, and lets a second launch bring it forward.
 *
 * Closing the main window can leave the app running in the system tray so reminders still fire
 * (NL-01). A user who thinks the app is closed launches it again from the Start menu, and without
 * this that second process would open the same encrypted database and run its own sync and
 * reminder timers alongside the first. Instead the second launch asks the first to show its
 * window, and exits before opening anything.
 *
 * The lock is an OS file lock, so a crashed process releases it and never leaves the app unable
 * to start. The activation request is a file the running copy watches; it lives in its own
 * directory so the database's own writes do not wake the watcher.
 */
class SingleInstance private constructor(
    private val directory: File,
    private val channel: FileChannel?,
    private val lock: FileLock?,
) : Closeable {

    /**
     * Calls [onActivate], on a background thread, each time another launch asks this one to come
     * forward. Close the returned handle to stop listening.
     */
    fun listenForActivation(onActivate: () -> Unit): Closeable {
        val watcher = runCatching {
            FileSystems.getDefault().newWatchService().also { service ->
                directory.toPath().register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                )
            }
        }.getOrElse { error ->
            AppLog.warn("SingleInstance", "Could not watch for activation requests", error)
            return Closeable { }
        }
        thread(name = "notelikeus-single-instance", isDaemon = true) {
            try {
                while (true) {
                    val key = watcher.take()
                    val requested = key.pollEvents().any { event ->
                        event.context()?.toString() == ACTIVATION_FILE_NAME
                    }
                    if (requested) onActivate()
                    if (!key.reset()) break
                }
            } catch (_: ClosedWatchServiceException) {
                // Closed through the handle: stop quietly.
            } catch (_: InterruptedException) {
                // The process is going away.
            }
        }
        return Closeable { watcher.close() }
    }

    override fun close() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
    }

    companion object {
        internal const val LOCK_FILE_NAME = "instance.lock"
        internal const val ACTIVATION_FILE_NAME = "activate.request"

        /**
         * Takes the per-user lock in [directory], or — when another copy already holds it — asks
         * that copy to show its window and returns null, and the caller exits.
         *
         * If the lock cannot be taken for any other reason (a read-only profile, a file system
         * without locks), this returns an instance that holds nothing rather than refusing to
         * start: failing to guard against a second copy is far better than failing to open.
         */
        fun acquire(directory: File): SingleInstance? {
            directory.mkdirs()
            val channel = try {
                FileChannel.open(
                    File(directory, LOCK_FILE_NAME).toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
            } catch (error: Exception) {
                AppLog.warn("SingleInstance", "Could not open the instance lock; starting unguarded", error)
                return SingleInstance(directory, channel = null, lock = null)
            }
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // Already held inside this JVM, which also means "another copy is running".
                null
            } catch (error: Exception) {
                AppLog.warn("SingleInstance", "Could not take the instance lock; starting unguarded", error)
                runCatching { channel.close() }
                return SingleInstance(directory, channel = null, lock = null)
            }
            if (lock == null) {
                runCatching { channel.close() }
                requestActivation(directory)
                return null
            }
            return SingleInstance(directory, channel, lock)
        }

        /** Asks the copy that holds the lock in [directory] to bring its window forward. */
        internal fun requestActivation(directory: File) {
            runCatching {
                File(directory, ACTIVATION_FILE_NAME).writeText(System.nanoTime().toString())
            }.onFailure { error ->
                AppLog.warn("SingleInstance", "Could not ask the running copy to show itself", error)
            }
        }
    }
}
