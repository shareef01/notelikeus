package com.aus.notelikeus.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseMigrationsTest {

    @Test
    fun migrations_chainFromVersion1To4() {
        assertEquals(1, DatabaseMigrations.MIGRATION_1_2.startVersion)
        assertEquals(2, DatabaseMigrations.MIGRATION_1_2.endVersion)
        assertEquals(2, DatabaseMigrations.MIGRATION_2_3.startVersion)
        assertEquals(3, DatabaseMigrations.MIGRATION_2_3.endVersion)
        assertEquals(3, DatabaseMigrations.MIGRATION_3_4.startVersion)
        assertEquals(4, DatabaseMigrations.MIGRATION_3_4.endVersion)
        assertEquals(4, DatabaseMigrations.MIGRATION_4_5.startVersion)
        assertEquals(5, DatabaseMigrations.MIGRATION_4_5.endVersion)
        assertEquals(4, DatabaseMigrations.ALL.size)
    }
}
