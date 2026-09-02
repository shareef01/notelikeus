package com.aus.notelikeus.data.remote

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentObjectKeyTest {

    @Test
    fun buildsOwnerScopedKeys() {
        val ownerId = "11111111-2222-4333-8444-555555555555"
        val key = AttachmentObjectKey.build(ownerId, "note-1", "att-1")
        assertTrue(AttachmentObjectKey.isForOwner(key, ownerId))
        assertFalse(AttachmentObjectKey.isForOwner(key, "other-owner"))
    }
}
