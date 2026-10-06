package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.repository.LocalCommitResult
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The one-shot remote-mutation grant, exercised at the primitive.
 *
 * The property this has to make executable is an *ordering*: the grant and account isolation are
 * both taken under the same mutex, so exactly one of them can come first. Isolation first means no
 * grant exists and nothing can be started; grant first means that specific mutation was already
 * issued before the boundary and may complete. A grant, not a boolean, is what makes "exactly one"
 * and "for this mutation only" checkable rather than a claim in a comment.
 *
 * Concurrency is real here, not a single-threaded schedule: the parked mutation runs on
 * `Dispatchers.IO` while the test body (or another IO thread) takes the boundary.
 */
class LocalCommitGateRemoteMutationAuthorizationTest {

    @AfterTest
    fun takeTheQuarantineBackDown() {
        // The quarantine is process-wide by design — every local commit and every grant consults it —
        // so a lane that installs one has to take it back down.
        LocalCommitGate.installDatasetAuthorityQuarantine(null)
    }

    // ---- RMS-NSE-9: the grant is one-shot and cannot be reused ----

    @Test
    fun `a fresh grant carries its generation and the one mutation it is for`() = runTest(timeout = TIMEOUT) {
        val token = LocalCommitGate.capture(UID)
        val grant = assertNotNull(
            LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
            "a token from the current generation was refused",
        )

        assertEquals(token.generation, grant.generation, "the grant recorded the wrong generation")
        assertEquals(MUTATION, grant.mutationName, "the grant recorded the wrong mutation")
        assertFalse(grant.isConsumed, "a fresh grant was already consumed")
    }

    @Test
    fun `a grant cannot authorize a second mutation`() = runTest(timeout = TIMEOUT) {
        val token = LocalCommitGate.capture(UID)
        val grant = assertNotNull(LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION))
        val mutations = mutableListOf<String>()

        grant.consumeOnce { mutations += "first" }

        var refused = false
        try {
            grant.consumeOnce { mutations += "second" }
        } catch (expected: IllegalStateException) {
            refused = true
        }

