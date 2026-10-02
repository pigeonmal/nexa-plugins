package dev.nexa.sqlite

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase as PlatformSQLiteDatabase
import android.database.sqlite.SQLiteProgram
import android.database.sqlite.SQLiteQuery
import dev.nexa.core.NexaRuntimeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A serialized app-private SQLite connection. Blocking work always runs on IO. */
public class SQLiteDatabaseImpl(name: String) : SQLiteDatabaseSpec {
    private val name = name
    private val lock = Any()
    private var database: PlatformSQLiteDatabase? = null
    private var openFailure: SQLiteError? = null
    private var disposed = false

    init {
        if (!DATABASE_NAME.matches(name)) {
            openFailure = SQLiteError.invalidDatabaseName(
                "Database names must contain 1–64 ASCII letters, digits, underscores, or hyphens.",
            )
        }
    }

    override suspend fun execute(sql: String, parameters: List<SQLiteValue>): SQLiteExecutionResult =
        withContext(Dispatchers.IO) {
            synchronized(lock) { executeLocked(requireDatabase(), sql, parameters) }
        }

    override suspend fun query(sql: String, parameters: List<SQLiteValue>): SQLiteQueryResult =
        withContext(Dispatchers.IO) {
            synchronized(lock) { queryLocked(requireDatabase(), sql, parameters) }
        }

