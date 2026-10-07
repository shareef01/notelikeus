package com.aus.notelikeus.data.sync

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E3A-1 — the coherent-capture primitive.
 *
 * `capture(uid)` takes the uid as a parameter, so a caller that reads the session and then captures
 * does two independent reads. An isolation landing between them pairs the **old account's uid with
 * the new dataset's generation** — a combination generation validation would happily accept, because
 * the generation is current and nothing checks that the uid belongs to it. `captureCoherent` closes
 * that window by re-reading the generation either side of the uid read.
 *
 * This is a plain (non-`runTest`) test on purpose. The interleaving has to happen *inside* the
 * provider callback, which is non-suspending, so it cannot be produced by yielding on a
 * single-threaded test dispatcher — the isolation is run to completion synchronously from within the
 * callback instead. No sleeps, no threads, no timing assumption.
 */
class LocalCommitGateCoherentCaptureTest {

    @Test
    fun `captureCoherent retries when an isolation lands during the uid read`() {
        var providerCalls = 0
        val generationsSeenByProvider = mutableListOf<Long>()

        val token = LocalCommitGate.captureCoherent {
            providerCalls++
            generationsSeenByProvider += LocalCommitGate.currentGeneration()
            if (providerCalls == 1) {
                // The account boundary completes while the "session read" is in progress.
                runBlocking { LocalCommitGate.isolate { } }
            }
            UID_A
        }

        assertEquals(
            2,
            providerCalls,
            "capture did not retry after the generation moved during the uid read",
        )
        assertEquals(
            generationsSeenByProvider[0] + 1,
            generationsSeenByProvider[1],
            "the retry should observe the post-isolation generation",
        )
        assertEquals(
            generationsSeenByProvider[1],
            token.generation,
            "the returned token kept the pre-isolation generation, pairing an old uid with a new dataset",
        )
        assertEquals(UID_A, token.initiatingUid)
        assertEquals(LocalCommitGate.currentGeneration(), token.generation)
    }

    @Test
    fun `captureCoherent does not retry when the uid changes without an isolation`() {
        // Guest -> first account adoption is NOT an account boundary: the generation deliberately
        // stays put, so the token captured moments earlier must remain valid.
        var providerCalls = 0

        val token = LocalCommitGate.captureCoherent {
            providerCalls++
            FIRST_ACCOUNT_UID
        }

        assertEquals(1, providerCalls, "capture retried across a non-isolating uid change")
        assertEquals(LocalCommitGate.currentGeneration(), token.generation)
        assertTrue(token.initiatingUid == FIRST_ACCOUNT_UID)
    }

    private companion object {
        const val UID_A = "uid-A"
        const val FIRST_ACCOUNT_UID = "first-account"
    }
}
