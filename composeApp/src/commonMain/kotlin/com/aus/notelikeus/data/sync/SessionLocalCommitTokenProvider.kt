package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.remote.CloudSessionManager
import com.aus.notelikeus.domain.repository.LocalCommitToken
import com.aus.notelikeus.domain.repository.LocalCommitTokenProvider

/**
 * Captures the token from the live session on the infrastructure side, so the editor never has to
 * know about [CloudSessionManager] or [LocalCommitGate].
 *
 * The uid is read *inside* [LocalCommitGate.captureCoherent], which brackets that read with two
 * generation loads and retries if an account boundary lands in between. Reading the uid and then
 * calling capture(uid) would be two independent reads and could pair the previous account's uid
 * with the new dataset's generation -- a pairing generation validation would accept.
 */
class SessionLocalCommitTokenProvider(
    private val sessionManager: CloudSessionManager,
) : LocalCommitTokenProvider {

    override fun capture(): LocalCommitToken =
        LocalCommitGate.captureCoherent {
            sessionManager.getCurrentAccount().userId
        }
}