        assertTrue(refused, "reusing a consumed grant did not throw")
        assertEquals(listOf("first"), mutations, "one grant started more than one mutation")
    }

    @Test
    fun `one grant cannot consume or be consumed by another`() = runTest(timeout = TIMEOUT) {
        val tokenA = LocalCommitGate.capture(UID)
        val tokenB = LocalCommitGate.capture(UID)
        val tombstone = assertNotNull(LocalCommitGate.authorizeRemoteMutationStart(tokenA, TOMBSTONE))
        val deletion = assertNotNull(LocalCommitGate.authorizeRemoteMutationStart(tokenB, DELETION))
        val mutations = mutableListOf<String>()

        assertTrue(tombstone !== deletion, "two grants were the same object")
        tombstone.consumeOnce { mutations += tombstone.mutationName }
        assertFalse(deletion.isConsumed, "consuming one grant consumed an unrelated one")
        deletion.consumeOnce { mutations += deletion.mutationName }

        assertEquals(
            listOf(TOMBSTONE, DELETION),
            mutations,
            "a grant ran a mutation other than its own",
        )
    }

    // ---- RMS-NSE-1/2/3: the two orders, and only two ----

    @Test
    fun `isolation first issues no grant and starts nothing`() = runTest(timeout = TIMEOUT) {
        val token = LocalCommitGate.capture(UID)
        LocalCommitGate.isolate { }

        assertNull(
            LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
            "a token from the replaced dataset was still authorized",
        )
    }

    @Test
    fun `authorization first survives a later boundary and still runs`() = runTest(timeout = TIMEOUT) {
        val token = LocalCommitGate.capture(UID)
        val grant = assertNotNull(LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION))

        // The boundary lands after the grant: the mutation was already issued, so it is not cancelled.
        LocalCommitGate.isolate { }

        var ran = false
        grant.consumeOnce { ran = true }

        assertTrue(ran, "a later boundary cancelled an already-issued mutation")
        assertTrue(grant.isConsumed, "the grant was not consumed by its own mutation")
    }

    @Test
    fun `a same-uid boundary is the same refusal as any other`() = runTest(timeout = TIMEOUT) {
        val token = LocalCommitGate.capture(UID)
        LocalCommitGate.isolate { }
        val afterBoundary = LocalCommitGate.capture(UID)

        assertEquals(
            UID,
            afterBoundary.initiatingUid,
            "precondition: the uid is identical across the boundary",
        )
        assertNull(
            LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
            "a uid-only boundary did not refuse the older dataset's token",
        )
        assertNotNull(
            LocalCommitGate.authorizeRemoteMutationStart(afterBoundary, MUTATION),
            "the replacement dataset could not authorize its own mutation",
        )
    }

    // ---- the network never runs under the gate ----

    @Test
    fun `the gate is free while the authorized mutation is outstanding`() = runTest(timeout = TIMEOUT) {
        val token = LocalCommitGate.capture(UID)
        val grant = assertNotNull(LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION))
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val mutation = async(Dispatchers.IO) {
            grant.consumeOnce {
                parked.complete(Unit)
                release.await()
                "mutation finished"
            }
        }
        parked.await()

        assertFalse(
            LocalCommitGate.mutex.isLocked,
            "LocalCommitGate was held across the authorized remote mutation",
        )
        var isolated = false
        withContext(Dispatchers.IO) { LocalCommitGate.isolate { isolated = true } }
        assertTrue(isolated, "account isolation could not complete while the mutation was outstanding")

        release.complete(Unit)
        assertEquals("mutation finished", mutation.await())
    }

    // ---- RMS-NSE-7: cancellation leaves nothing behind ----

    @Test
    fun `cancelling an outstanding mutation holds no lock and leaks no permit`() = runTest(timeout = TIMEOUT) {
        val token = LocalCommitGate.capture(UID)
        val grant = assertNotNull(LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION))
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val mutation = async(Dispatchers.IO) {
            grant.consumeOnce {
                parked.complete(Unit)
                release.await()
            }
        }
        parked.await()

        mutation.cancel()
        release.complete(Unit)

        assertFalse(LocalCommitGate.mutex.isLocked, "cancellation left the gate held")
        assertTrue(grant.isConsumed, "the grant should have been consumed before the cancellation")

        // There is no permit registry to tidy: the grant is a value, so a cancelled mutation leaves
        // nothing registered and cannot block a later boundary or a later authorization.
        var isolated = false
        LocalCommitGate.isolate { isolated = true }
        assertTrue(isolated, "a cancelled mutation left account isolation blocked")
        assertNotNull(
            LocalCommitGate.authorizeRemoteMutationStart(LocalCommitGate.capture(UID), MUTATION),
            "a cancelled mutation left remote authorization permanently blocked",
        )
    }

    // ---- failure keeps the authority consumed ----

    @Test
    fun `a failure inside the mutation propagates and leaves the grant consumed`() = runTest(timeout = TIMEOUT) {
        val token = LocalCommitGate.capture(UID)
        val grant = assertNotNull(LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION))

        var propagated: Throwable? = null
        try {
            grant.consumeOnce { throw TransportBlip() }
        } catch (failure: Throwable) {
            propagated = failure
        }

        assertTrue(propagated is TransportBlip, "the failure was replaced by $propagated")
        assertTrue(grant.isConsumed, "a failed mutation handed its authority back")
    }

    // ---- R16.1: the quarantine is part of the grant decision ----

    /**
     * The window F-6's closure left open at the primitive: once durable dataset authority is
     * `Isolating`, no **new** grant may be minted — even though the local generation has not advanced
     * yet, because the boundary ([LocalCommitGate.isolate], and with it `completeIsolation`) is
     * crossed later than the marker [DatasetEpochAuthority.beginIsolation] persists.
     *
     * Pre-fix this is red: the marker is durable, `generation` is still N, the generation check passes
     * and a grant object is created for a dataset the device has already left.
     */
    @Test
    fun `no grant can be minted while the dataset authority is isolating`() = runTest(timeout = TIMEOUT) {
        val authority = DatasetEpochAuthority(InMemoryDatasetEpochStore())
        LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
        val token = LocalCommitGate.capture(UID)

        assertNotNull(
            LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
            "precondition: on a stable dataset a current token is authorized",
        )
        val generationBefore = LocalCommitGate.currentGeneration()

        authority.beginIsolation()   // the durable marker, and only the marker

        assertTrue(authority.isIsolationIncomplete(), "precondition: the transition is in flight")
        assertEquals(
            generationBefore,
            LocalCommitGate.currentGeneration(),
            "precondition: the marker must not have crossed the boundary yet",
        )
        assertNull(
            LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
            "a grant was minted for a dataset the device has already left",
        )
        // The two primitives must agree: a local commit refuses in this state, so a grant may not be
        // created for the remote call that would follow it.
        assertEquals(
            LocalCommitResult.StaleGeneration,
            LocalCommitGate.commit(token) { },
            "commit refuses while the grant object is still created",
        )
    }

    @Test
    fun `a stable dataset authorizes its token even with the quarantine installed`() = runTest(timeout = TIMEOUT) {
        val authority = DatasetEpochAuthority(InMemoryDatasetEpochStore())
        LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
        val token = LocalCommitGate.capture(UID)

        assertFalse(authority.isIsolationIncomplete(), "precondition: no transition is in flight")
        assertNotNull(
            LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
            "an installed-but-clear quarantine refused a stable dataset's token",
        )
    }

    @Test
    fun `a grant created before the transition may still run after it completes`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryDatasetEpochStore()
        val authority = DatasetEpochAuthority(store)
        LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
        val token = LocalCommitGate.capture(UID)
        val grant = assertNotNull(
            LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
            "precondition: on a stable dataset the current token is authorized",
        )

        // The transition starts and finishes: marker, then the boundary, then the completion.
        authority.beginIsolation()
        assertNull(
            LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
            "a new grant was minted while the isolation was in flight",
        )
        LocalCommitGate.isolate { authority.completeIsolation() }

        // F-10, unchanged: the grant exists, so its one mutation was already issued and may run. The
        // quarantine is consulted when authority is *created*, never when it is spent — retroactively
        // invalidating a grant would cancel a request the boundary is documented to allow.
        var ran = false
        grant.consumeOnce { ran = true }
        assertTrue(ran, "an already-issued grant was cancelled by a transition that followed it")
        assertTrue(grant.isConsumed, "the grant was not consumed by its own mutation")
    }

    @Test
    fun `recovery re-enables authorization for the replacement dataset`() = runTest(timeout = TIMEOUT) {
        val authority = DatasetEpochAuthority(InMemoryDatasetEpochStore())
        LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
        val oldToken = LocalCommitGate.capture(UID)

        authority.beginIsolation()
        assertNull(
            LocalCommitGate.authorizeRemoteMutationStart(oldToken, MUTATION),
            "an in-flight isolation minted a grant",
        )

        // The real recovery sequence: the boundary is crossed and the transition completed inside it.
        LocalCommitGate.isolate { authority.completeIsolation() }

        assertFalse(authority.isIsolationIncomplete(), "precondition: the recovery completed")
        assertNull(
            LocalCommitGate.authorizeRemoteMutationStart(oldToken, MUTATION),
            "the replaced dataset's token was authorized after the boundary",
        )
        assertNotNull(
            LocalCommitGate.authorizeRemoteMutationStart(LocalCommitGate.capture(UID), MUTATION),
            "recovery left remote-start authority permanently disabled",
        )
    }

    @Test
    fun `the grant decision performs no durable IO while the gate is held`() = runTest(timeout = TIMEOUT) {
        val store = InMemoryDatasetEpochStore()
        val authority = DatasetEpochAuthority(store)
        LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
        authority.beginIsolation()
        val token = LocalCommitGate.capture(UID)
        assertEquals(
            LocalCommitGate.currentGeneration(),
            token.generation,
            "precondition: the marker has not crossed the boundary, so this is the quarantine refusing",
        )

        // From here, any durable read or write performed while LocalCommitGate is held fails the lane
        // outright — so the refusal has to come from the in-memory quarantine the grant reuses.
        store.gateLock = LocalCommitGate.mutex
        store.failUnderGate = true

        assertNull(
            LocalCommitGate.authorizeRemoteMutationStart(token, MUTATION),
            "an unfinished isolation must refuse the start",
        )
        assertFalse(
            store.touchedUnderGate,
            "the grant decision touched the durable store while the gate was held",
        )
    }

    @Test
    fun `the grant decision consults nothing but the gate's own state`() {
        // The declaration through its closing brace: the lock is taken in an expression body, so the
        // region — not just the block — is what has to be inspected.
        val region = methodRegion(gateSource(), GRANT)
        assertTrue(region.isNotEmpty(), "precondition: the grant was located")

        assertEquals(
            1,
            Regex("""mutex\.withLock""").findAll(region).count(),
            "the grant takes a lock other than its own, or none at all",
        )
        assertTrue(
            region.contains("datasetAuthorityUnresolved()"),
            "the grant does not reuse the quarantine predicate local commits already consult",
        )
        listOf(
            "awaitAuthority",
            "currentEpochOrNull",
            "authorityOrNull",
            "currentPending",
            "DatasetEpochAuthority",
            "DatasetEpochStore",
            "epochAuthority",
            "store",
            "withContext",
            "captureCoherent",
            "session",
        ).forEach { forbidden ->
            assertFalse(region.contains(forbidden), "the grant decision consults $forbidden")
        }
    }

    private fun gateSource(): String {
        val candidates = listOf(
            File("src/commonMain/kotlin/com/aus/notelikeus/data/sync/LocalCommitGate.kt"),
            File("composeApp/src/commonMain/kotlin/com/aus/notelikeus/data/sync/LocalCommitGate.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("could not read LocalCommitGate.kt from ${candidates.joinToString { it.absolutePath }}")
        return file.readText()
    }

    /** One suspend member, from its declaration through its matching closing brace. */
    private fun methodRegion(source: String, name: String): String {
        val declaration = Regex("""suspend fun $name\(""").find(source) ?: return ""
        val end = parameterListEnd(source, name)
        if (end < 0) return ""
        val open = source.indexOf('{', end)
        if (open < 0) return ""
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(declaration.range.first, index + 1)
                }
            }
        }
        return source.substring(declaration.range.first)
    }

    private fun parameterListEnd(source: String, name: String): Int {
        val declaration = Regex("""suspend fun $name\(""").find(source) ?: return -1
        var depth = 0
        for (index in declaration.range.last until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private class TransportBlip : RuntimeException("transport down")

    private companion object {
        const val UID = "same-user"
        const val GRANT = "authorizeRemoteMutationStart"
        const val MUTATION = "writeTombstone"
        const val TOMBSTONE = "writeTombstone"
        const val DELETION = "deleteNote"
        val TIMEOUT = 60.seconds
    }
}
