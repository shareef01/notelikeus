package com.aus.notelikeus.ui.editor

import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.domain.repository.LocalCommitToken
import com.aus.notelikeus.domain.repository.LocalCommitTokenProvider

/**
 * Deterministic [LocalCommitTokenProvider] for editor tests. Uses the real gate so a test can drive
 * generation changes through `LocalCommitGate.isolate` and observe a real stale outcome.
 */
class FakeLocalCommitTokenProvider(
    private val uid: String? = "test-uid",
) : LocalCommitTokenProvider {

    var captureCount = 0
        private set

    /** The exact token most recently handed out, for comparing against what was committed. */
    var lastToken: LocalCommitToken? = null
        private set

    override fun capture(): LocalCommitToken {
        captureCount++
        return LocalCommitGate.capture(uid).also {
            lastToken = it
        }
    }
}
