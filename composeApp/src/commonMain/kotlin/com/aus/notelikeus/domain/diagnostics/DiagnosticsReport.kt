package com.aus.notelikeus.domain.diagnostics

/**
 * A privacy-preserving snapshot of sync state, for troubleshooting.
 *
 * The problems this exists for — "my note did not appear on my other device", "it says pending
 * forever", "the images are gone" — are questions about *counts and cursors*, not about content.
 * So the report carries only counts and cursors, and [assertNoSensitiveValues] is what keeps it
 * that way: it runs before a report can be shown or copied, so a field added later cannot quietly
 * start leaking.
 *
 * Deliberately absent, and enforced: note titles and bodies, checklist and label text, attachment
 * bytes and paths, access and refresh tokens, raw JWTs, email addresses, OAuth profile data, and
 * the account id in unredacted form.
 *
 * The web client's counterpart is `web/src/lib/diagnostics/diagnosticsReport.ts`, and the two
 * report the same fields under the same names so one troubleshooting guide covers both.
 */
data class DiagnosticsReport(
    val schemaVersion: Int = SCHEMA_VERSION,
    val generatedAt: Long,
    val appVersion: String,
    val platform: String,
    val storage: Storage,
    val account: Account,
    val notes: Notes,
    val sync: Sync,
    val attachments: Attachments,
) {
    data class Storage(
        val kind: String,
        val schemaVersion: Int,
        val encryptedAtRest: Boolean,
    )

    data class Account(
        val state: AccountState,
        /** A stable, non-reversible tag. Never the account id itself. */
        val ownerTag: String,
    )

    enum class AccountState { GUEST, SIGNED_IN, SIGNED_OUT }

    data class Notes(
        val total: Int,
        val active: Int,
        val archived: Int,
        val trashed: Int,
        val pinned: Int,
        val withReminder: Int,
        val withAttachments: Int,
    )

    data class Sync(
        val knownCloudIdCount: Int,
        val pendingMutationCount: Int,
        val tombstoneCount: Int,
        val pendingRestoreCount: Int,
        val lastReconciledAt: Long,
        val lastErrorCategory: SyncErrorCategory,
    )

    data class Attachments(
        val stagedCount: Int,
        val stagedBytes: Long,
        val pendingUploadCount: Int,
        val unresolvedCleanupCount: Int,
    )

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * A stable code for a sync failure, rather than its message.
 *
 * The message is where content leaks: a revision conflict from `apply_note_change` embeds the
 * remote note's *title*, and a storage failure can carry an object key. The category is what a
 * maintainer can act on anyway.
 */
enum class SyncErrorCategory {
    NONE,
    OFFLINE,
    AUTH,
    PERMISSION,
    CONFLICT,
    EMPTY_CLOUD_REFUSED,
    WRONG_ACCOUNT,
    STORAGE,
    UPSTREAM,
    UNKNOWN,
}

fun categorizeSyncError(error: Throwable?): SyncErrorCategory {
    if (error == null) return SyncErrorCategory.NONE
    // Typed first: these are this project's own exceptions and say exactly what happened.
    when (error) {
        is com.aus.notelikeus.data.sync.SuspectEmptyCloudException ->
            return SyncErrorCategory.EMPTY_CLOUD_REFUSED
        is com.aus.notelikeus.data.sync.WrongAccountSyncException ->
            return SyncErrorCategory.WRONG_ACCOUNT
    }
    val message = error.message.orEmpty().lowercase()
    return when {
        "401" in message || "jwt" in message || "session" in message -> SyncErrorCategory.AUTH
        "403" in message || "row-level security" in message || "permission" in message ->
            SyncErrorCategory.PERMISSION
        "conflict" in message -> SyncErrorCategory.CONFLICT
        "sqlite" in message || "disk" in message || "database" in message ->
            SyncErrorCategory.STORAGE
        "unreachable" in message || "timeout" in message || "host" in message ||
            "connect" in message || "network" in message -> SyncErrorCategory.OFFLINE
        "502" in message || "503" in message || "upstream" in message -> SyncErrorCategory.UPSTREAM
        else -> SyncErrorCategory.UNKNOWN
    }
}

/**
 * A short, stable, non-reversible tag for an account id.
 *
 * Enough to tell "these two reports are the same account" and "this one is a different account"
 * — the only question troubleshooting actually needs — without carrying a value that identifies
 * anyone or that could be replayed anywhere. Matches the web client's `ownerTag`, including the
 * FNV-1a constants, so the same account produces the same tag on every platform.
 */
fun ownerTag(ownerId: String?): String {
    if (ownerId.isNullOrEmpty()) return "none"
    if (ownerId == GUEST_OWNER_TAG_INPUT) return "guest"
    var hash = 0x811c9dc5u
    for (char in ownerId) {
        hash = hash xor char.code.toUInt()
        hash *= 0x01000193u
    }
    return "acct-" + hash.toString(16).padStart(8, '0')
}

private const val GUEST_OWNER_TAG_INPUT = "__guest__"

class DiagnosticsLeakException(message: String) : Exception(message)

/**
 * Every substring a rendered report must never contain, lowercase.
 *
 * Checked against the *rendered text* rather than field by field, so a field added later is
 * covered without anyone remembering to extend a list of property names.
 */
private val FORBIDDEN_SUBSTRINGS = listOf(
    "access_token", "refresh_token", "accesstoken", "refreshtoken",
    "id_token", "idtoken", "apikey", "api_key", "authorization", "bearer ",
    "password", "passphrase", "secret", "private_key", "privatekey",
    // An email address, an OAuth profile, or an object key with an account in it.
    "@",
    // Structural markers for storage locations, caught by prefix rather than only by the
    // identifier inside them.
    "owners/", "pending:", "r2:", "pending-attachments/",
)

private val JWT_PATTERN = Regex("""eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.""")
private val UUID_PATTERN =
    Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

/**
 * Throws if [text] carries anything a diagnostics report must not.
 *
 * A hard failure rather than a scrub: silently removing a leaked value would leave the code that
 * put it there in place, and the next value it adds would be the one nobody checks.
 */
fun assertNoSensitiveValues(text: String) {
    val lowered = text.lowercase()
    for (needle in FORBIDDEN_SUBSTRINGS) {
        if (needle in lowered) {
            throw DiagnosticsLeakException("Diagnostics report contains \"$needle\"")
        }
    }
    if (JWT_PATTERN.containsMatchIn(text)) {
        throw DiagnosticsLeakException("Diagnostics report contains what looks like a JWT")
    }
    if (UUID_PATTERN.containsMatchIn(text)) {
        throw DiagnosticsLeakException("Diagnostics report contains a raw identifier")
    }
}

/**
 * Renders [report] for the clipboard, after checking it is safe to hand over.
 *
 * Plain `key: value` lines rather than JSON: this is read by a person pasting it into an issue,
 * and the field names match the web client's so one troubleshooting guide covers both.
 */
fun formatDiagnosticsReport(report: DiagnosticsReport): String {
    val text = buildString {
        appendLine("notelikeus diagnostics (schema ${report.schemaVersion})")
        appendLine("generatedAt: ${report.generatedAt}")
        appendLine("appVersion: ${report.appVersion}")
        appendLine("platform: ${report.platform}")
        appendLine("storage.kind: ${report.storage.kind}")
        appendLine("storage.schemaVersion: ${report.storage.schemaVersion}")
        appendLine("storage.encryptedAtRest: ${report.storage.encryptedAtRest}")
        appendLine("account.state: ${report.account.state.name.lowercase()}")
        appendLine("account.ownerTag: ${report.account.ownerTag}")
        appendLine("notes.total: ${report.notes.total}")
        appendLine("notes.active: ${report.notes.active}")
        appendLine("notes.archived: ${report.notes.archived}")
        appendLine("notes.trashed: ${report.notes.trashed}")
        appendLine("notes.pinned: ${report.notes.pinned}")
        appendLine("notes.withReminder: ${report.notes.withReminder}")
        appendLine("notes.withAttachments: ${report.notes.withAttachments}")
        appendLine("sync.knownCloudIdCount: ${report.sync.knownCloudIdCount}")
        appendLine("sync.pendingMutationCount: ${report.sync.pendingMutationCount}")
        appendLine("sync.tombstoneCount: ${report.sync.tombstoneCount}")
        appendLine("sync.pendingRestoreCount: ${report.sync.pendingRestoreCount}")
        appendLine("sync.lastReconciledAt: ${report.sync.lastReconciledAt}")
        appendLine("sync.lastErrorCategory: ${report.sync.lastErrorCategory.name.lowercase()}")
        appendLine("attachments.stagedCount: ${report.attachments.stagedCount}")
        appendLine("attachments.stagedBytes: ${report.attachments.stagedBytes}")
        appendLine("attachments.pendingUploadCount: ${report.attachments.pendingUploadCount}")
        appendLine("attachments.unresolvedCleanupCount: ${report.attachments.unresolvedCleanupCount}")
    }
    assertNoSensitiveValues(text)
    return text
}
