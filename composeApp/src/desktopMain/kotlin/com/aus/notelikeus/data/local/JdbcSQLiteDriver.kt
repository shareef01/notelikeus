package com.aus.notelikeus.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import org.sqlite.JDBC
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

/**
 * Room [SQLiteDriver] backed by Willena `sqlite-jdbc` (Multiple Ciphers build).
 *
 * Slice 2 of Desktop notes-DB encryption: prove Room can run on this JDBC stack **without**
 * applying a key, so the on-disk format stays plaintext SQLite. A later slice passes the
 * [DesktopDatabaseKeyManager] passphrase via SQLCipher URI parameters.
 *
 * [hasConnectionPool] is false — each [open] owns one JDBC connection; Room's pool manages
 * concurrency, matching [androidx.sqlite.driver.bundled.BundledSQLiteDriver]'s contract for
 * unpooled drivers.
 */
class JdbcSQLiteDriver : SQLiteDriver {
    override val hasConnectionPool: Boolean
        get() = false

    override fun open(fileName: String): SQLiteConnection {
        // Ensure the Willena driver is registered even if ServiceLoader is stripped in a fat jar.
        DriverManager.registerDriver(JDBC())
        val url = jdbcUrl(fileName)
        val connection = DriverManager.getConnection(url)
        connection.autoCommit = true
        return JdbcSQLiteConnection(connection)
    }

    companion object {
        /**
         * Builds a JDBC URL for a plaintext database. Encryption parameters are intentionally
         * omitted in this slice.
         */
        fun jdbcUrl(fileName: String): String {
            if (fileName == ":memory:" || fileName.startsWith("file:")) {
                return "jdbc:sqlite:$fileName"
            }
            // Absolute Windows paths need the file: URI form so the driver does not treat the
            // drive letter as a URL scheme.
            val normalized = fileName.replace('\\', '/')
            return if (normalized.length >= 2 && normalized[1] == ':') {
                "jdbc:sqlite:file:/$normalized"
            } else {
                "jdbc:sqlite:$normalized"
            }
        }
    }
}

private class JdbcSQLiteConnection(
    private val connection: Connection,
) : SQLiteConnection {
    override fun inTransaction(): Boolean = try {
        !connection.autoCommit
    } catch (_: SQLException) {
        false
    }

    override fun prepare(sql: String): SQLiteStatement =
        JdbcSQLiteStatement(connection.prepareStatement(sql))

    override fun close() {
        connection.close()
    }
}

private class JdbcSQLiteStatement(
    private val statement: PreparedStatement,
) : SQLiteStatement {
    private var resultSet: ResultSet? = null
    private var executed = false

    override fun bindBlob(index: Int, value: ByteArray) {
        statement.setBytes(index, value)
    }

    override fun bindDouble(index: Int, value: Double) {
        statement.setDouble(index, value)
    }

    override fun bindLong(index: Int, value: Long) {
        statement.setLong(index, value)
    }

    override fun bindText(index: Int, value: String) {
        statement.setString(index, value)
    }

    override fun bindNull(index: Int) {
        statement.setNull(index, Types.NULL)
    }

    override fun getBlob(index: Int): ByteArray =
        resultSet().getBytes(index + 1) ?: ByteArray(0)

    override fun getDouble(index: Int): Double = resultSet().getDouble(index + 1)

    override fun getLong(index: Int): Long = resultSet().getLong(index + 1)

    override fun getText(index: Int): String = resultSet().getString(index + 1).orEmpty()

    override fun isNull(index: Int): Boolean {
        val rs = resultSet()
        rs.getObject(index + 1)
        return rs.wasNull()
    }

    override fun getColumnCount(): Int = resultSet().metaData.columnCount

    override fun getColumnName(index: Int): String = resultSet().metaData.getColumnLabel(index + 1)

    override fun getColumnType(index: Int): Int {
        val rs = resultSet()
        if (rs.getObject(index + 1) == null || rs.wasNull()) return SQLITE_DATA_NULL
        return when (rs.metaData.getColumnType(index + 1)) {
            Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> SQLITE_DATA_BLOB
            Types.FLOAT, Types.DOUBLE, Types.REAL, Types.NUMERIC, Types.DECIMAL -> SQLITE_DATA_FLOAT
            Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT, Types.BOOLEAN ->
                SQLITE_DATA_INTEGER
            else -> SQLITE_DATA_TEXT
        }
    }

    override fun step(): Boolean {
        if (!executed) {
            executed = true
            return if (statement.execute()) {
                val rs = statement.resultSet
                resultSet = rs
                rs != null && rs.next()
            } else {
                resultSet = null
                false
            }
        }
        val rs = resultSet ?: return false
        return rs.next()
    }

    override fun reset() {
        resultSet?.close()
        resultSet = null
        executed = false
    }

    override fun clearBindings() {
        statement.clearParameters()
    }

    override fun close() {
        resultSet?.close()
        resultSet = null
        statement.close()
    }

    private fun resultSet(): ResultSet =
        resultSet ?: error("No active result row; call step() first")

    companion object {
        // Matches androidx.sqlite.SQLite.SQLITE_DATA_* (JVM facade is not a Kotlin type).
        private const val SQLITE_DATA_INTEGER = 1
        private const val SQLITE_DATA_FLOAT = 2
        private const val SQLITE_DATA_TEXT = 3
        private const val SQLITE_DATA_BLOB = 4
        private const val SQLITE_DATA_NULL = 5
    }
}
