package com.aus.notelikeus.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopSqliteFlagsTest {
    @Test
    fun parsesTruthyPropertyValues() {
        val previous = System.getProperty(DesktopSqliteFlags.PROPERTY)
        try {
            System.setProperty(DesktopSqliteFlags.PROPERTY, "true")
            assertTrue(DesktopSqliteFlags.useJdbcSqlite())
            System.setProperty(DesktopSqliteFlags.PROPERTY, "1")
            assertTrue(DesktopSqliteFlags.useJdbcSqlite())
            System.setProperty(DesktopSqliteFlags.PROPERTY, "false")
            assertFalse(DesktopSqliteFlags.useJdbcSqlite())
        } finally {
            if (previous == null) {
                System.clearProperty(DesktopSqliteFlags.PROPERTY)
            } else {
                System.setProperty(DesktopSqliteFlags.PROPERTY, previous)
            }
        }
    }

    @Test
    fun defaultFollowsWindowsHostWhenUnset() {
        val previous = System.getProperty(DesktopSqliteFlags.PROPERTY)
        try {
            System.clearProperty(DesktopSqliteFlags.PROPERTY)
            assertEquals(DesktopSqliteFlags.isWindows(), DesktopSqliteFlags.useJdbcSqlite())
        } finally {
            if (previous == null) {
                System.clearProperty(DesktopSqliteFlags.PROPERTY)
            } else {
                System.setProperty(DesktopSqliteFlags.PROPERTY, previous)
            }
        }
    }
}

class JdbcSQLiteDriverTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun jdbcUrlUsesFileUriForWindowsDrivePaths() {
        assertEquals(
            "jdbc:sqlite:file:/C:/Users/me/notes.db",
            JdbcSQLiteDriver.jdbcUrl("C:/Users/me/notes.db"),
        )
        assertEquals(
            "jdbc:sqlite:file:/C:/Users/me/notes.db",
            JdbcSQLiteDriver.jdbcUrl("C:\\Users\\me\\notes.db"),
        )
        assertEquals("jdbc:sqlite::memory:", JdbcSQLiteDriver.jdbcUrl(":memory:"))
    }

    @Test
    fun plaintextRoundTripCreateInsertSelect() {
        val dbFile = File(temp.root, "probe.db")
        val driver = JdbcSQLiteDriver()
        driver.open(dbFile.absolutePath).use { connection ->
            connection.prepare(
                "CREATE TABLE note (id INTEGER PRIMARY KEY NOT NULL, title TEXT NOT NULL)",
            ).use { stmt ->
                assertFalse(stmt.step())
            }
            connection.prepare("INSERT INTO note (id, title) VALUES (?, ?)").use { stmt ->
                stmt.bindLong(1, 1L)
                stmt.bindText(2, "hello")
                assertFalse(stmt.step())
            }
            connection.prepare("SELECT id, title FROM note WHERE id = ?").use { stmt ->
                stmt.bindLong(1, 1L)
                assertTrue(stmt.step())
                assertEquals(1L, stmt.getLong(0))
                assertEquals("hello", stmt.getText(1))
                assertEquals(1, stmt.getColumnType(0)) // SQLITE_DATA_INTEGER
                assertEquals(3, stmt.getColumnType(1)) // SQLITE_DATA_TEXT
                assertFalse(stmt.step())
            }
        }
        assertTrue(dbFile.exists())
        assertTrue(dbFile.length() > 0)
    }
}
