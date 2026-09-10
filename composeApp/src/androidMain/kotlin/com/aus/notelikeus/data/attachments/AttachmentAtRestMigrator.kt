package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.util.AppLog
import java.io.File

/**
 * One-shot migration of plaintext attachment files into sealed blobs.
 *
 * Each file is either left sealed, replaced atomically with ciphertext, or moved into a
 * quarantine sibling directory — never truncated mid-write. Safe to run on every cold start:
 * already-sealed files are skipped after a cheap magic check.
 */
object AttachmentAtRestMigrator {
    private const val TAG = "AttachmentMigrate"
    private const val QUARANTINE_DIR = "attachments-quarantine"
    private const val STAGING_QUARANTINE_DIR = "pending-attachments-quarantine"
    private const val TEMP_SUFFIX = ".encrypt-tmp"
    private const val BACKUP_SUFFIX = ".pre-encrypt"

    fun migrateAttachmentsRoot(attachmentsDir: File, protector: AttachmentBytesProtector) {
        if (protector === NoopAttachmentBytesProtector) return
        migrateDirectory(
            root = attachmentsDir,
            quarantineRoot = File(attachmentsDir.parentFile, QUARANTINE_DIR),
            protector = protector,
            aadFor = { file -> file.name.toByteArray(Charsets.UTF_8) },
            include = { file ->
                !file.name.endsWith(TEMP_SUFFIX) &&
                    !file.name.endsWith(BACKUP_SUFFIX) &&
                    !file.name.startsWith(".")
            },
        )
    }

    fun migratePendingStagingRoot(stagingRoot: File, protector: AttachmentBytesProtector) {
        if (protector === NoopAttachmentBytesProtector) return
        if (!stagingRoot.exists()) return
        migrateDirectory(
            root = stagingRoot,
            quarantineRoot = File(stagingRoot.parentFile, STAGING_QUARANTINE_DIR),
            protector = protector,
            aadFor = { file ->
                // Layout: <root>/<ownerId>/<attachmentId>.bin
                val owner = file.parentFile?.name.orEmpty()
                val id = file.name.removeSuffix(".bin")
                "$owner/$id".toByteArray(Charsets.UTF_8)
            },
            include = { file ->
                file.name.endsWith(".bin") &&
                    !file.name.endsWith(TEMP_SUFFIX) &&
                    !file.name.endsWith(BACKUP_SUFFIX)
            },
        )
    }

    private fun migrateDirectory(
        root: File,
        quarantineRoot: File,
        protector: AttachmentBytesProtector,
        aadFor: (File) -> ByteArray,
        include: (File) -> Boolean,
    ) {
        if (!root.exists()) return
        val files = root.walkTopDown()
            .filter { it.isFile && include(it) }
            .toList()
        for (file in files) {
            migrateOne(file, quarantineRoot, protector, aadFor(file))
        }
    }

    private fun migrateOne(
        file: File,
        quarantineRoot: File,
        protector: AttachmentBytesProtector,
        aad: ByteArray,
    ) {
        val raw = runCatching { file.readBytes() }.getOrElse {
            AppLog.warn(TAG, "Could not read attachment for migration: ${file.name}", it)
            quarantine(file, quarantineRoot)
            return
        }
        if (protector.looksSealed(raw)) return

        val sealed = try {
            protector.seal(raw, aad)
        } catch (error: Exception) {
            AppLog.warn(TAG, "Could not seal attachment for migration: ${file.name}", error)
            quarantine(file, quarantineRoot)
            return
        }

        val opened = protector.open(sealed, aad)
        if (opened == null || !opened.contentEquals(raw)) {
            AppLog.warn(TAG, "Sealed attachment failed round-trip; quarantining ${file.name}")
            quarantine(file, quarantineRoot)
            return
        }

        val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
        val backup = File(file.parentFile, file.name + BACKUP_SUFFIX)
        try {
            temp.writeBytes(sealed)
            if (backup.exists()) backup.delete()
            if (!file.renameTo(backup)) {
                AppLog.warn(TAG, "Could not move aside plaintext attachment ${file.name}")
                temp.delete()
                quarantine(file, quarantineRoot)
                return
            }
            if (!temp.renameTo(file)) {
                AppLog.warn(TAG, "Could not publish sealed attachment ${file.name}; restoring")
                backup.renameTo(file)
                temp.delete()
                return
            }
            backup.delete()
        } catch (error: Exception) {
            AppLog.warn(TAG, "Migration write failed for ${file.name}", error)
            runCatching { if (!file.exists() && backup.exists()) backup.renameTo(file) }
            runCatching { temp.delete() }
            if (file.exists() && !protector.looksSealed(runCatching { file.readBytes() }.getOrNull() ?: ByteArray(0))) {
                quarantine(file, quarantineRoot)
            }
        }
    }

    private fun quarantine(file: File, quarantineRoot: File) {
        runCatching {
            quarantineRoot.mkdirs()
            val dest = File(quarantineRoot, "${file.name}.${System.currentTimeMillis()}")
            if (!file.renameTo(dest)) {
                file.copyTo(dest, overwrite = true)
                file.delete()
            }
        }.onFailure {
            AppLog.warn(TAG, "Could not quarantine ${file.name}", it)
        }
    }
}
