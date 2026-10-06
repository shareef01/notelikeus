package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.remote.OperationRemoteIdentity
import com.aus.notelikeus.data.remote.RemoteIdentityProvider

/**
 * The credential every test lane's captured identity carries unless the lane is *about* identity.
 *
 * One constant is enough there: those lanes assert on generations, ordering and local state, and the
 * engine never hands the token anywhere they inspect. The lanes that do care — `NoteSyncEngineRemoteIdentityF1Test`,
 * the RIDE2E identity suites and the transport-level identity tests — wire the real provider against a
 * real HTTP server and assert on the bearer that arrived.
 */
const val TEST_ACCESS_TOKEN: String = "test-access-token"

/**
 * A [RemoteIdentityProvider] for the lanes whose subject is something other than identity.
 *
 * It reads the owner from the same session the lane already models for the engine's `uidProvider`, and
 * it validates the originating generation exactly as production does — so a lane that stales a token
 * still sees the refusal, and a lane that switches accounts still sees the boundary. Only the
 * credential is a constant, because nothing there asserts on it.
 *
 * Deliberately *not* a default on the engine: `remoteIdentityProvider` is a mandatory constructor
 * parameter with no default, so no production construction can leave identity binding out. This
 * factory exists only in `commonTest`, and the compiler keeps it there.
 */
fun testRemoteIdentityProvider(owner: () -> String?): RemoteIdentityProvider =
    RemoteIdentityProvider(
        accessTokenProvider = { TEST_ACCESS_TOKEN },
        sessionOwnerId = { owner() },
    )

/**
 * The identity a lane's engine operations run under, for the lanes that drive an `internal` helper
 * directly rather than through a public operation.
 *
 * Those helpers now take the identity their caller owns instead of a bare `uid`, so a lane that calls
 * one has to say what it is. The owner is whatever the lane's session says, and the credential is the
 * same constant the rest of that lane uses.
 */
fun testIdentity(owner: String): OperationRemoteIdentity =
    OperationRemoteIdentity(ownerId = owner, accessToken = TEST_ACCESS_TOKEN)