    override suspend fun executeTransaction(
        statements: List<SQLiteStatement>,
    ): List<SQLiteExecutionResult> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val db = requireDatabase()
            try {
                db.beginTransaction()
                val results = statements.map { statement ->
                    executeLocked(db, statement.sql, statement.parameters)
                }
                db.setTransactionSuccessful()
                results
            } catch (error: SQLiteError) {
                throw SQLiteError.transactionFailed(error.message ?: "Transaction failed")
            } catch (error: Exception) {
                throw SQLiteError.transactionFailed(error.message ?: "Transaction failed")
            } finally {
                if (db.inTransaction()) db.endTransaction()
            }
        }
    }

    override suspend fun migrate(migrations: List<SQLiteMigration>): Int = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val db = requireDatabase()
            val ordered = migrations.sortedBy(SQLiteMigration::version)
            if (ordered.withIndex().any { (index, migration) -> migration.version != index + 1 }) {
                throw SQLiteError.migrationFailed("Migration versions must be unique and consecutive from 1.")
            }

            try {
                db.beginTransaction()
                var version = db.version
                for (migration in ordered) {
                    if (migration.version <= version) continue
                    if (migration.version != version + 1) {
                        throw SQLiteError.migrationFailed("Missing migration version ${version + 1}.")
                    }
                    for (statement in migration.statements) {
                        executeLocked(db, statement.sql, statement.parameters)
                    }
                    db.version = migration.version
                    version = migration.version
                }
                db.setTransactionSuccessful()
                version
            } catch (error: SQLiteError) {
                throw error
            } catch (error: Exception) {
                throw SQLiteError.migrationFailed(error.message ?: "Migration failed")
            } finally {
                if (db.inTransaction()) db.endTransaction()
            }
        }
    }

    override suspend fun userVersion(): Int = withContext(Dispatchers.IO) {
        synchronized(lock) { requireDatabase().version }
    }

    override fun dispose() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            database?.close()
            database = null
        }
    }

    private fun requireDatabase(): PlatformSQLiteDatabase {
        if (disposed) throw SQLiteError.closed
        openFailure?.let { throw it }
        database?.let { return it }
        val opened = try {
            val context = NexaRuntimeCore.context().applicationContext
            val file = context.getDatabasePath("$name.sqlite3")
            file.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                    throw IllegalStateException("Could not create the app database directory")
                }
            }
            PlatformSQLiteDatabase.openOrCreateDatabase(file, null).apply {
                enableWriteAheadLogging()
                setForeignKeyConstraintsEnabled(true)
            }
        } catch (error: Exception) {
            val failure = SQLiteError.openFailed(error.message ?: "Could not open database")
            openFailure = failure
            throw failure
        }
        database = opened
        return opened
    }

    private fun executeLocked(
        db: PlatformSQLiteDatabase,
        sql: String,
        parameters: List<SQLiteValue>,
    ): SQLiteExecutionResult {
        val statement = try {
            db.compileStatement(sql)
        } catch (error: Exception) {
            throw SQLiteError.prepareFailed(error.message ?: "Could not prepare statement")
        }
        try {
            bindAll(statement, parameters)
            val rowsAffected = statement.executeUpdateDelete().coerceAtLeast(0).toLong()
            return SQLiteExecutionResult(
                rowsAffected = rowsAffected,
                lastInsertRowId = scalarLong(db, "SELECT last_insert_rowid()"),
            )
        } catch (error: SQLiteError) {
            throw error
        } catch (error: Exception) {
            throw SQLiteError.executeFailed(error.message ?: "Statement execution failed")
        } finally {
            statement.close()
        }
    }

    private fun queryLocked(
        db: PlatformSQLiteDatabase,
        sql: String,
        parameters: List<SQLiteValue>,
    ): SQLiteQueryResult {
        val cursor = try {
            db.rawQueryWithFactory(
                { _, driver, editTable, query ->
                    val actualQuery = query ?: throw IllegalStateException("Missing SQLite query")
                    bindAll(actualQuery, parameters)
                    SQLiteCursor(
                        driver ?: throw IllegalStateException("Missing SQLite cursor driver"),
                        editTable,
                        actualQuery,
                    )
                },
                sql,
                null,
                "",
            )
        } catch (error: SQLiteError) {
            throw error
        } catch (error: Exception) {
            throw SQLiteError.queryFailed(error.message ?: "Query failed")
        }

        cursor.use {
            val columnNames = it.columnNames.toList()
            val rows = ArrayList<List<SQLiteValue>>()
            while (it.moveToNext()) {
                rows += columnNames.indices.map { index -> readValue(it, index) }
            }
            return SQLiteQueryResult(columnNames, rows)
        }
    }

    private fun bindAll(program: SQLiteProgram, parameters: List<SQLiteValue>) {
        parameters.forEachIndexed { index, value ->
            val hasInteger = value.integerValue != null
            val hasReal = value.realValue != null
            val hasText = value.textValue != null
            val hasBlob = value.blobValue != null
            val valid = when (value.kind) {
                SQLiteValueKind.nullValue -> !hasInteger && !hasReal && !hasText && !hasBlob
                SQLiteValueKind.integer -> hasInteger && !hasReal && !hasText && !hasBlob
                SQLiteValueKind.real -> !hasInteger && hasReal && !hasText && !hasBlob
                SQLiteValueKind.text -> !hasInteger && !hasReal && hasText && !hasBlob
                SQLiteValueKind.blob -> !hasInteger && !hasReal && !hasText && hasBlob
            }
            if (!valid) throw SQLiteError.invalidValue("SQLiteValue payload does not match its kind.")
            try {
                when (value.kind) {
                    SQLiteValueKind.nullValue -> program.bindNull(index + 1)
                    SQLiteValueKind.integer -> program.bindLong(index + 1, value.integerValue!!)
                    SQLiteValueKind.real -> program.bindDouble(index + 1, value.realValue!!)
                    SQLiteValueKind.text -> program.bindString(index + 1, value.textValue!!)
                    SQLiteValueKind.blob -> program.bindBlob(index + 1, value.blobValue!!)
                }
            } catch (error: SQLiteError) {
                throw error
            } catch (error: Exception) {
                throw SQLiteError.bindFailed(error.message ?: "Could not bind parameter ${index + 1}")
            }
        }
    }

    private fun readValue(cursor: Cursor, index: Int): SQLiteValue = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> SQLiteValue(SQLiteValueKind.nullValue, null, null, null, null)
        Cursor.FIELD_TYPE_INTEGER -> SQLiteValue(
            SQLiteValueKind.integer,
            cursor.getLong(index),
            null,
            null,
            null,
        )
        Cursor.FIELD_TYPE_FLOAT -> SQLiteValue(
            SQLiteValueKind.real,
            null,
            cursor.getDouble(index),
            null,
            null,
        )
        Cursor.FIELD_TYPE_BLOB -> SQLiteValue(
            SQLiteValueKind.blob,
            null,
            null,
            null,
            cursor.getBlob(index),
        )
        else -> SQLiteValue(SQLiteValueKind.text, null, null, cursor.getString(index), null)
    }

    private fun scalarLong(db: PlatformSQLiteDatabase, sql: String): Long {
        val statement = db.compileStatement(sql)
        return try {
            statement.simpleQueryForLong()
        } finally {
            statement.close()
        }
    }

    private companion object {
        val DATABASE_NAME = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
