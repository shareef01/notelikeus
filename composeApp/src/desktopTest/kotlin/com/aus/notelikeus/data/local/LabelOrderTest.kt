package com.aus.notelikeus.data.local

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aus.notelikeus.data.local.entity.LabelEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Edit labels lists A to Z whatever the case: SQLite's default order put "Zeta" before "apple". */
class LabelOrderTest {
    private lateinit var tempDir: File
    private lateinit var database: NotelikeusDatabase

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("notelikeus-label-order-test").toFile()
        database = Room.databaseBuilder<NotelikeusDatabase>(name = File(tempDir, "test.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `labels come back A to Z ignoring case`() = runBlocking {
        listOf("Zeta", "apple", "Mango", "banana").forEach { database.labelDao.insertLabel(LabelEntity(name = it)) }

        val expected = listOf("apple", "banana", "Mango", "Zeta")
        assertEquals(expected, database.labelDao.getAllLabelsOnce().map { it.name })
        assertEquals(expected, database.labelDao.getAllLabels().first().map { it.name })
    }
}
