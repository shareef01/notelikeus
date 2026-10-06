package com.aus.notelikeus.data.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The precise semantics of a remote-mutation grant across an account boundary — an audit pin, not a
 * behaviour change.
 *
 * `LocalCommitGate` orders the *grant* against isolation, and its own KDoc already says what that
 * does and does not cover: the grant is atomic, the transport call is not, and "an operation whose
 * grant precedes the boundary is defined as already issued and is allowed to complete, the same
 * asymmetry as work already on the wire."
 *
 * That is a claim about two separate events — the authorization decision and the physical request —
 * and the strongest way to test it is to take the boundary in its *strict* form: resolve the whole
 * isolation, generation N -> N+1, and only then let the granted block run. These tests do exactly
 * that, with no parking and no scheduler help, so the outcome cannot be an artifact of interleaving.
 *
 * They exist so the limitation is pinned rather than asserted in prose: if a later change makes the
 * gate refuse an already-issued grant, ISS-1 fails and says so.
 */
class LocalCommitGateIssuanceSemanticsTest {

    @Test
    fun `ISS-1 a grant issued before the boundary may still start its request afterwards`() =
        runTest(timeout = TIMEOUT) {
            val generationBefore = LocalCommitGate.currentGeneration()
            val token = LocalCommitGate.capture(UID)
            val grant = LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION)
            assertTrue(grant != null, "a current-generation token was refused a grant")

            // The boundary resolves completely first: the whole isolation block runs and the
            // generation has already moved on before the granted request is allowed to start.
            LocalCommitGate.isolate { }
            assertEquals(
                generationBefore + 1,
                LocalCommitGate.currentGeneration(),
                "isolate did not advance the generation, so this is not the post-boundary case",
            )

            var requestStarted = false
            grant.consumeOnce { requestStarted = true }

            assertTrue(
                requestStarted,
                "the gate now re-checks the generation at consume time; the documented " +
                    "'already issued' semantics have changed and the D11 write-up must be revisited",
            )
        }

    @Test
    fun `ISS-2 a boundary that wins the race yields no grant at all`() = runTest(timeout = TIMEOUT) {
        val token = LocalCommitGate.capture(UID)
        LocalCommitGate.isolate { }

        val grant = LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION)

        assertNull(grant, "a stale token was issued a grant, so isolation-first still lets work through")
    }

    private companion object {
        const val UID = "same-user"
        const val MUTATION = "audit.issuance-probe"

        val TIMEOUT = 60.seconds
    }
}
