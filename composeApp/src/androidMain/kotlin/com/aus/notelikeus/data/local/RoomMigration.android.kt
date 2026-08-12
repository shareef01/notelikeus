package com.aus.notelikeus.data.local

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.db.SupportSQLiteDatabase

actual abstract class RoomMigration actual constructor(
    startVersion: Int,
    endVersion: Int
) : Migration(startVersion, endVersion) {

    actual abstract override fun migrate(connection: SQLiteConnection)

    override fun migrate(db: SupportSQLiteDatabase) {
        val connection = SupportSQLiteConnectionProxy(db)
        try {
            migrate(connection)
        } finally {
            connection.close()
        }
    }
}

/**
 * Adapts the Android [SupportSQLiteDatabase] handed to us by Room's SupportSQLite path (which is
 * what SQLCipher's `SupportOpenHelperFactory` puts us on) to the KMP [SQLiteConnection] API that
 * the shared migrations are written against.
 *
 * Reads are supported, not just `execSQL`: migrations legitimately need to inspect the schema
 * (e.g. `pragma_table_info`) to stay idempotent, and a proxy that throws on every getter forces
 * migrations into blind try/catch blocks that hide real failures.
 */
private class SupportSQLiteConnectionProxy(private val db: SupportSQLiteDatabase) : SQLiteConnection {
    override fun prepare(sql: String): SQLiteStatement = SupportSQLiteStatementProxy(db, sql)
    override fun close() {}
}

private class SupportSQLiteStatementProxy(
    private val db: SupportSQLiteDatabase,
    private val sql: String
) : SQLiteStatement {

    private val bindings = sortedMapOf<Int, Any?>()
    private var cursor: Cursor? = null
    private var executed = false

    private val isQuery: Boolean
        get() = sql.trimStart().takeWhile { !it.isWhitespace() }.let { verb ->
            verb.equals("SELECT", ignoreCase = true) ||
                verb.equals("PRAGMA", ignoreCase = true) ||
                verb.equals("WITH", ignoreCase = true) ||
                verb.equals("EXPLAIN", ignoreCase = true)
        }

    private fun args(): Array<Any?> = bindings.values.toTypedArray()

    private fun requireCursor(): Cursor =
        cursor ?: error("No row available: call step() before reading columns")

    override fun step(): Boolean {
        if (!isQuery) {
            // Statements are executed once; step() reports "no row", matching the KMP contract.
            if (!executed) {
                if (bindings.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args())
                executed = true
            }
            return false
        }
        val active = cursor ?: db.query(sql, args()).also { cursor = it }
        return active.moveToNext()
    }

    override fun bindBlob(index: Int, value: ByteArray) { bindings[index] = value }
    override fun bindDouble(index: Int, value: Double) { bindings[index] = value }
    override fun bindLong(index: Int, value: Long) { bindings[index] = value }
    override fun bindText(index: Int, value: String) { bindings[index] = value }
    override fun bindNull(index: Int) { bindings[index] = null }

    override fun getBlob(index: Int): ByteArray = requireCursor().getBlob(index)
    override fun getDouble(index: Int): Double = requireCursor().getDouble(index)
    override fun getLong(index: Int): Long = requireCursor().getLong(index)
    override fun getText(index: Int): String = requireCursor().getString(index)
    override fun isNull(index: Int): Boolean = requireCursor().isNull(index)

    override fun getColumnCount(): Int = cursor?.columnCount ?: 0
    override fun getColumnName(index: Int): String = requireCursor().getColumnName(index)

    // Cursor.FIELD_TYPE_* and androidx.sqlite's SQLITE_DATA_* share the same integer values.
    override fun getColumnType(index: Int): Int = requireCursor().getType(index)

    override fun reset() {
        cursor?.close()
        cursor = null
        executed = false
    }

    override fun clearBindings() {
        bindings.clear()
    }

    override fun close() {
        cursor?.close()
        cursor = null
    }
}
