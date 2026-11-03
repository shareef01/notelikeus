package com.aus.notelikeus.ui.main

/**
 * A destructive action the server refused until the user confirms it is them (AUD-005).
 *
 * Deleting the account or wiping its cloud data needs a session that signed in within the last ten
 * minutes. When it did not, nothing was deleted: the app asks for a new emailed code for the same
 * account, which starts a fresh session, and then runs the action again.
 */
enum class IdentityCheck { DeleteAccount, WipeCloudData }
