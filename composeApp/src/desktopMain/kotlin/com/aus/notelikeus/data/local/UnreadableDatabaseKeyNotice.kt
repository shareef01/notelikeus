package com.aus.notelikeus.data.local

import java.io.File

/**
 * What the user is told when the notes database key cannot be unlocked at startup (FU-2).
 *
 * The usual cause is Windows no longer being able to decrypt the key for this account: an
 * administrator reset the password, or the user profile was copied from another PC. Nothing is
 * lost at that point — the key file and the encrypted notes are both still on disk — so the message
 * says exactly that, where they are, and what the user can do, rather than offering to delete
 * anything.
 */
object UnreadableDatabaseKeyNotice {
    const val TITLE = "Notelikeus can't open your notes"

    fun message(dataDirectory: File, keyDirectory: File): String =
        """
        Notelikeus can't unlock its notes database for this Windows account.

        This usually happens when Windows can no longer decrypt the app's key: for example after an administrator reset your Windows password, or if your user profile was copied from another PC.

        Nothing has been deleted. Your notes and the key are still here:
          ${dataDirectory.absolutePath}
          ${keyDirectory.absolutePath}

        If your password was reset, signing in to Windows with your previous password may restore access. If your notes are synced, you can also move both folders somewhere else and restart Notelikeus to start with an empty library, then sign in to download your notes again.
        """.trimIndent()
}
