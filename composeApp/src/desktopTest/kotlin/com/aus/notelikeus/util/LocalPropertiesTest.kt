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
                notelikeus.remoteBackend=supabase
                notelikeus.supabaseUrl=
                leftover.oauth=keep-me
                """.trimIndent(),
            )
            val nested = root.resolve("composeApp").apply { mkdirs() }
            assertEquals("supabase", readLocalProperty("notelikeus.remoteBackend", nested))
            assertNull(readLocalProperty("notelikeus.supabaseUrl", nested))
            assertEquals("keep-me", readLocalProperty("leftover.oauth", nested))
            assertNull(readLocalProperty("missing.key", nested))
        } finally {
            root.deleteRecursively()
        }
    }
}
