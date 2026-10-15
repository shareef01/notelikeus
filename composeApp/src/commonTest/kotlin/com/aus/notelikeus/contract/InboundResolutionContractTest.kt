package com.aus.notelikeus.contract

import com.aus.notelikeus.data.sync.ConflictResolution
import com.aus.notelikeus.data.sync.ConflictResolution.InboundAction
import com.aus.notelikeus.data.sync.FakeCloudNoteTransport
import com.aus.notelikeus.data.sync.FakeLabelDao
import com.aus.notelikeus.data.sync.FakeNoteDao
import com.aus.notelikeus.data.sync.FakeNoteSyncStateStore
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.data.sync.testRemoteIdentityProvider
import com.aus.notelikeus.domain.model.Note
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `contracts/sync/inbound-resolution.json`, asserted against the decision the download actually uses.
 * The web client asserts the same file in `inboundResolutionContract.test.ts`.
 */
class InboundResolutionContractTest {

    private val engine = NoteSyncEngine(
        transport = FakeCloudNoteTransport(),
        remoteIdentityProvider = testRemoteIdentityProvider { "uid" },
        noteDao = FakeNoteDao(),
        labelDao = FakeLabelDao(),
        syncStateStore = FakeNoteSyncStateStore(),
        uidProvider = { Result.success("uid") },
    )

    private fun JsonElement.side(): Note? {
        if (this is JsonNull) return null
        val o = jsonObject
        return Note(
            id = 1L,
            title = o.getValue("title").jsonPrimitive.content,
            content = o.getValue("content").jsonPrimitive.content,
            timestamp = o.getValue("timestamp").jsonPrimitive.long,
            color = 0,
            serverUpdatedAt = o.getValue("serverUpdatedAt").jsonPrimitive.longOrNull,
        )
    }

    private fun outcome(case: JsonObject): String {
        val local = case.getValue("local").side()
        val synced = case.getValue("synced").jsonPrimitive.longOrNull
        val cloud = case.getValue("cloud").side()
        if (cloud == null) {
            // The download's tombstone purge and vanished-id branch both ask exactly this.
            val edited = local != null && ConflictResolution.isLocallyEdited(local, synced)
            return if (edited) "fork-then-delete" else "delete"
        }
        val cloudWins = local == null || engine.cloudWinsConflict(
            cloud.serverUpdatedAt,
            local.serverUpdatedAt,
            cloud.timestamp,
            local.timestamp,
        )
        return when (ConflictResolution.decide(local, synced, cloud, cloudWins)) {
            InboundAction.ACCEPT -> "accept"
            InboundAction.KEEP_LOCAL -> "keep-local"
            InboundAction.FORK -> "fork"
        }
    }

    @Test
    fun `every case resolves as the contract says`() {
        val cases = Json.parseToJsonElement(ContractFixtures.read("sync/inbound-resolution.json"))
            .jsonObject.getValue("cases").jsonArray
        val mismatches = cases.map { it.jsonObject }.mapNotNull { case ->
            val expected = case.getValue("expected").jsonPrimitive.content
            val actual = outcome(case)
            if (actual == expected) null else "${case.getValue("name").jsonPrimitive.content}: expected $expected, got $actual"
        }
        assertEquals(emptyList(), mismatches)
        assertEquals(13, cases.size, "a case was added or dropped without updating both clients")
    }
}
