package com.aus.notelikeus.data.local

/**
 * Feature flags for Desktop notes-DB driver selection.
 *
 * **Default on Windows:** migrate to SQLCipher (v4) and open through [JdbcSQLiteDriver] with the
 * DPAPI-sealed passphrase from [DesktopDatabaseKeyManager].
 *
 * **Default elsewhere (Linux CI, macOS):** keep [androidx.sqlite.driver.bundled.BundledSQLiteDriver]
 * — those hosts have no DPAPI, and Desktop CI runs on Linux.
 *
 * Override with `notelikeus.desktop.jdbcSqlite` / `NOTELIKEUS_DESKTOP_JDBC_SQLITE`
 * (`true`/`false`/`on`/`off`/…).
 */
object DesktopSqliteFlags {
    fun useJdbcSqlite(): Boolean =
        parseBool(System.getProperty(PROPERTY))
            ?: parseBool(System.getenv(ENV))
            ?: isWindows()

    fun isWindows(): Boolean {
        val name = System.getProperty("os.name")?.lowercase() ?: return false
        return name.contains("win")
    }

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
