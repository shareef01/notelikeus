package com.aus.notelikeus.data.remote

/**
 * The immutable account identity a remote attachment operation was initiated under.
 *
 * [R2AttachmentBlobTransport] used to resolve the access token and the owner id **per remote
 * call** from the live session, so a delayed upload or delete that ran after an account switch
 * executed as the new account: the object went into the new account's namespace, or a delete aimed
 * at a colliding `(noteId, attachmentId)` removed the new account's object. Snapshotting the
 * identity once, where the operation starts, and passing it explicitly is what makes a remote
 * attachment operation's account immutable for its whole duration.
 *
 * **Never persisted, never logged, never interpolated into an exception message.** [toString] is
 * deliberately redacted so a stray log line, crash report or debug interpolation cannot leak the
 * bearer token it carries.
 */
data class AttachmentRemoteContext(
    val ownerId: String,
    val accessToken: String,
) {
    override fun toString(): String =
        "AttachmentRemoteContext(ownerId=$ownerId, accessToken=<redacted>)"
}
