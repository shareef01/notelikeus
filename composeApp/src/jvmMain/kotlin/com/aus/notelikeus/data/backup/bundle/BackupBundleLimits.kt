package com.aus.notelikeus.data.backup.bundle

/**
 * Limits shared with `web/src/lib/backup/bundle/backupBundle.ts` and `zip.ts`.
 * Keep these numbers identical — they are part of the cross-client contract.
 */
object BackupBundleLimits {
    const val BUNDLE_MANIFEST_ENTRY = "manifest.json"
    const val BUNDLE_MEDIA_PREFIX = "media/"
    const val BUNDLE_FILE_EXTENSION = ".nlkbak"

    /** Whole-file ceiling before anything is parsed. */
    const val MAX_BUNDLE_FILE_BYTES = 256L * 1024 * 1024

    /** Per-attachment ceiling, matching the Worker's upload cap. */
    const val MAX_BUNDLE_ATTACHMENT_BYTES = 10L * 1024 * 1024

    /** More attachments than any real library has. */
    const val MAX_BUNDLE_ATTACHMENTS = 5_000

    /**
     * Manifest entry ceiling: embedded v3 backup (10 MiB) plus a modest attachment index.
     */
    const val MAX_BUNDLE_MANIFEST_BYTES = 10L * 1024 * 1024 + 2L * 1024 * 1024

    const val MAX_ZIP_ENTRIES = 10_000
    const val MAX_ZIP_ENTRY_BYTES = 32L * 1024 * 1024
    const val MAX_ZIP_TOTAL_BYTES = 512L * 1024 * 1024

    val ATTACHMENT_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")
}
