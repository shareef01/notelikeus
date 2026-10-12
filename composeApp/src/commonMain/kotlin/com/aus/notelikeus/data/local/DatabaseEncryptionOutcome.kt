package com.aus.notelikeus.data.local

/**
 * What the plaintext → encrypted database migration left at the database path, which decides how
 * the database must be opened for this launch.
 */
enum class DatabaseEncryptionOutcome {
    /** The file opens with the passphrase, or there is no file yet: open it encrypted. */
    ENCRYPTED,

    /**
     * A readable plaintext database could not be encrypted this time — a failed export, a copy that
     * would not open, a swap that did not complete. It is still in place with every row, so this
     * launch opens it as plaintext and the next launch tries again (F-10). Quarantining it instead
     * started the app empty over notes that were perfectly readable.
     */
    PLAINTEXT_RETAINED,

    /** The file opened neither with the passphrase nor as plaintext, and was moved aside. */
    QUARANTINED,
}
