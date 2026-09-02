package com.aus.notelikeus.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseMigrationsTest {

    @Test
    fun migrations_chainFromVersion1To11() {
        assertEquals(1, DatabaseMigrations.MIGRATION_1_2.startVersion)
        assertEquals(2, DatabaseMigrations.MIGRATION_1_2.endVersion)
        assertEquals(2, DatabaseMigrations.MIGRATION_2_3.startVersion)
        assertEquals(3, DatabaseMigrations.MIGRATION_2_3.endVersion)
        assertEquals(3, DatabaseMigrations.MIGRATION_3_4.startVersion)
        assertEquals(4, DatabaseMigrations.MIGRATION_3_4.endVersion)
        assertEquals(4, DatabaseMigrations.MIGRATION_4_5.startVersion)
        assertEquals(5, DatabaseMigrations.MIGRATION_4_5.endVersion)
        assertEquals(5, DatabaseMigrations.MIGRATION_5_6.startVersion)
        assertEquals(6, DatabaseMigrations.MIGRATION_5_6.endVersion)
        assertEquals(6, DatabaseMigrations.MIGRATION_6_7.startVersion)
        assertEquals(7, DatabaseMigrations.MIGRATION_6_7.endVersion)
        assertEquals(7, DatabaseMigrations.MIGRATION_7_8.startVersion)
        assertEquals(8, DatabaseMigrations.MIGRATION_7_8.endVersion)
        assertEquals(8, DatabaseMigrations.MIGRATION_8_9.startVersion)
        assertEquals(9, DatabaseMigrations.MIGRATION_8_9.endVersion)
        assertEquals(9, DatabaseMigrations.MIGRATION_9_10.startVersion)
        assertEquals(10, DatabaseMigrations.MIGRATION_9_10.endVersion)
        assertEquals(10, DatabaseMigrations.MIGRATION_10_11.startVersion)
        assertEquals(11, DatabaseMigrations.MIGRATION_10_11.endVersion)
        assertEquals(10, DatabaseMigrations.ALL.size)
    }
}
