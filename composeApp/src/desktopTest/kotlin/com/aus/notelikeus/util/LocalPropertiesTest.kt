package com.aus.notelikeus.util

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalPropertiesTest {

    @Test
    fun readsNearestFileAndSkipsBlank() {
        val root = Files.createTempDirectory("notelikeus-local-properties").toFile()
        try {
            root.resolve("local.properties").writeText(
                """
                notelikeus.supabaseUrl=https://example.supabase.co
                notelikeus.supabaseAnonKey=
                leftover.oauth=keep-me
                """.trimIndent(),
            )
            val nested = root.resolve("composeApp").apply { mkdirs() }
            assertEquals("https://example.supabase.co", readLocalProperty("notelikeus.supabaseUrl", nested))
            assertNull(readLocalProperty("notelikeus.supabaseAnonKey", nested))
            assertEquals("keep-me", readLocalProperty("leftover.oauth", nested))
            assertNull(readLocalProperty("missing.key", nested))
        } finally {
            root.deleteRecursively()
        }
    }
}
