package com.aus.notelikeus.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import org.sqlite.JDBC
import org.sqlite.mc.SQLiteMCSqlCipherConfig
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

/**
 * Room [SQLiteDriver] backed by Willena `sqlite-jdbc` (Multiple Ciphers / SQLCipher v4).
 *
 * When [passphrase] is null the file stays plaintext. When non-null, opens with SQLCipher v4
 * defaults; the 32-byte secret is hex-encoded for Willena's string key API (binary `\0` would
 * truncate a C passphrase). Desktop-only — not the same wire format as Android's raw `byte[]` key.
 *
 * [hasConnectionPool] is false — each [open] owns one JDBC connection.
 */
class JdbcSQLiteDriver(
    private val passphrase: ByteArray? = null,
) : SQLiteDriver {
    override val hasConnectionPool: Boolean
        get() = false

    override fun open(fileName: String): SQLiteConnection {
        val connection = openJdbcConnection(fileName, passphrase)
        connection.autoCommit = true
        return JdbcSQLiteConnection(connection)
    }

    companion object {
        fun jdbcUrl(fileName: String): String {
            if (fileName == ":memory:" || fileName.startsWith("file:")) {
                return "jdbc:sqlite:$fileName"
            }
            val normalized = fileName.replace('\\', '/')
            return if (normalized.length >= 2 && normalized[1] == ':') {
                "jdbc:sqlite:file:/$normalized"
            } else {
                "jdbc:sqlite:$normalized"
            }
        }

        /**
         * Opens a JDBC connection, optionally under SQLCipher v4.
         * Shared by Room and [DesktopPlaintextDatabaseMigrator].
         */
        fun openJdbcConnection(fileName: String, passphrase: ByteArray?): Connection {
            DriverManager.registerDriver(JDBC())
            val url = jdbcUrl(fileName)
            return if (passphrase == null) {
                DriverManager.getConnection(url)
            } else {
                SQLiteMCSqlCipherConfig.getV4Defaults()
                    .withKey(passphraseAsHexKey(passphrase))
                    .build()
                    .createConnection(url)
            }
        }

        /**
         * Hex-encodes the 32-byte secret for Willena's string `withKey`.
         * ISO-8859-1 is unsafe: a random `\0` truncates the C passphrase.
         */
        fun passphraseAsHexKey(passphrase: ByteArray): String =
            passphrase.joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
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
        private const val SQLITE_DATA_INTEGER = 1
        private const val SQLITE_DATA_FLOAT = 2
        private const val SQLITE_DATA_TEXT = 3
        private const val SQLITE_DATA_BLOB = 4
        private const val SQLITE_DATA_NULL = 5
    }
}
