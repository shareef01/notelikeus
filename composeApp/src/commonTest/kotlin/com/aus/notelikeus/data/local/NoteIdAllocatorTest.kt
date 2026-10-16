package com.aus.notelikeus.data.local

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteIdAllocatorTest {

    @Test
    fun `an id follows the web rule and stays exact in a JavaScript number`() = runTest {
        val now = 1_792_000_000_000L
        val id = NoteIdAllocator.next(now, Random(1)) { false }

        assertTrue(id in now * 1000 until now * 1000 + 1000, "id $id is outside the web's range")
        assertTrue(id < (1L shl 53), "id $id is not exact in a JavaScript number")
    }

    @Test
    fun `an id already taken on this device is skipped`() = runTest {
        val now = 1_792_000_000_000L
        val first = NoteIdAllocator.next(now, Random(1)) { false }

        val next = NoteIdAllocator.next(now, Random(1)) { it == first }

        assertEquals(first + 1, next)
    }
}
