package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.sync.PendingCloudWipeIntent
import com.aus.notelikeus.data.sync.PendingCloudWipeIntentStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The Desktop home of accepted destructive wipe requests — F-8.
 *
 * A small file of its own in the app data directory, beside — and independent of — the settings DataStore
 * that local account isolation clears. One line per owner: the target uid and the request timestamp,
 * tab-separated. No credential.
 *
 * The write is atomic (write a temporary file, then move it into place), so any reader — including a second
 * desktop instance — sees the whole previous set or the whole new one, never a truncated file. Requests are
 * keyed by owner, so one account's accepted deletion is never written or cleared by another account's, and
 * the first version's two-line single-owner format is still read.
 */
class DesktopPendingCloudWipeIntentStore(private val file: File) : PendingCloudWipeIntentStore {

    override suspend fun read(): List<PendingCloudWipeIntent> {
        if (!file.exists()) return emptyList()
        val lines = file.readText().lines().map { it.trim() }.filter { it.isNotEmpty() }
        // The first version wrote exactly two lines: the owner, then the timestamp.
        if (lines.size == 2 && !lines[0].contains(SEPARATOR) && lines[1].toLongOrNull() != null) {
            return listOf(PendingCloudWipeIntent(lines[0], lines[1].toLong()))
        }
        return lines.mapNotNull { line ->
            val uid = line.substringBefore(SEPARATOR, "").trim()
            val timestamp = line.substringAfter(SEPARATOR, "").trim().toLongOrNull()
            if (uid.isEmpty() || timestamp == null) null else PendingCloudWipeIntent(uid, timestamp)
        }
    }

    override suspend fun find(ownerUid: String): PendingCloudWipeIntent? =
        read().firstOrNull { it.ownerUid == ownerUid }

    override suspend fun write(intent: PendingCloudWipeIntent) {
        val existing = read()
        val previous = existing.firstOrNull { it.ownerUid == intent.ownerUid }
        // A repeat request keeps the original timestamp: the same obligation, still outstanding.
        replaceFile(existing.filterNot { it.ownerUid == intent.ownerUid } + (previous ?: intent))
    }

    override suspend fun clear(ownerUid: String) {
        val remaining = read().filterNot { it.ownerUid == ownerUid }
        if (remaining.isEmpty()) {
            if (file.exists() && !file.delete()) {
                error("pending cloud wipe intent could not be cleared")
            }
            return
        }
        replaceFile(remaining)
    }

    private fun replaceFile(intents: List<PendingCloudWipeIntent>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(
            intents.joinToString(separator = "") { "${it.ownerUid}$SEPARATOR${it.requestedAtMillis}\n" },
        )
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private companion object {
        const val SEPARATOR = "\t"
    }
}
