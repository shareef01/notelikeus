package com.aus.notelikeus.data.local

/**
 * Feature flags for Desktop notes-DB driver selection.
 *
 * Default remains [androidx.sqlite.driver.bundled.BundledSQLiteDriver] (plaintext, current format).
 * Set `notelikeus.desktop.jdbcSqlite=true` (system property) or `NOTELIKEUS_DESKTOP_JDBC_SQLITE=true`
 * to open Room through [JdbcSQLiteDriver] — still **without** a passphrase — so the JDBC path can
 * be exercised before encryption/migration lands.
 */
object DesktopSqliteFlags {
    fun useJdbcSqlite(): Boolean =
        parseBool(System.getProperty(PROPERTY))
            ?: parseBool(System.getenv(ENV))
            ?: false

    private fun parseBool(raw: String?): Boolean? {
        val value = raw?.trim()?.lowercase().orEmpty()
        return when (value) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            "" -> null
            else -> null
        }
    }

    const val PROPERTY = "notelikeus.desktop.jdbcSqlite"
    const val ENV = "NOTELIKEUS_DESKTOP_JDBC_SQLITE"
}
